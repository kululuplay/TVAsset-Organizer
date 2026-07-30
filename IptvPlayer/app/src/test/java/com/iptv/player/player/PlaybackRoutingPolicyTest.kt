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
    fun `explicit engines stay selected for ordinary source errors`() {
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
                Failure.ERROR,
            ),
        )
        assertNull(
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.VLC,
                DecoderMode.HARDWARE,
                Stage.VLC_HW,
                Failure.ERROR,
            ),
        )
    }

    @Test
    fun `automatic audio failure keeps hardware video and uses VLC audio support`() {
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Stage.EXO,
                Failure.AUDIO,
            ),
        )
    }

    @Test
    fun `automatic invalid Exo video tries safe VLC hardware before software`() {
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Stage.EXO,
                Failure.VIDEO,
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
    fun `hardware preference uses safe VLC hardware for decoder init failure`() {
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.HARDWARE,
                Stage.EXO,
                Failure.DECODE,
            ),
        )
        assertEquals(
            Stage.VLC_SW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.HARDWARE,
                Stage.VLC_HW,
                Failure.VIDEO,
            ),
        )
        assertEquals(
            Stage.VLC_SW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.EXOPLAYER,
                DecoderMode.HARDWARE,
                Stage.VLC_HW,
                Failure.VIDEO,
            ),
        )
    }

    @Test
    fun `VLC confirmed decode failure can recover even with hardware preference`() {
        assertEquals(
            Stage.VLC_SW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.VLC,
                DecoderMode.HARDWARE,
                Stage.VLC_HW,
                Failure.DECODE,
            ),
        )
    }

    @Test
    fun `explicit Exo uses bounded compatibility fallback for media failures`() {
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.EXOPLAYER,
                DecoderMode.HARDWARE,
                Stage.EXO,
                Failure.AUDIO,
            ),
        )
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.EXOPLAYER,
                DecoderMode.HARDWARE,
                Stage.EXO,
                Failure.VIDEO,
            ),
        )
    }

    @Test
    fun `confirmed software overload can use bounded hardware recovery`() {
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.SOFTWARE,
                Stage.VLC_SW,
                Failure.SOFTWARE_SLOW,
                triedStages = setOf(Stage.VLC_SW),
            ),
        )
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Stage.VLC_SW,
                Failure.SOFTWARE_SLOW,
                triedStages = setOf(Stage.EXO, Stage.VLC_SW),
            ),
        )
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.VLC,
                DecoderMode.SOFTWARE,
                Stage.VLC_SW,
                Failure.SOFTWARE_SLOW,
                triedStages = setOf(Stage.VLC_SW),
            ),
        )
    }

    @Test
    fun `explicit VLC keeps its decoder for an ordinary source error`() {
        assertNull(
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.VLC,
                DecoderMode.AUTO,
                Stage.VLC_HW,
                Failure.ERROR,
                triedStages = setOf(Stage.VLC_HW),
            ),
        )
    }

    @Test
    fun `startup timeout escapes explicit Exo hardware`() {
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.EXOPLAYER,
                DecoderMode.HARDWARE,
                Stage.EXO,
                Failure.STARTUP,
                triedStages = setOf(Stage.EXO),
            ),
        )
    }

    @Test
    fun `compound green then software slow recovery tries every stage once`() {
        val afterGreen = PlaybackRoutingPolicy.nextStage(
            PlayerMode.AUTO,
            DecoderMode.AUTO,
            Stage.EXO,
            Failure.VIDEO,
            triedStages = setOf(Stage.EXO),
        )
        assertEquals(Stage.VLC_HW, afterGreen)

        val afterVlcGreen = PlaybackRoutingPolicy.nextStage(
            PlayerMode.AUTO,
            DecoderMode.AUTO,
            Stage.VLC_HW,
            Failure.VIDEO,
            triedStages = setOf(Stage.EXO, Stage.VLC_HW),
        )
        assertEquals(Stage.VLC_SW, afterVlcGreen)

        assertNull(
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Stage.VLC_SW,
                Failure.SOFTWARE_SLOW,
                triedStages = setOf(Stage.EXO, Stage.VLC_SW, Stage.VLC_HW),
            ),
        )
    }

    @Test
    fun `explicit VLC software recovery uses Exo only after both VLC paths fail`() {
        assertEquals(
            Stage.VLC_HW,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.VLC,
                DecoderMode.SOFTWARE,
                Stage.VLC_SW,
                Failure.SOFTWARE_SLOW,
                triedStages = setOf(Stage.VLC_SW),
            ),
        )
        assertEquals(
            Stage.EXO,
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.VLC,
                DecoderMode.SOFTWARE,
                Stage.VLC_HW,
                Failure.VIDEO,
                triedStages = setOf(Stage.VLC_SW, Stage.VLC_HW),
            ),
        )
        assertNull(
            PlaybackRoutingPolicy.nextStage(
                PlayerMode.VLC,
                DecoderMode.SOFTWARE,
                Stage.EXO,
                Failure.VIDEO,
                triedStages = setOf(Stage.VLC_SW, Stage.VLC_HW, Stage.EXO),
            ),
        )
    }
}
