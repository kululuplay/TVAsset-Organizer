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

/** Parity of the extracted ladder with PlayerController.nextStage in 1.5.98. */
class LiveVideoRouteTest {

    private fun route(
        mode: PlayerMode = PlayerMode.AUTO,
        decoderMode: DecoderMode = DecoderMode.AUTO,
        current: Stage = Stage.EXO,
        reason: Failure = Failure.VIDEO,
        triedStages: Set<Stage> = setOf(current),
        amlogic: Boolean = true,
        constrained: Boolean = true,
        allowSoftwareHdFallback: Boolean = false,
        allowSoftwareHevcRescue: Boolean = false,
        width: Int = 1920,
        height: Int = 1080,
        codec: String? = "H.264",
        automaticMode: Boolean = false,
        interlacedRescue: Boolean = false,
    ) = LiveVideoRoute.next(
        mode = mode,
        decoderMode = decoderMode,
        current = current,
        reason = reason,
        triedStages = triedStages,
        bypassVlcHardware = amlogic,
        constrainedDevice = constrained,
        allowSoftwareHdFallback = allowSoftwareHdFallback,
        allowSoftwareHevcRescue = allowSoftwareHevcRescue,
        width = width,
        height = height,
        codec = codec,
        automaticMode = automaticMode,
        allowInterlacedSoftwareRescue = interlacedRescue,
    )

    @Test
    fun `automatic mode ends the ladder instead of software HD once both hardware engines failed`() {
        val resolved = route(
            amlogic = false,
            constrained = false,
            triedStages = setOf(Stage.EXO, Stage.VLC_HW),
            automaticMode = true,
        )
        assertNull(resolved.next)
        assertTrue(Stage.VLC_SW in resolved.softwareHdExcluded)
        assertEquals(
            Stage.VLC_SW,
            route(
                amlogic = false,
                constrained = false,
                triedStages = setOf(Stage.EXO, Stage.VLC_HW),
                automaticMode = true,
                width = 720,
                height = 576,
            ).next,
        )
    }

    @Test
    fun `automatic Amlogic interlaced 1080 H264 keeps the measured software rescue`() {
        assertEquals(Stage.VLC_SW, route(constrained = false, automaticMode = true, interlacedRescue = true).next)
        assertNull(route(constrained = false, automaticMode = true, interlacedRescue = false).next)
    }

    @Test
    fun `constrained Amlogic 1080 H264 has no other video stage`() {
        val resolved = route()
        assertNull(resolved.next)
        assertTrue(Stage.VLC_SW in resolved.softwareHdExcluded)
        // The controller's "too heavy for this device" notice still fires.
        assertTrue(
            SoftwareHdFallbackPolicy.shouldReportTooHeavy(resolved.next, resolved.softwareHdExcluded, Failure.VIDEO),
        )
    }

    @Test
    fun `SD stream on the same stick still reaches software`() {
        val resolved = route(width = 720, height = 576)
        assertEquals(Stage.VLC_SW, resolved.next)
        assertTrue(resolved.softwareHdExcluded.isEmpty())
    }

    @Test
    fun `remote allowSoftwareHdFallback restores the software rung`() {
        assertEquals(Stage.VLC_SW, route(allowSoftwareHdFallback = true).next)
    }

    @Test
    fun `constrained non-Amlogic device keeps VLC hardware`() {
        assertEquals(Stage.VLC_HW, route(amlogic = false).next)
    }

    @Test
    fun `standard Amlogic device routes interlaced 1080 H264 to software`() {
        val resolved = route(constrained = false)
        assertEquals(Stage.VLC_SW, resolved.next)
        assertTrue(resolved.softwareHdExcluded.isEmpty())
    }

    @Test
    fun `HEVC without a software rescue excludes VLC software`() {
        val resolved = route(constrained = false, codec = "H.265")
        assertNull(resolved.next)
        assertTrue(resolved.softwareHdExcluded.isEmpty())
        assertEquals(Stage.VLC_SW, route(constrained = false, codec = "H.265", allowSoftwareHevcRescue = true).next)
        assertTrue(LiveVideoRoute.isHevcCodec("hvc1"))
        assertFalse(LiveVideoRoute.isHevcCodec("H.264"))
        assertFalse(LiveVideoRoute.isHevcCodec(null))
    }

    @Test
    fun `explicit VLC mode on Amlogic keeps its software rescue after the Exo substitute`() {
        assertEquals(Stage.VLC_SW, route(mode = PlayerMode.VLC, constrained = false).next)
    }

    @Test
    fun `tried stages are never revisited`() {
        assertEquals(
            Stage.VLC_SW,
            route(amlogic = false, constrained = false, triedStages = setOf(Stage.EXO, Stage.VLC_HW)).next,
        )
        assertNull(
            route(
                amlogic = false,
                constrained = false,
                triedStages = setOf(Stage.EXO, Stage.VLC_HW, Stage.VLC_SW),
            ).next,
        )
    }
}
