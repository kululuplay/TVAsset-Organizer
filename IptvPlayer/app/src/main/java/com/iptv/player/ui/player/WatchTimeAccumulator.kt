package com.iptv.player.ui.player

/** Counts advancing playback, never the resume target or elapsed pause time. */
internal class WatchTimeAccumulator {
    private var lastTime: Long? = null
    private var lastPosition = 0L
    private var pendingMs = 0L

    fun sample(nowMs: Long, positionMs: Long, eligible: Boolean) {
        if (!eligible) {
            lastTime = null
            return
        }
        val before = lastTime
        if (before != null) {
            val elapsed = nowMs - before
            val advanced = positionMs - lastPosition
            // VLC snapshots can update more slowly than the UI timer. Preserve
            // the baseline for repeated snapshots, but cap any stall at 5s.
            if (advanced == 0L && elapsed in 0..5_000L) return
            if (elapsed in 1..5_000L && advanced in 1..(elapsed * 3 / 2 + 500)) {
                pendingMs += minOf(elapsed, advanced)
            }
        }
        lastTime = nowMs
        lastPosition = positionMs
    }

    fun drain(): Long = pendingMs.also { pendingMs = 0L }
}
