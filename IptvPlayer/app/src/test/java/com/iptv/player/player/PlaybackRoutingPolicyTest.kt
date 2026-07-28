package com.iptv.player.player

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.player.PlaybackRoutingPolicy.Failure
import com.iptv.player.player.PlaybackRoutingPolicy.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRoutingPolicyTest {

    @Test
    fun `automatic playback starts Exo hardware`() {
        assertEquals(
            Stage.EXO,
            PlaybackRoutingPolicy.initialStage(PlayerMode.AUTO, DecoderMode.AUTO),
        )
        assertEquals(
            Stage.EXO,
            PlaybackRoutingPolicy.initialStage(PlayerMode.AUTO, DecoderMode.HARDWARE),
        )
    }

    @Test
    fun `automatic software preference starts VLC software`() {
        assertEquals(
            Stage.VLC_SW,
            PlaybackRoutingPolicy.initialStage(PlayerMode.AUTO, DecoderMode.SOFTWARE),
        )
    }

    @Test
    fun `explicit engine choices stay on their selected engine`() {
        assertEquals(
            Stage.EXO,
            PlaybackRoutingPolicy.initialStage(PlayerMode.EXOPLAYER, DecoderMode.AUTO),
        )
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.initialStage(PlayerMode.VLC, DecoderMode.HARDWARE),
        )
        assertNull(
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.EXOPLAYER,
                DecoderMode.AUTO,
                Stage.EXO,
                Failure.DECODE,
            ),
        )
        assertNull(
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.VLC,
                DecoderMode.HARDWARE,
                Stage.VLC_HW,
                Failure.VIDEO,
            ),
        )
    }

    @Test
    fun `automatic decoder walks Exo then VLC hardware then VLC software`() {
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Stage.EXO,
                Failure.AUDIO,
            ),
        )
        assertEquals(
            Stage.VLC_SW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Stage.VLC_HW,
                Failure.VIDEO,
            ),
        )
        assertNull(
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Stage.VLC_SW,
                Failure.ERROR,
            ),
        )
    }

    @Test
    fun `hardware decoder mode never falls back to software`() {
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.HARDWARE,
                Stage.EXO,
                Failure.DECODE,
            ),
        )
        assertNull(
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.HARDWARE,
                Stage.VLC_HW,
                Failure.VIDEO,
            ),
        )
    }

    @Test
    fun `VLC automatic decoder can fall back without changing engine`() {
        assertEquals(
            Stage.VLC_SW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.VLC,
                DecoderMode.AUTO,
                Stage.VLC_HW,
                Failure.DECODE,
            ),
        )
    }

    @Test
    fun `UHD software escalation respects explicit software mode`() {
        assertNull(
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.SOFTWARE,
                Stage.VLC_SW,
                Failure.SOFTWARE_SLOW,
            ),
        )
        assertEquals(
            Stage.EXO,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Stage.VLC_SW,
                Failure.SOFTWARE_SLOW,
            ),
        )
    }
}
