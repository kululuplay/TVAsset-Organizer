package com.iptv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VodPlaybackHealthPolicyTest {

    @Test
    fun `waits until the stall timeout`() {
        assertEquals(
            VodPlaybackHealthPolicy.Decision.WAIT,
            classify(stalledForMs = 29_999, buffering = false, decoded = true),
        )
    }

    @Test
    fun `buffer starvation is a source stall`() {
        assertEquals(
            VodPlaybackHealthPolicy.Decision.SOURCE_STALL,
            classify(stalledForMs = 30_000, buffering = true, decoded = true),
        )
    }

    @Test
    fun `no decoded video pipeline is a source or demux stall`() {
        assertEquals(
            VodPlaybackHealthPolicy.Decision.SOURCE_STALL,
            classify(stalledForMs = 30_000, buffering = false, decoded = false),
        )
    }

    @Test
    fun `decoded pipeline without a verified frame is a decoder stall`() {
        assertEquals(
            VodPlaybackHealthPolicy.Decision.DECODER_STALL,
            classify(stalledForMs = 30_000, buffering = false, decoded = true),
        )
    }

    @Test
    fun `healthy display followed by clock freeze is a source stall`() {
        assertEquals(
            VodPlaybackHealthPolicy.Decision.SOURCE_STALL,
            classify(
                stalledForMs = 30_000,
                buffering = false,
                decoded = true,
                display = VodPlaybackHealthPolicy.DisplayEvidence.HEALTHY,
            ),
        )
    }

    @Test
    fun `explicit display failure always selects decoder recovery`() {
        assertEquals(
            VodPlaybackHealthPolicy.Decision.DECODER_STALL,
            classify(
                stalledForMs = 1,
                buffering = true,
                decoded = false,
                display = VodPlaybackHealthPolicy.DisplayEvidence.FAILED,
            ),
        )
    }

    private fun classify(
        stalledForMs: Long,
        buffering: Boolean,
        decoded: Boolean,
        display: VodPlaybackHealthPolicy.DisplayEvidence =
            VodPlaybackHealthPolicy.DisplayEvidence.UNKNOWN,
    ): VodPlaybackHealthPolicy.Decision =
        VodPlaybackHealthPolicy.classify(
            VodPlaybackHealthPolicy.Evidence(
                stalledForMs = stalledForMs,
                inputBuffering = buffering,
                decodedVideoSeen = decoded,
                display = display,
            ),
            timeoutMs = 30_000,
        )
}
