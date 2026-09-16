/*
 * PlaybackRouteMemory.kt
 * Tier 2 self-healing: a tiny, disk-backed memory of which decode path (engine +
 * stage) a given live channel last played STABLY on, so the next start can skip
 * straight to that path instead of re-walking the hardware-first fallback ladder
 * and re-incurring the same greens / start-timeouts the user already waited
 * through.
 *
 * What it stores: per channel-key, the winning Stage name (EXO / VLC_HW / VLC_SW)
 * plus tiny success/failure counters and timestamps, and a small per-channel
 * quality record (recent rebuffers, dropped-frame breaches) the controller uses
 * to seed the adaptive buffer on the next visit. NO URLs, tokens or account
 * ids — only an opaque caller-supplied key (channel id + container format).
 *
 * Safety model (mirrors StabilityTelemetry):
 *   - In-memory map is the source of truth on the hot path; reads NEVER touch disk.
 *   - Writes are pushed to a single-thread daemon executor, so no caller (least of
 *     all the main thread or a libVLC native callback) ever blocks on I/O.
 *   - Everything is best-effort and never throws: a corrupt entry is skipped, a
 *     failed write is dropped. A wrong/stale suggestion can only ever cost ONE
 *     extra failed stage attempt — PlayerController distrusts a remembered stage
 *     that fails before proving stable and falls back to the normal base ladder.
 *   - Entries are capped (LRU by last-used) and expire, so the file stays tiny.
 */
package com.iptv.player.util

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object PlaybackRouteMemory {

    private const val FILE = "route-memory.jsonl"

    /** Bump to invalidate all stored entries if the record shape ever changes. */
    // v2 invalidates routes learned before real-frame/loss/green health checks.
    // Only this tiny cache is affected; login and Settings live in DataStore.
    // v3 invalidates routes learned before sustained dropped-frame quality was
    // part of the definition of stable playback.
    // v4 invalidates routes learned before rapid-zap session isolation and the
    // corrected Fire TV frame-quality gates. Only this tiny route cache is
    // discarded; account credentials and every user setting remain untouched.
    // v5 re-evaluates only old full-software routes: v4 could learn VLC_SW
    // after two harmless AC3 underruns. Keep working EXO/VLC_HW routes and all
    // user settings; newly proven VLC_SW routes may still be remembered.
    // v6 retries pre-MPEG-PCM full-software routes once so the audio-only
    // renderer can keep video in Media3. Preserve working hardware routes.
    // v7 retries software routes learned before bounded MPEG Layer-II recovery.
    // Otherwise an old audio glitch could bypass the fixed hardware path for
    // fourteen days. Existing working hardware routes remain eligible.
    // The per-channel quality record (rb/db/r fields) is additive to v7: older
    // v7 lines simply load with an empty record, so no schema bump is needed.
    private const val SCHEMA = 7

    internal fun acceptsStoredRoute(version: Int, stage: String): Boolean =
        stage in setOf("EXO", "VLC_HW", "VLC_SW") &&
            (version == SCHEMA || (version in 4..6 && stage != "VLC_SW"))

    /** A line may also carry only a quality record (no stable stage learned yet). */
    internal fun acceptsStoredEntry(version: Int, stage: String): Boolean =
        if (stage.isEmpty()) version == SCHEMA else acceptsStoredRoute(version, stage)

    /** Hard cap on remembered channels; the least-recently-used is evicted past this. */
    private const val MAX_ENTRIES = 500

    /** A route not confirmed for this long is forgotten (channel/codec may have changed). */
    private const val TTL_MS = 14L * 24 * 60 * 60 * 1000

    /** Quality evidence older than this says nothing about today's link/provider. */
    internal const val RECORD_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Consecutive pre-stable failures of a remembered route before it is dropped
     * entirely. 2 = tolerate a single transient (e.g. a server restart right at
     * startup) without forgetting an otherwise-good route.
     */
    private const val MAX_FAILURES = 2

    /** Bounds on the per-channel quality counters (also the adaptive seed cap). */
    private const val MAX_REBUFFER_COUNT = 6
    private const val MAX_BREACH_COUNT = 9

    /** Sessions whose record shows at least this many rebuffers start on a larger buffer. */
    private const val SEED_MIN_REBUFFERS = 2

    /**
     * Per-channel quality record. [rebufferCount] is a decayed count over the
     * last few sessions (halved on every new session, incremented per rebuffer),
     * so a channel that stopped rebuffering falls back to a fast start within
     * two or three visits. [lastStableStage] is null until a stage proved stable.
     */
    data class ChannelRecord(
        val rebufferCount: Int,
        val droppedFrameBreaches: Int,
        val lastStableStage: String?,
        val updatedAtMs: Long,
    )

    private class Entry(
        // "" = no stable stage learned yet (record-only entry).
        var stage: String,
        var ok: Int,
        var fail: Int,
        val learnedAt: Long,
        var usedAt: Long,
        var rebuffers: Int = 0,
        var breaches: Int = 0,
        var recordAt: Long = 0L,
    )

    private val lock = Any()
    private val routes = HashMap<String, Entry>()

    @Volatile private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "route-memory").apply { isDaemon = true }
    }

    /** Load routes learned by a previous run. Call once, early, on startup. */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        file = f // set synchronously so writes can persist immediately
        io.execute {
            runCatching {
                if (!f.exists()) return@execute
                val now = System.currentTimeMillis()
                f.readLines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    val key = o.optString("k", "")
                    val stage = o.optString("s", "")
                    if (!acceptsStoredEntry(o.optInt("v", 0), stage)) return@forEach
                    if (key.isEmpty()) return@forEach
                    val usedAt = o.optLong("u", 0L)
                    if (now - usedAt > TTL_MS) return@forEach // drop expired on load
                    synchronized(lock) {
                        if (routes.size < MAX_ENTRIES) {
                            routes[key] = Entry(
                                stage = stage,
                                ok = o.optInt("ok", 0),
                                fail = o.optInt("f", 0),
                                learnedAt = o.optLong("l", usedAt),
                                usedAt = usedAt,
                                rebuffers = o.optInt("rb", 0).coerceIn(0, MAX_REBUFFER_COUNT),
                                breaches = o.optInt("db", 0).coerceIn(0, MAX_BREACH_COUNT),
                                recordAt = o.optLong("r", 0L),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The Stage name this [key] last proved stable on, or null when there is no
     * usable memory (absent / expired / [key] null). Pure in-memory read.
     */
    fun bestStage(key: String?): String? {
        val k = key ?: return null
        return synchronized(lock) {
            val e = routes[k] ?: return@synchronized null
            if (System.currentTimeMillis() - e.usedAt > TTL_MS) {
                routes.remove(k)
                null
            } else {
                e.stage.ifEmpty { null }
            }
        }
    }

    /**
     * The channel's quality record, or null when absent / older than
     * [RECORD_TTL_MS]. Pure in-memory read; never touches disk.
     */
    fun record(key: String?, nowMs: Long = System.currentTimeMillis()): ChannelRecord? {
        val k = key ?: return null
        return synchronized(lock) {
            val e = routes[k] ?: return@synchronized null
            if (!recordIsFresh(e.recordAt, nowMs)) return@synchronized null
            ChannelRecord(
                rebufferCount = e.rebuffers,
                droppedFrameBreaches = e.breaches,
                lastStableStage = e.stage.ifEmpty { null },
                updatedAtMs = e.recordAt,
            )
        }
    }

    internal fun recordIsFresh(recordAtMs: Long, nowMs: Long): Boolean =
        recordAtMs > 0L && nowMs - recordAtMs in 0..RECORD_TTL_MS

    /**
     * Adaptive-buffer seed for a new session: a channel that rebuffered at least
     * [SEED_MIN_REBUFFERS] times recently starts at NORMAL/HIGH straight away
     * (AdaptiveBufferPolicy grows with this count); anything less starts fresh.
     */
    fun seedRebuffers(record: ChannelRecord?): Int {
        val count = record?.rebufferCount ?: 0
        return if (count >= SEED_MIN_REBUFFERS) count.coerceAtMost(MAX_REBUFFER_COUNT) else 0
    }

    /** Pure decay applied once per new session so stale trouble fades within a few visits. */
    internal fun decayedForNewSession(count: Int): Int = (count / 2).coerceAtLeast(0)

    /**
     * A new session starts for [key]: decay the quality counters so only recent
     * sessions influence the seed. Stale records (beyond [RECORD_TTL_MS]) are
     * restarted from zero. Never creates a disk write on its own.
     */
    fun beginSession(key: String?, nowMs: Long = System.currentTimeMillis()) {
        val k = key ?: return
        runCatching {
            synchronized(lock) {
                val e = routes[k] ?: return@synchronized
                if (recordIsFresh(e.recordAt, nowMs)) {
                    e.rebuffers = decayedForNewSession(e.rebuffers)
                    e.breaches = decayedForNewSession(e.breaches)
                } else {
                    e.rebuffers = 0
                    e.breaches = 0
                }
            }
        }
    }

    /** One mid-stream rebuffer on [key]'s current session. Bounded; best-effort. */
    fun recordRebuffer(key: String?, nowMs: Long = System.currentTimeMillis()) {
        updateRecord(key, nowMs) { e ->
            e.rebuffers = (e.rebuffers + 1).coerceAtMost(MAX_REBUFFER_COUNT)
        }
    }

    /** One confirmed sustained dropped-frame breach on [key]. Bounded; best-effort. */
    fun recordDroppedFrameBreach(key: String?, nowMs: Long = System.currentTimeMillis()) {
        updateRecord(key, nowMs) { e ->
            e.breaches = (e.breaches + 1).coerceAtMost(MAX_BREACH_COUNT)
        }
    }

    private fun updateRecord(key: String?, nowMs: Long, mutate: (Entry) -> Unit) {
        val k = key ?: return
        runCatching {
            synchronized(lock) {
                val e = routes[k] ?: Entry(
                    stage = "",
                    ok = 0,
                    fail = 0,
                    learnedAt = nowMs,
                    usedAt = nowMs,
                ).also { routes[k] = it }
                mutate(e)
                e.recordAt = nowMs
                e.usedAt = nowMs
                evictIfNeeded()
            }
            persistAsync()
        }
    }

    /** A stage played stably for [key]: remember it as the winner (overwrites any prior). */
    fun markStable(key: String?, stage: String) {
        val k = key ?: return
        if (stage.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val e = routes[k]
                if (e == null) {
                    routes[k] = Entry(stage = stage, ok = 1, fail = 0, learnedAt = now, usedAt = now)
                } else {
                    if (e.stage != stage) { e.stage = stage; e.ok = 0 }
                    e.ok += 1
                    e.fail = 0
                    e.usedAt = now
                }
                evictIfNeeded()
            }
            persistAsync()
        }
    }

    /**
     * A remembered stage failed BEFORE proving stable; distrust it. After
     * [MAX_FAILURES] the entry is dropped so future launches stop suggesting it.
     */
    fun markFailed(key: String?, stage: String) {
        val k = key ?: return
        runCatching {
            synchronized(lock) {
                val e = routes[k] ?: return@synchronized
                if (e.stage != stage) return@synchronized // not the route we suggested
                e.fail += 1
                e.usedAt = System.currentTimeMillis()
                if (e.fail >= MAX_FAILURES) dropRoute(k, e)
            }
            persistAsync()
        }
    }

    /**
     * Immediately forget a route that was proven decode-quality-incompatible
     * after its earlier stable window (for example periodic severe frame loss).
     */
    fun forget(key: String?, stage: String) {
        val k = key ?: return
        runCatching {
            val removed = synchronized(lock) {
                val e = routes[k] ?: return@synchronized false
                if (e.stage != stage) return@synchronized false
                dropRoute(k, e)
                true
            }
            if (removed) persistAsync()
        }
    }

    /**
     * Forget every learned route (safe mode): a remembered stage may itself be
     * what keeps crashing playback, so the crash-loop reset wipes the memory too.
     */
    fun clear() {
        runCatching {
            synchronized(lock) { routes.clear() }
            persistAsync() // empty map -> deletes the backing file
        }
    }

    /**
     * Caller must hold [lock]. Drops the stage suggestion but keeps a still-fresh
     * quality record: a channel that greened on hardware AND rebuffers should
     * still start its next visit with the larger buffer.
     */
    private fun dropRoute(key: String, e: Entry) {
        if (recordIsFresh(e.recordAt, System.currentTimeMillis()) && (e.rebuffers > 0 || e.breaches > 0)) {
            e.stage = ""
            e.ok = 0
            e.fail = 0
        } else {
            routes.remove(key)
        }
    }

    /** Caller must hold [lock]. Evict the least-recently-used entries past the cap. */
    private fun evictIfNeeded() {
        while (routes.size > MAX_ENTRIES) {
            var oldestKey: String? = null
            var oldest = Long.MAX_VALUE
            for ((k, e) in routes) if (e.usedAt < oldest) { oldest = e.usedAt; oldestKey = k }
            if (oldestKey == null) break
            routes.remove(oldestKey)
        }
    }

    private fun persistAsync() {
        val f = file ?: return
        io.execute {
            runCatching {
                val lines = synchronized(lock) {
                    routes.map { (k, e) ->
                        JSONObject().apply {
                            put("v", SCHEMA)
                            put("k", k)
                            put("s", e.stage)
                            put("ok", e.ok)
                            put("f", e.fail)
                            put("l", e.learnedAt)
                            put("u", e.usedAt)
                            put("rb", e.rebuffers)
                            put("db", e.breaches)
                            put("r", e.recordAt)
                        }.toString()
                    }
                }
                if (lines.isEmpty()) {
                    if (f.exists()) f.delete()
                    return@execute
                }
                val sb = StringBuilder(lines.size * 80)
                for (l in lines) sb.append(l).append('\n')
                f.writeText(sb.toString())
            }
        }
    }
}
