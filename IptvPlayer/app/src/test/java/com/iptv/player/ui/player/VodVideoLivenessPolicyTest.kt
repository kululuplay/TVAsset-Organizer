package com.iptv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VodVideoLivenessPolicyTest {

    @Test
    fun `audio clock advancing without rendered video reaches bounded recovery`() {
        assertEquals(
            VodVideoLivenessPolicy.Decision.VIDEO_STALL,
            classify(
                clockAdvanceMs = 12_000L,
                frameAgeMs = 12_000L,
                graceMs = 12_000L,
            ),
        )
    }

    @Test
    fun `recent renderer heartbeat keeps a static-looking scene healthy`() {
        assertEquals(
            VodVideoLivenessPolicy.Decision.WAIT,
            classify(
                clockAdvanceMs = 60_000L,
                frameAgeMs = 250L,
                graceMs = 60_000L,
            ),
        )
    }

    @Test
    fun `buffering cannot be misclassified as decoder freeze`() {
        assertEquals(
            VodVideoLivenessPolicy.Decision.WAIT,
            classify(
                clockAdvanceMs = 12_000L,
                frameAgeMs = 30_000L,
                graceMs = 30_000L,
                buffering = true,
            ),
        )
    }

    @Test
    fun `pause cannot be misclassified as decoder freeze`() {
        assertEquals(
            VodVideoLivenessPolicy.Decision.WAIT,
            classify(
                clockAdvanceMs = 12_000L,
                frameAgeMs = 30_000L,
                graceMs = 30_000L,
                active = false,
            ),
        )
    }

    @Test
    fun `seek or rebuffer grace must expire before recovery`() {
        assertEquals(
            VodVideoLivenessPolicy.Decision.WAIT,
            classify(
                clockAdvanceMs = 12_000L,
                frameAgeMs = 30_000L,
                graceMs = 11_999L,
            ),
        )
    }

    @Test
    fun `unknown heartbeat and unverified video never fail`() {
        assertEquals(
            VodVideoLivenessPolicy.Decision.WAIT,
            classify(
                clockAdvanceMs = 30_000L,
                frameAgeMs = null,
                graceMs = 30_000L,
            ),
        )
        assertEquals(
            VodVideoLivenessPolicy.Decision.WAIT,
            classify(
                clockAdvanceMs = 30_000L,
                frameAgeMs = 30_000L,
                graceMs = 30_000L,
                verified = false,
            ),
        )
    }

    private fun classify(
        clockAdvanceMs: Long,
        frameAgeMs: Long?,
        graceMs: Long,
        active: Boolean = true,
        buffering: Boolean = false,
        verified: Boolean = true,
    ): VodVideoLivenessPolicy.Decision =
        VodVideoLivenessPolicy.classify(
            evidence = VodVideoLivenessPolicy.Evidence(
                playbackActive = active,
                inputBuffering = buffering,
                verifiedVideo = verified,
                mediaClockAdvanceMs = clockAdvanceMs,
                lastFrameAgeMs = frameAgeMs,
                graceElapsedMs = graceMs,
            ),
            frameTimeoutMs = 12_000L,
            minimumClockAdvanceMs = 4_000L,
        )
}
