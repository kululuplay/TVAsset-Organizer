package com.iptv.player.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ExoLivePlaybackEvidenceTest {
    @Test
    fun `continuous TS loading does not hide frozen video while audio advances`() {
        val player = mock(Player::class.java)
        `when`(player.playWhenReady).thenReturn(true)
        `when`(player.playbackState).thenReturn(Player.STATE_READY)
        `when`(player.isLoading).thenReturn(true)
        assertEquals(LiveVideoLivenessPolicy.Decision.VIDEO_STALL, decision(player))
    }

    @Test
    fun `actual rebuffering and intentional pause do not blame the decoder`() {
        val player = mock(Player::class.java)
        `when`(player.playWhenReady).thenReturn(true)
        `when`(player.playbackState).thenReturn(Player.STATE_BUFFERING)
        assertEquals(LiveVideoLivenessPolicy.Decision.WAIT, decision(player))
        `when`(player.playbackState).thenReturn(Player.STATE_READY)
        `when`(player.playWhenReady).thenReturn(false)
        assertEquals(LiveVideoLivenessPolicy.Decision.WAIT, decision(player))
    }

    @Test
    fun `fresh rendered frames remain healthy during cache refill`() {
        val player = mock(Player::class.java)
        `when`(player.playWhenReady).thenReturn(true)
        `when`(player.playbackState).thenReturn(Player.STATE_READY)
        `when`(player.isLoading).thenReturn(true)
        assertEquals(LiveVideoLivenessPolicy.Decision.WAIT, decision(player, frameAgeMs = 20L))
    }

    private fun decision(player: Player, frameAgeMs: Long = 8_000L) =
        LiveVideoLivenessPolicy.classify(
            liveVideoEvidence(player, true, false, 1_500L, frameAgeMs),
            frameTimeoutMs = 7_000L,
            minimumClockAdvanceMs = 500L,
        )
}
