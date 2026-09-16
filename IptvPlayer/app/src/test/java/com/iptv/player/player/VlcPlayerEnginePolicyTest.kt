package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcPlayerEnginePolicyTest {

    @Test
    fun `buffering values near one hundred are treated as complete`() {
        assertTrue(isVlcBuffering(99.49f))
        assertFalse(isVlcBuffering(99.5f))
        assertFalse(isVlcBuffering(99.9f))
        assertFalse(isVlcBuffering(100f))
    }

    @Test
    fun `invalid buffering progress stays in buffering state`() {
        assertTrue(isVlcBuffering(Float.NaN))
        assertTrue(isVlcBuffering(Float.NEGATIVE_INFINITY))
        assertTrue(isVlcBuffering(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `software displayed pictures bypass unreliable PixelCopy validation`() {
        assertTrue(
            shouldTrustVlcNativeFrame(
                forceSoftware = true,
                hasDisplayedPictureEvidence = true,
            ),
        )
        assertFalse(
            shouldTrustVlcNativeFrame(
                forceSoftware = false,
                hasDisplayedPictureEvidence = true,
            ),
        )
        assertFalse(
            shouldTrustVlcNativeFrame(
                forceSoftware = true,
                hasDisplayedPictureEvidence = false,
            ),
        )
    }

    @Test
    fun `verified surface and advancing pictures prove buffering resumed`() {
        assertTrue(
            shouldSignalVlcFrameProgressResumed(
                surfaceWasVerified = true,
                bufferingActive = true,
                displayedFrameAdvanced = true,
            ),
        )
        assertFalse(
            shouldSignalVlcFrameProgressResumed(
                surfaceWasVerified = false,
                bufferingActive = true,
                displayedFrameAdvanced = true,
            ),
        )
        assertFalse(
            shouldSignalVlcFrameProgressResumed(
                surfaceWasVerified = true,
                bufferingActive = false,
                displayedFrameAdvanced = true,
            ),
        )
    }

    @Test
    fun `dead audio counters fall back to the playback clock after bounded checks`() {
        val evidence = VlcAudioOutputEvidence(deadAfterChecks = 3)
        fun poll(timeAdvanced: Boolean = true) = evidence.evaluate(
            hasAudio = true,
            audioSelected = true,
            playedAbuffers = 0,
            playedAdvanced = false,
            timeAdvanced = timeAdvanced,
        )
        assertFalse(poll())
        assertFalse(poll())
        assertFalse(evidence.counterDead)
        assertTrue(poll())
        assertTrue(evidence.counterDead)
        // The clock is now the audio evidence: a stalled clock is still a stall.
        assertFalse(poll(timeAdvanced = false))
        assertTrue(poll())
    }

    @Test
    fun `stalled clock never counts toward dead audio counters`() {
        val evidence = VlcAudioOutputEvidence(deadAfterChecks = 2)
        repeat(5) {
            assertFalse(
                evidence.evaluate(
                    hasAudio = true,
                    audioSelected = true,
                    playedAbuffers = 0,
                    playedAdvanced = false,
                    timeAdvanced = false,
                ),
            )
        }
        assertFalse(evidence.counterDead)
    }

    @Test
    fun `live audio counters stay authoritative`() {
        val evidence = VlcAudioOutputEvidence(deadAfterChecks = 2)
        assertTrue(
            evidence.evaluate(
                hasAudio = true,
                audioSelected = true,
                playedAbuffers = 10,
                playedAdvanced = true,
                timeAdvanced = true,
            ),
        )
        assertTrue(evidence.counterUsable)
        // Counter stops while the clock keeps moving: a real audio stall, and
        // no amount of polling may reclassify a proven counter as dead.
        repeat(4) {
            assertFalse(
                evidence.evaluate(
                    hasAudio = true,
                    audioSelected = true,
                    playedAbuffers = 10,
                    playedAdvanced = false,
                    timeAdvanced = true,
                ),
            )
        }
        assertFalse(evidence.counterDead)
    }

    @Test
    fun `audio evidence ignores streams without audio and rejects unselected audio`() {
        val evidence = VlcAudioOutputEvidence()
        assertTrue(
            evidence.evaluate(
                hasAudio = false,
                audioSelected = false,
                playedAbuffers = 0,
                playedAdvanced = false,
                timeAdvanced = false,
            ),
        )
        assertFalse(
            evidence.evaluate(
                hasAudio = true,
                audioSelected = false,
                playedAbuffers = 0,
                playedAdvanced = false,
                timeAdvanced = true,
            ),
        )
        evidence.reset()
        assertFalse(evidence.counterDead)
        assertFalse(evidence.counterUsable)
    }
}
