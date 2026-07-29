/*
 * PlaybackRouteMemory.kt
 * Tier 2 self-healing: a tiny, disk-backed memory of which decode path (engine +
 * stage) a given live channel last played STABLY on, so the next start can skip
 * straight to that path instead of re-walking the hardware-first fallback ladder
 * and re-incurring the same greens / start-timeouts the user already waited
 * through.
 *
 * What it stores: per channel-key, the winning Stage name (EXO / VLC_HW / VLC_SW)
 * plus tiny success/failure counters and timestamps. NO URLs, tokens or account
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
    private const val SCHEMA = 2

    /** Hard cap on remembered channels; the least-recently-used is evicted past this. */
    private const val MAX_ENTRIES = 500

    /** A route not confirmed for this long is forgotten (channel/codec may have changed). */
    private const val TTL_MS = 14L * 24 * 60 * 60 * 1000

    /**
     * Consecutive pre-stable failures of a remembered route before it is dropped
     * entirely. 2 = tolerate a single transient (e.g. a server restart right at
     * startup) without forgetting an otherwise-good route.
     */
    private const val MAX_FAILURES = 2

    private class Entry(
        var stage: String,
        var ok: Int,
        var fail: Int,
        val learnedAt: Long,
        var usedAt: Long,
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
                    if (o.optInt("v", 0) != SCHEMA) return@forEach
                    val key = o.optString("k", "")
                    val stage = o.optString("s", "")
                    if (key.isEmpty() || stage.isEmpty()) return@forEach
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
                e.stage
            }
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
                if (e.fail >= MAX_FAILURES) routes.remove(k)
            }
            persistAsync()
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
