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

class VlcHardwareDevicePolicyTest {

    @Test
    fun `Amlogic OMX and C2 video decoders bypass VLC hardware`() {
        assertTrue(
            VlcHardwareDevicePolicy.shouldBypassVlcHardware(
                listOf("OMX.amlogic.avc.decoder.awesome2"),
            ),
        )
        assertTrue(
            VlcHardwareDevicePolicy.shouldBypassVlcHardware(
                listOf("c2.amlogic.hevc.decoder"),
            ),
        )
    }

    @Test
    fun `other vendor decoders keep VLC hardware available`() {
        assertFalse(
            VlcHardwareDevicePolicy.shouldBypassVlcHardware(
                listOf("OMX.qcom.video.decoder.avc", "c2.android.avc.decoder"),
            ),
        )
    }

    @Test
    fun `unsafe VLC hardware preference is fulfilled by EXO hardware`() {
        assertEquals(
            Stage.EXO,
            VlcHardwareDevicePolicy.compatibleInitialStage(
                preferred = Stage.VLC_HW,
                bypassVlcHardware = true,
            ),
        )
        assertEquals(
            Stage.VLC_SW,
            VlcHardwareDevicePolicy.compatibleInitialStage(
                preferred = Stage.VLC_SW,
                bypassVlcHardware = true,
            ),
        )
        assertEquals(
            Stage.VLC_HW,
            VlcHardwareDevicePolicy.compatibleInitialStage(
                preferred = Stage.VLC_HW,
                bypassVlcHardware = false,
            ),
        )
    }

    @Test
    fun `marking unsafe VLC hardware unavailable skips it in normal ladder`() {
        assertEquals(
            Stage.VLC_SW,
            PlaybackRoutingPolicy.nextStage(
                mode = PlayerMode.AUTO,
                decoderMode = DecoderMode.AUTO,
                current = Stage.EXO,
                failure = Failure.VIDEO,
                triedStages = setOf(Stage.EXO, Stage.VLC_HW),
            ),
        )
    }

    @Test
    fun `explicit VLC retains one software rescue after substituted EXO failure`() {
        assertEquals(
            Stage.VLC_SW,
            VlcHardwareDevicePolicy.fallbackAfterHardwareSubstitution(
                mode = PlayerMode.VLC,
                current = Stage.EXO,
                failure = Failure.VIDEO,
                triedStages = setOf(Stage.EXO, Stage.VLC_HW),
                bypassVlcHardware = true,
            ),
        )
        assertNull(
            VlcHardwareDevicePolicy.fallbackAfterHardwareSubstitution(
                mode = PlayerMode.VLC,
                current = Stage.EXO,
                failure = Failure.ERROR,
                triedStages = setOf(Stage.EXO, Stage.VLC_HW),
                bypassVlcHardware = true,
            ),
        )
    }
}
