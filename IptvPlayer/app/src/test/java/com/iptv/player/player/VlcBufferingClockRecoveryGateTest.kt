package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcBufferingClockRecoveryGateTest {
    private val gate = VlcBufferingClockRecoveryGate()

    @Test
    fun `missing video stats permit a fresh image probe after sustained verified playback`() {
        assertFalse(sample(position = 10_000L, at = 0L))
        assertFalse(sample(position = 11_500L, at = 1_500L))
        assertTrue(sample(position = 13_000L, at = 3_000L))
    }

    @Test
    fun `audio clock cannot prove the initial video surface is healthy`() {
        repeat(5) { index ->
            assertFalse(sample(index * 1_500L, index * 1_500L, verified = false))
        }
        assertFalse(sample(position = 7_500L, at = 7_500L))
        assertFalse(sample(position = 9_000L, at = 9_000L))
        assertTrue(sample(position = 10_500L, at = 10_500L))
    }

    @Test
    fun `usable video stats keep authority even when audio clock advances`() {
        repeat(6) { index ->
            assertFalse(sample(index * 1_500L, index * 1_500L, usableStats = true))
        }
    }

    @Test
    fun `a genuinely frozen stream remains buffering`() {
        repeat(8) { index ->
            assertFalse(sample(position = 12_000L, at = index * 1_500L))
        }
    }

    @Test
    fun `one progressing interval followed by frozen playback cannot recover`() {
        assertFalse(sample(position = 10_000L, at = 0L))
        assertFalse(sample(position = 11_500L, at = 1_500L))
        assertFalse(sample(position = 11_500L, at = 3_000L))
        assertFalse(sample(position = 13_000L, at = 4_500L))
        assertTrue(sample(position = 14_500L, at = 6_000L))
    }

    @Test
    fun `a clock discontinuity is not accepted as playback resuming`() {
        assertFalse(sample(position = 10_000L, at = 0L))
        assertFalse(sample(position = 11_500L, at = 1_500L))
        assertFalse(sample(position = 80_000L, at = 3_000L))
        assertFalse(sample(position = 81_500L, at = 4_500L))
        assertTrue(sample(position = 83_000L, at = 6_000L))
    }

    @Test
    fun `unavailable or backward clock resets accumulated progress`() {
        assertFalse(sample(position = 10_000L, at = 0L))
        assertFalse(sample(position = 11_500L, at = 1_500L))
        assertFalse(sample(position = -1L, at = 3_000L))
        assertFalse(sample(position = 13_000L, at = 4_500L))
        assertFalse(sample(position = 14_500L, at = 6_000L))
        assertFalse(sample(position = 2_000L, at = 7_500L))
        assertFalse(sample(position = 3_500L, at = 9_000L))
        assertTrue(sample(position = 5_000L, at = 10_500L))
    }

    @Test
    fun `a long sampling gap cannot join unrelated clock progress`() {
        assertFalse(sample(position = 10_000L, at = 0L))
        assertFalse(sample(position = 11_500L, at = 1_500L))
        assertFalse(sample(position = 25_000L, at = 15_000L))
        assertFalse(sample(position = 26_500L, at = 16_500L))
        assertTrue(sample(position = 28_000L, at = 18_000L))
    }

    @Test
    fun `zap reset discards the previous channels evidence`() {
        assertFalse(sample(position = 10_000L, at = 0L))
        assertFalse(sample(position = 11_500L, at = 1_500L))
        gate.reset()
        assertFalse(sample(position = 13_000L, at = 3_000L))
        assertFalse(sample(position = 14_500L, at = 4_500L))
        assertTrue(sample(position = 16_000L, at = 6_000L))
    }

    @Test
    fun `normal native resume discards stale evidence before the next rebuffer`() {
        assertFalse(sample(position = 10_000L, at = 0L))
        assertFalse(sample(position = 11_500L, at = 1_500L))
        assertFalse(sample(position = 13_000L, at = 3_000L, buffering = false))
        assertFalse(sample(position = 14_500L, at = 4_500L))
        assertFalse(sample(position = 16_000L, at = 6_000L))
        assertTrue(sample(position = 17_500L, at = 7_500L))
    }

    private fun sample(
        position: Long,
        at: Long,
        verified: Boolean = true,
        usableStats: Boolean = false,
        buffering: Boolean = true,
    ): Boolean = gate.shouldProbe(
        surfaceWasVerified = verified,
        bufferingActive = buffering,
        videoStatsUsable = usableStats,
        playbackPositionMs = position,
        nowMs = at,
    )
}
