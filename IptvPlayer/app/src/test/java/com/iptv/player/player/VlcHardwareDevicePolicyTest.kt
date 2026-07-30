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
    fun `QHD and UHD profiles require hardware decode`() {
        assertTrue(VlcHardwareDevicePolicy.requiresHardwareDecode(3840, 2160))
        assertTrue(VlcHardwareDevicePolicy.requiresHardwareDecode(2560, 1080))
        assertTrue(VlcHardwareDevicePolicy.requiresHardwareDecode(1920, 1440))
        assertFalse(VlcHardwareDevicePolicy.requiresHardwareDecode(1920, 1080))
    }

    @Test
    fun `Amlogic UHD excludes both unsafe VLC paths`() {
        val unavailable = VlcHardwareDevicePolicy.unavailableStages(
            bypassVlcHardware = true,
            width = 3840,
            height = 2160,
        )
        assertEquals(
            setOf(Stage.VLC_HW, Stage.VLC_SW),
            unavailable,
        )
        assertNull(
            PlaybackRoutingPolicy.nextStage(
                mode = PlayerMode.AUTO,
                decoderMode = DecoderMode.AUTO,
                current = Stage.EXO,
                failure = Failure.VIDEO,
                triedStages = setOf(Stage.EXO) + unavailable,
            ),
        )
        assertEquals(
            setOf(Stage.VLC_HW),
            VlcHardwareDevicePolicy.unavailableStages(
                bypassVlcHardware = true,
                width = 1920,
                height = 1080,
            ),
        )
        assertEquals(
            setOf(Stage.VLC_SW),
            VlcHardwareDevicePolicy.unavailableStages(
                bypassVlcHardware = false,
                width = 3840,
                height = 2160,
            ),
        )
    }

    @Test
    fun `temporary HD metadata does not classify a later UHD profile as software safe`() {
        assertFalse(VlcHardwareDevicePolicy.requiresHardwareDecode(1280, 720))
        assertTrue(VlcHardwareDevicePolicy.requiresHardwareDecode(3840, 2160))
    }

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
    fun `Amlogic EXO video failure skips unsafe VLC hardware and selects software`() {
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
    fun `Amlogic EXO stream changes require a fresh engine`() {
        assertFalse(
            VlcHardwareDevicePolicy.canReuseEngineForStreamChange(
                stage = Stage.EXO,
                bypassVlcHardware = true,
            ),
        )
        assertTrue(
            VlcHardwareDevicePolicy.canReuseEngineForStreamChange(
                stage = Stage.VLC_SW,
                bypassVlcHardware = true,
            ),
        )
        assertTrue(
            VlcHardwareDevicePolicy.canReuseEngineForStreamChange(
                stage = Stage.EXO,
                bypassVlcHardware = false,
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

    @Test
    fun `software overload gets one bounded hardware revisit`() {
        assertEquals(
            Stage.EXO,
            VlcHardwareDevicePolicy.lastChanceAfterSoftwareOverload(
                current = Stage.VLC_SW,
                failure = Failure.SOFTWARE_SLOW,
                alreadyUsed = false,
                bypassVlcHardware = true,
            ),
        )
        assertEquals(
            Stage.VLC_HW,
            VlcHardwareDevicePolicy.lastChanceAfterSoftwareOverload(
                current = Stage.VLC_SW,
                failure = Failure.SOFTWARE_SLOW,
                alreadyUsed = false,
                bypassVlcHardware = false,
            ),
        )
        assertNull(
            VlcHardwareDevicePolicy.lastChanceAfterSoftwareOverload(
                current = Stage.VLC_SW,
                failure = Failure.SOFTWARE_SLOW,
                alreadyUsed = true,
                bypassVlcHardware = true,
            ),
        )
    }
}
