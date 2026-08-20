package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveVideoLivenessPolicyTest {

    @Test
    fun `stale frames with advancing clock identify video output stall`() {
        assertEquals(
            LiveVideoLivenessPolicy.Decision.VIDEO_STALL,
            classify(clockAdvanceMs = 1_500L, frameAgeMs = 8_000L),
        )
    }

    @Test
    fun `stalled input is not mislabeled as decoder failure`() {
        assertEquals(
            LiveVideoLivenessPolicy.Decision.WAIT,
            classify(clockAdvanceMs = 0L, frameAgeMs = 8_000L),
        )
    }

    @Test
    fun `buffering never triggers video output fallback`() {
        assertEquals(
            LiveVideoLivenessPolicy.Decision.WAIT,
            classify(clockAdvanceMs = 1_500L, frameAgeMs = 8_000L, buffering = true),
        )
    }

    private fun classify(
        clockAdvanceMs: Long,
        frameAgeMs: Long,
        buffering: Boolean = false,
    ) = LiveVideoLivenessPolicy.classify(
        evidence = LiveVideoLivenessPolicy.Evidence(
            playbackReady = true,
            inputBuffering = buffering,
            mediaClockAdvanceMs = clockAdvanceMs,
            lastFrameAgeMs = frameAgeMs,
        ),
        frameTimeoutMs = 7_000L,
        minimumClockAdvanceMs = 500L,
    )
}
