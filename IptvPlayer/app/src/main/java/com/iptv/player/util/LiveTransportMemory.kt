package com.iptv.player.util

import android.content.Context
import com.iptv.player.data.model.StreamFormat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Tiny per-channel memory of a TS/HLS route that proved stable. Keys are opaque
 * host/channel fingerprints supplied by LiveStreamUrl; no URL or credential is
 * stored. Reads are memory-only and persistence is best-effort off the hot path.
 */
object LiveTransportMemory {
    private const val FILE = "transport-memory.jsonl"
    private const val SCHEMA = 1
    private const val MAX_ENTRIES = 500
    private const val TTL_MS = 14L * 24 * 60 * 60 * 1000

    private data class Entry(val format: StreamFormat, var usedAt: Long)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    @Volatile private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "transport-memory").apply { isDaemon = true }
    }

    fun init(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, FILE)
        file = target
        io.execute {
            runCatching {
                if (!target.exists()) return@execute
                val now = System.currentTimeMillis()
                target.forEachLine { line ->
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    if (json.optInt("v") != SCHEMA) return@forEachLine
                    val key = json.optString("k")
                    val format = runCatching {
                        StreamFormat.valueOf(json.optString("f"))
                    }.getOrNull() ?: return@forEachLine
                    val usedAt = json.optLong("u")
                    if (key.isBlank() || now - usedAt > TTL_MS) return@forEachLine
                    synchronized(lock) {
                        if (key !in entries && entries.size < MAX_ENTRIES) {
                            entries[key] = Entry(format, usedAt)
                        }
                    }
                }
            }
        }
    }

    fun bestFormat(key: String?): StreamFormat? {
        val safeKey = key ?: return null
        return synchronized(lock) {
            val entry = entries[safeKey] ?: return@synchronized null
            if (System.currentTimeMillis() - entry.usedAt > TTL_MS) {
                entries.remove(safeKey)
                null
            } else {
                entry.format
            }
        }
    }

    fun markStable(key: String?, format: StreamFormat?) {
        val safeKey = key ?: return
        val safeFormat = format ?: return
        synchronized(lock) {
            entries[safeKey] = Entry(safeFormat, System.currentTimeMillis())
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.minByOrNull { it.value.usedAt }?.key ?: break
                entries.remove(oldest)
            }
        }
        persistAsync()
    }

    fun forget(key: String?, format: StreamFormat?) {
        val safeKey = key ?: return
        val removed = synchronized(lock) {
            val entry = entries[safeKey] ?: return@synchronized false
            if (format != null && entry.format != format) return@synchronized false
            entries.remove(safeKey)
            true
        }
        if (removed) persistAsync()
    }

    private fun persistAsync() {
        val target = file ?: return
        io.execute {
            runCatching {
                val snapshot = synchronized(lock) { entries.toMap() }
                if (snapshot.isEmpty()) {
                    if (target.exists()) target.delete()
                    return@execute
                }
                target.writeText(
                    buildString {
                        snapshot.forEach { (key, entry) ->
                            append(
                                JSONObject()
                                    .put("v", SCHEMA)
                                    .put("k", key)
                                    .put("f", entry.format.name)
                                    .put("u", entry.usedAt),
                            ).append('\n')
                        }
                    },
                )
            }
        }
    }
}
