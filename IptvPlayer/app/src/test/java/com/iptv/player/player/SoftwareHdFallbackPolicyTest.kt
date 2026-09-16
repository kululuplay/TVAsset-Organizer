package com.iptv.player.player

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.player.PlaybackRoutingPolicy.Failure
import com.iptv.player.player.PlaybackRoutingPolicy.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareHdFallbackPolicyTest {

    @Test
    fun `constrained device withholds software for HD or heavy codecs`() {
        assertEquals(
            setOf(Stage.VLC_SW),
            SoftwareHdFallbackPolicy.excludedStages(true, 1280, 720, "avc", false),
        )
        assertEquals(
            setOf(Stage.VLC_SW),
            SoftwareHdFallbackPolicy.excludedStages(true, 720, 576, "video/hevc", false),
        )
        assertEquals(
            setOf(Stage.VLC_SW),
            SoftwareHdFallbackPolicy.excludedStages(true, 0, 0, "VP9", false),
        )
        assertEquals(
            setOf(Stage.VLC_SW),
            SoftwareHdFallbackPolicy.excludedStages(true, 0, 0, "av01", false),
        )
    }

    @Test
    fun `SD H264 unknown streams and capable devices keep the software rung`() {
        assertTrue(SoftwareHdFallbackPolicy.excludedStages(true, 720, 576, "avc", false).isEmpty())
        assertTrue(SoftwareHdFallbackPolicy.excludedStages(true, 0, 0, null, false).isEmpty())
        // 1080i MPEG-2 is light in software and the only rung left on Amlogic compat boxes.
        assertTrue(SoftwareHdFallbackPolicy.excludedStages(true, 1920, 1080, "video/mpeg2", false).isEmpty())
        assertTrue(SoftwareHdFallbackPolicy.excludedStages(true, 1920, 1080, "mp2v", false).isEmpty())
        // Unknown codec at HD is still assumed to be H.264-class.
        assertFalse(SoftwareHdFallbackPolicy.excludedStages(true, 1920, 1080, null, false).isEmpty())
        assertTrue(SoftwareHdFallbackPolicy.excludedStages(false, 1920, 1080, "hevc", false).isEmpty())
    }

    @Test
    fun `remote override re-enables software HD on a constrained device`() {
        assertTrue(SoftwareHdFallbackPolicy.excludedStages(true, 1920, 1080, "hevc", true).isEmpty())
    }

    @Test
    fun `too heavy is reported only when the withheld rung ended a decode failure`() {
        val excluded = setOf(Stage.VLC_SW)
        assertTrue(SoftwareHdFallbackPolicy.shouldReportTooHeavy(null, excluded, Failure.VIDEO))
        assertTrue(SoftwareHdFallbackPolicy.shouldReportTooHeavy(null, excluded, Failure.DECODE))
        assertTrue(SoftwareHdFallbackPolicy.shouldReportTooHeavy(null, excluded, Failure.SOFTWARE_SLOW))
        assertFalse(SoftwareHdFallbackPolicy.shouldReportTooHeavy(null, excluded, Failure.ERROR))
        assertFalse(SoftwareHdFallbackPolicy.shouldReportTooHeavy(null, excluded, Failure.STARTUP))
        assertFalse(SoftwareHdFallbackPolicy.shouldReportTooHeavy(Stage.VLC_HW, excluded, Failure.VIDEO))
        assertFalse(SoftwareHdFallbackPolicy.shouldReportTooHeavy(null, emptySet(), Failure.VIDEO))
    }

    @Test
    fun `Amlogic compatibility device green on EXO has no software rung for 1080p`() {
        val excluded = SoftwareHdFallbackPolicy.excludedStages(true, 1920, 1080, "avc", false)
        val tried = setOf(Stage.EXO) +
            VlcHardwareDevicePolicy.unavailableStages(true, 1920, 1080) +
            excluded
        val next = PlaybackRoutingPolicy.nextStage(
            mode = PlayerMode.AUTO,
            decoderMode = DecoderMode.AUTO,
            current = Stage.EXO,
            failure = Failure.VIDEO,
            triedStages = tried,
        )
        assertNull(next)
        assertTrue(SoftwareHdFallbackPolicy.shouldReportTooHeavy(next, excluded, Failure.VIDEO))
        // The AUTO start itself is unchanged: still ExoPlayer hardware first.
        assertEquals(Stage.EXO, PlaybackRoutingPolicy.initialStage(PlayerMode.AUTO, DecoderMode.AUTO))
    }
}
