package com.iptv.player.player

/**
 * Bounded retry budget for SurfaceView PixelCopy failures.
 *
 * Some protected/vendor surfaces never support PixelCopy. Retrying those every
 * few hundred milliseconds for the entire playback session wastes compositor
 * and main-thread work without ever producing a sample. The monitor gets a
 * short exponential discovery window, then disables sampling for this stream.
 * A new stream/output monitor starts with a fresh policy instance/reset.
 */
internal class SurfaceSampleRetryPolicy(
    private val initialDelayMs: Long = 350L,
    private val maxDelayMs: Long = 2_800L,
    private val maxFailures: Int = 5,
) {
    private var failures = 0

    /** Next delay, or null once PixelCopy is considered unavailable. */
    fun onUnavailable(): Long? {
        failures++
        if (failures >= maxFailures) return null
        val shift = (failures - 1).coerceAtMost(30)
        return (initialDelayMs * (1L shl shift)).coerceAtMost(maxDelayMs)
    }

    fun onSuccess() {
        failures = 0
    }

    fun reset() {
        failures = 0
    }
}
