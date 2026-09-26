package com.iptv.player.player

/**
 * Decoder-side frame evidence for one live stream: a ring of the most recent
 * frame-submission times plus a running total. Fed only by Media3's
 * VideoFrameMetadataListener (playback thread), read on the main thread.
 * Unlike ExoPlayerEngine.lastVideoFrameAtMs, READY never writes it, so it
 * cannot mistake a buffering transition for a rendered picture.
 */
internal class NativeFrameCadence(private val capacity: Int = CAPACITY) {

    /** [lastAgeMs] is null before the first frame. */
    data class Snapshot(val total: Long, val lastSecond: Int, val lastAgeMs: Long?)

    private val lock = Any()
    private val stamps = LongArray(capacity)
    private var total = 0L

    fun onFrame(nowMs: Long) {
        synchronized(lock) {
            stamps[(total % capacity).toInt()] = nowMs
            total++
        }
    }

    fun snapshot(nowMs: Long): Snapshot = synchronized(lock) {
        val stored = minOf(total, capacity.toLong()).toInt()
        var lastSecond = 0
        for (index in 0 until stored) {
            val age = nowMs - stamps[index]
            if (age in 0 until WINDOW_MS) lastSecond++
        }
        val lastAgeMs = if (total > 0L) {
            (nowMs - stamps[((total - 1) % capacity).toInt()]).coerceAtLeast(0L)
        } else {
            null
        }
        Snapshot(total, lastSecond, lastAgeMs)
    }

    fun reset() {
        synchronized(lock) {
            total = 0L
            stamps.fill(0L)
        }
    }

    private companion object {
        private const val CAPACITY = 128
        private const val WINDOW_MS = 1_000L
    }
}
