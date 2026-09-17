package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateEstimatorTest {

    private fun feed(estimator: FrameRateEstimator, intervalUs: Long, frames: Int, startUs: Long = 0L) {
        var t = startUs
        repeat(frames) {
            estimator.onFrame(t)
            t += intervalUs
        }
    }

    @Test
    fun `no estimate before enough samples`() {
        val e = FrameRateEstimator()
        feed(e, 40_000L, 10)
        assertEquals(0f, e.estimate(), 0f)
    }

    @Test
    fun `25 fps cadence is measured from timestamps`() {
        val e = FrameRateEstimator()
        feed(e, 40_000L, 40)
        assertEquals(25f, e.estimate(), 0.01f)
    }

    @Test
    fun `59_94 fps cadence survives jitter through the median`() {
        val e = FrameRateEstimator()
        var t = 0L
        repeat(60) { i ->
            e.onFrame(t)
            t += 16_683L + when (i % 3) { 0 -> 300L; 1 -> -300L; else -> 0L }
        }
        assertEquals(59.94f, e.estimate(), 0.6f)
    }

    @Test
    fun `a discontinuity restarts sampling`() {
        val e = FrameRateEstimator()
        feed(e, 40_000L, 40)
        e.onFrame(1_000_000_000L) // zap/seek gap
        assertEquals(0f, e.estimate(), 0f)
        feed(e, 20_000L, 40, startUs = 1_000_020_000L)
        assertEquals(50f, e.estimate(), 0.01f)
    }
}
