package com.iptv.player.util

/** Pure bounded delivery policy. Payload and ID stay immutable across retries/restarts. */
internal class PlaybackUploadQueue(
    private val capacity: Int = 32,
    private val maxBytes: Int = 256 * 1024,
) {
    data class Entry(val id: String, val body: String, val sampledAtMs: Long, val boundary: Boolean, val urgent: Boolean = false)
    private val items = ArrayList<Entry>()
    var failureCount: Int = 0
        private set
    var nextAttemptMs: Long = 0
        private set

    fun restore(entries: List<Entry>, failures: Int, nextAttempt: Long, now: Long) {
        entries.forEach(::enqueue)
        failureCount = failures.coerceIn(0, 6)
        nextAttemptMs = nextAttempt.coerceIn(0, now + MAX_BACKOFF_MS)
    }

    fun enqueue(entry: Entry) {
        if (entry.body.toByteArray(Charsets.UTF_8).size > 32 * 1024 || items.any { it.id == entry.id }) return
        // Periodic observations can be superseded while offline. Start/end evidence remains.
        if (!entry.boundary) items.removeAll { !it.boundary }
        items.add(entry)
        while (items.size > capacity || items.sumOf { it.body.toByteArray(Charsets.UTF_8).size } > maxBytes) {
            items.removeAt(0)
        }
    }

    fun entries(): List<Entry> = items.toList()
    fun expire(now: Long) {
        items.removeAll { it.sampledAtMs < now - 6 * 24 * 60 * 60_000L || it.sampledAtMs > now + 5 * 60_000L }
    }
    fun next(now: Long): Entry? {
        expire(now)
        if (now < nextAttemptMs) return null
        // New incidents are urgent; an offline incident backlog must not hide the current state.
        return items.firstOrNull { it.urgent && now - it.sampledAtMs in 0L..120_000L }
            ?: items.lastOrNull { !it.boundary }
            ?: items.firstOrNull { it.urgent }
            ?: items.firstOrNull()
    }
    fun reject(id: String) { items.removeAll { it.id == id } }
    fun acknowledge(expectedId: String, acknowledgedId: String): Boolean {
        if (expectedId != acknowledgedId || items.none { it.id == expectedId }) return false
        items.removeAll { it.id == expectedId }
        failureCount = 0
        nextAttemptMs = 0
        return true
    }
    fun failed(now: Long) {
        failureCount = (failureCount + 1).coerceAtMost(6)
        nextAttemptMs = now + minOf(MAX_BACKOFF_MS, 30_000L shl (failureCount - 1))
    }
    companion object { const val MAX_BACKOFF_MS = 15 * 60_000L }
}
