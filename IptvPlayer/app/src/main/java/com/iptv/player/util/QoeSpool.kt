/*
 * QoeSpool.kt
 * Bounded, disk-backed spool of finished playback QoE session summaries (the
 * closed-schema JSON PlaybackQoeRecord.toSafeFields() produces). Sessions are
 * appended when they end and DRAINED by the heartbeat (<= 20 per beat under
 * "qoe"), which removes them only after the server answered 2xx, so an offline
 * stretch or a process death mid-upload never loses a summary.
 *
 * Bounds: at most MAX_ENTRIES summaries / MAX_BYTES on disk, the oldest dropped
 * first; a single summary above MAX_ENTRY_BYTES is refused outright (the server
 * would reject it too). Entries are keyed by their session_id so a retry of the
 * same session never duplicates.
 *
 * Deliberately org.json-free: the summary is stored as the JSON line the
 * runtime already serialised, and the id is lifted with a regex, so the bounds
 * and ack semantics are testable on the plain JVM (android.jar's org.json is a
 * stub under unit tests). The heartbeat re-parses lines when it builds the beat.
 *
 * Privacy: the summaries carry no title, URL or account string by construction
 * (see PlaybackQoeRecord); this spool adds nothing of its own.
 */
package com.iptv.player.util

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class QoeSpool(
    private val file: File?,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Int = MAX_BYTES,
    private val io: Executor = defaultExecutor(),
) {

    data class Entry(val id: String, val json: String)

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var bytes = 0
    private var dropped = 0

    /** Load summaries spooled by a previous run. Call once, early, on startup. */
    fun init() {
        val f = file ?: return
        io.execute {
            runCatching {
                if (!f.exists()) return@execute
                load(f.readText())
            }
        }
    }

    /**
     * Load the JSON lines in [text] (bounded) ahead of anything already in
     * memory. [init] runs this asynchronously, so a summary appended before the
     * startup load completed must survive it rather than be wiped.
     */
    fun load(text: String) {
        synchronized(lock) {
            val appendedMeanwhile = ArrayList(entries)
            entries.clear()
            bytes = 0
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val id = extractId(trimmed) ?: return@forEach
                if (trimmed.length > MAX_ENTRY_BYTES) return@forEach
                if (entries.any { it.id == id }) return@forEach
                entries.add(Entry(id, trimmed))
                bytes += trimmed.length + 1
            }
            appendedMeanwhile.forEach { e ->
                if (entries.any { it.id == e.id }) return@forEach
                entries.add(e)
                bytes += e.json.length + 1
            }
            trimLocked(countDrops = false)
        }
    }

    /**
     * Append one finished session summary (a single-line JSON object carrying a
     * `session_id`). Returns false when refused (oversized, malformed, or an id
     * already spooled). Never throws; disk I/O happens off the caller's thread.
     */
    fun append(json: String): Boolean {
        val line = json.trim()
        if (line.isEmpty() || line.length > MAX_ENTRY_BYTES || '\n' in line) return false
        val id = extractId(line) ?: return false
        val accepted = synchronized(lock) {
            if (entries.any { it.id == id }) return@synchronized false
            entries.add(Entry(id, line))
            bytes += line.length + 1
            trimLocked(countDrops = true)
            true
        }
        if (accepted) persistAsync()
        return accepted
    }

    /** Oldest [max] entries WITHOUT removing them; ack with [confirmUploaded]. */
    fun snapshot(max: Int): List<Entry> = synchronized(lock) {
        if (max <= 0 || entries.isEmpty()) emptyList() else entries.take(max)
    }

    /** Remove entries the server accepted (a 2xx for the beat that carried them). */
    fun confirmUploaded(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        val changed = synchronized(lock) {
            val before = entries.size
            val it = entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.id in set) {
                    it.remove()
                    bytes -= e.json.length + 1
                }
            }
            entries.size != before
        }
        if (changed) persistAsync()
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun byteSize(): Int = synchronized(lock) { bytes }

    /** Summaries dropped to overflow since the last [confirmDropped]. */
    fun droppedCount(): Int = synchronized(lock) { dropped }

    fun confirmDropped(count: Int) {
        if (count <= 0) return
        synchronized(lock) { dropped = (dropped - count).coerceAtLeast(0) }
    }

    /** The JSON-lines text persisted to disk (oldest first). */
    fun serialize(): String = synchronized(lock) {
        if (entries.isEmpty()) return@synchronized ""
        val sb = StringBuilder(bytes)
        for (e in entries) sb.append(e.json).append('\n')
        sb.toString()
    }

    private fun trimLocked(countDrops: Boolean) {
        while (entries.isNotEmpty() && (entries.size > maxEntries || bytes > maxBytes)) {
            val victim = entries.removeAt(0)
            bytes -= victim.json.length + 1
            if (countDrops) dropped += 1
        }
        if (bytes < 0) bytes = 0
    }

    private fun persistAsync() {
        val f = file ?: return
        io.execute {
            runCatching {
                val text = serialize()
                if (text.isEmpty()) {
                    if (f.exists()) f.delete()
                } else {
                    // Temp + rename so a process death mid-write cannot leave a
                    // truncated spool behind (rename is atomic on Android).
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(f)) {
                        f.writeText(text)
                        tmp.delete()
                    }
                }
            }
        }
    }

    companion object {
        const val FILE_NAME = "qoe-spool.jsonl"
        const val MAX_ENTRIES = 200
        const val MAX_BYTES = 256 * 1024
        /** Server-side per-summary cap; the closed schema stays far below it. */
        const val MAX_ENTRY_BYTES = 4 * 1024

        private val ID_PATTERN = Regex(
            "\"session_id\"\\s*:\\s*\"([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\"",
        )

        internal fun extractId(json: String): String? =
            ID_PATTERN.find(json)?.groupValues?.get(1)

        private fun defaultExecutor(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "qoe-spool").apply { isDaemon = true }
        }
    }
}
