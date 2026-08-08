/*
 * StabilityTelemetry.kt
 * Best-effort, disk-backed spool of discrete field-failure events (player stalls,
 * reconnects, engine fallbacks, start-timeouts, fatals, ANR freezes, suspected
 * abnormal exits). Events are accumulated and DRAINED by the heartbeat (attached
 * to the 60s beat) instead of one network call per event, to spare battery and
 * data on the low-end Fire TV sticks that make up most of the fleet.
 *
 * Why disk-backed: an OS OOM-kill or a native (libVLC / MediaCodec) crash can
 * take the process down BEFORE the next beat, so an in-memory-only ring would
 * lose exactly the events that matter most. Each record() persists the ring to a
 * tiny JSONL file (off the caller's thread), so the next launch re-loads and
 * ships them.
 *
 * Thread-safety: record() is called from the main thread AND libVLC native
 * callback threads, so all mutation happens under one lock; disk writes are
 * pushed to a single-thread executor so no caller (especially the main thread)
 * ever blocks on I/O. Entirely best-effort: it never throws.
 *
 * Privacy: no URLs, tokens or account ids — only the (clipped) channel title the
 * heartbeat already reports, plus engine/stage/severity and a short detail.
 */
package com.iptv.player.util

import android.content.Context
import com.iptv.player.playback.core.PlaybackQoeRecord
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object StabilityTelemetry {

    private const val FILE = "stability-spool.jsonl"

    /** Hard cap on spooled events; the oldest is dropped past this (and counted). */
    private const val MAX_EVENTS = 50
    private const val MAX_DETAIL = 300
    private const val MAX_CHANNEL = 200

    private val lock = Any()
    private val events = ArrayDeque<JSONObject>(MAX_EVENTS)
    private val dropped = AtomicInteger(0)

    @Volatile private var spoolFile: File? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stability-spool").apply { isDaemon = true }
    }

    /** Load events spooled by a previous run. Call once, early, on startup. */
    fun init(context: Context) {
        if (spoolFile != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        spoolFile = f // set synchronously so record() can persist immediately
        io.execute {
            runCatching {
                if (!f.exists()) return@execute
                f.readLines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    synchronized(lock) { if (events.size < MAX_EVENTS) events.addLast(obj) }
                }
            }
        }
    }

    /**
     * Record one stability event. Safe to call from any thread; never throws and
     * never does disk I/O on the caller's thread. Channel/kind default to whatever
     * is playing right now so most callers only pass a [type].
     */
    fun record(
        type: String,
        channel: String? = NowPlaying.title,
        kind: String? = NowPlaying.kind,
        engine: String? = null,
        stage: String? = null,
        severity: String? = null,
        detail: String? = null,
    ) {
        runCatching {
            val ev = JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("type", type)
                channel?.takeIf { it.isNotBlank() }?.let { put("ch", clip(it, MAX_CHANNEL)) }
                kind?.takeIf { it.isNotBlank() }?.let { put("kind", it) }
                engine?.takeIf { it.isNotBlank() }?.let { put("engine", it) }
                stage?.takeIf { it.isNotBlank() }?.let { put("stage", it) }
                severity?.takeIf { it.isNotBlank() }?.let { put("sev", it) }
                detail?.takeIf { it.isNotBlank() }?.let { put("detail", clip(it, MAX_DETAIL)) }
            }
            synchronized(lock) {
                events.addLast(ev)
                while (events.size > MAX_EVENTS) {
                    events.removeFirst()
                    dropped.incrementAndGet()
                }
            }
            persistAsync()
        }
    }

    /**
     * Persist one closed-schema QoE aggregate without channel/title/URL defaults.
     * The core record cannot contain arbitrary text; every accepted field is
     * enumerated by PlaybackQoeRecord.toSafeFields().
     */
    fun recordQoe(record: PlaybackQoeRecord) {
        runCatching {
            val event = JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("type", "playback_qoe")
                record.toSafeFields().forEach { (key, value) -> put(key, value) }
            }
            synchronized(lock) {
                events.addLast(event)
                while (events.size > MAX_EVENTS) {
                    events.removeFirst()
                    dropped.incrementAndGet()
                }
            }
            persistAsync()
        }
    }

    /**
     * Up to [max] oldest events to attach to a heartbeat, WITHOUT removing them
     * (a failed upload keeps them). Pass the returned list back to
     * [confirmUploaded] only after a successful POST.
     */
    fun snapshot(max: Int): List<JSONObject> =
        synchronized(lock) { if (events.isEmpty()) emptyList() else events.take(max) }

    /** Count of events dropped to ring overflow that hasn't been reported yet. */
    fun snapshotDropped(): Int = dropped.get()

    /** Acknowledge [count] dropped events were reported so they aren't re-counted. */
    fun confirmDropped(count: Int) {
        if (count <= 0) return
        // Manual CAS loop: AtomicInteger.updateAndGet is API 24+, minSdk here is 21.
        while (true) {
            val cur = dropped.get()
            if (dropped.compareAndSet(cur, (cur - count).coerceAtLeast(0))) return
        }
    }

    /** Remove the previously-snapshotted events after a successful upload (by identity). */
    fun confirmUploaded(uploaded: List<JSONObject>) {
        if (uploaded.isEmpty()) return
        synchronized(lock) {
            val ids = Collections.newSetFromMap(IdentityHashMap<JSONObject, Boolean>())
            ids.addAll(uploaded)
            val it = events.iterator()
            while (it.hasNext()) if (ids.contains(it.next())) it.remove()
        }
        persistAsync()
    }

    private fun persistAsync() {
        val f = spoolFile ?: return
        io.execute {
            runCatching {
                val snapshot = synchronized(lock) { events.toList() }
                if (snapshot.isEmpty()) {
                    if (f.exists()) f.delete()
                    return@execute
                }
                val sb = StringBuilder(snapshot.size * 80)
                for (ev in snapshot) sb.append(ev.toString()).append('\n')
                f.writeText(sb.toString())
            }
        }
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max)
}
