package com.iptv.player.player

/**
 * Estimates the content frame rate from presentation timestamps of rendered
 * frames. Media3's MPEG-TS readers build H.264/H.265 formats without a frame
 * rate, so this is the only fps source on the live Exo path. Median of the
 * recent frame intervals; a discontinuity (seek, zap, gap) restarts sampling.
 * Thread-safe: frames arrive on the playback thread, reads on main.
 */
internal class FrameRateEstimator(private val minSamples: Int = 24) {

    private val lock = Any()
    private val intervalsUs = LongArray(MAX_SAMPLES)
    private var count = 0
    private var next = 0
    private var lastPresentationUs = Long.MIN_VALUE

    fun onFrame(presentationTimeUs: Long) {
        synchronized(lock) {
            val last = lastPresentationUs
            lastPresentationUs = presentationTimeUs
            if (last == Long.MIN_VALUE) return
            val delta = presentationTimeUs - last
            if (delta < MIN_INTERVAL_US || delta > MAX_INTERVAL_US) {
                // Backwards or a gap: new stream/seek, forget the old cadence.
                count = 0
                next = 0
                return
            }
            intervalsUs[next] = delta
            next = (next + 1) % MAX_SAMPLES
            if (count < MAX_SAMPLES) count++
        }
    }

    /** Frames per second, or 0 until enough intervals were observed. */
    fun estimate(): Float {
        val sorted = synchronized(lock) {
            if (count < minSamples) return 0f
            intervalsUs.copyOf(count).also { it.sort() }
        }
        val median = sorted[sorted.size / 2]
        if (median <= 0L) return 0f
        return (1_000_000.0 / median).toFloat()
    }

    fun reset() {
        synchronized(lock) {
            count = 0
            next = 0
            lastPresentationUs = Long.MIN_VALUE
        }
    }

    private companion object {
        const val MAX_SAMPLES = 48
        const val MIN_INTERVAL_US = 5_000L   // > 200 fps is not content
        const val MAX_INTERVAL_US = 100_000L // < 10 fps means a gap, not cadence
    }
}
