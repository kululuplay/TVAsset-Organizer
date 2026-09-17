package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateMatchPolicyTest {

    // A typical Android TV stick: 1080p only, the usual refresh set.
    private val stick1080 = listOf(
        mode(1, 1920, 1080, 60f),
        mode(2, 1920, 1080, 59.94f),
        mode(3, 1920, 1080, 50f),
        mode(4, 1920, 1080, 30f),
        mode(5, 1920, 1080, 29.97f),
        mode(6, 1920, 1080, 25f),
        mode(7, 1920, 1080, 24f),
        mode(8, 1920, 1080, 23.976f),
    )

    // A 4K TV: 2160p and 1080p sets, plus 720p.
    private val tv4k = listOf(
        mode(10, 3840, 2160, 60f),
        mode(11, 3840, 2160, 59.94f),
        mode(12, 3840, 2160, 50f),
        mode(13, 3840, 2160, 30f),
        mode(14, 3840, 2160, 25f),
        mode(15, 3840, 2160, 24f),
        mode(16, 3840, 2160, 23.976f),
        mode(20, 1920, 1080, 60f),
        mode(21, 1920, 1080, 59.94f),
        mode(22, 1920, 1080, 50f),
        mode(23, 1920, 1080, 24f),
        mode(24, 1920, 1080, 23.976f),
        mode(30, 1280, 720, 60f),
        mode(31, 1280, 720, 50f),
    )

    @Test
    fun `normalises ntsc rates onto their integer family`() {
        assertEquals(24, FrameRateMatchPolicy.normalizeRate(23.976f))
        assertEquals(24, FrameRateMatchPolicy.normalizeRate(24f))
        assertEquals(25, FrameRateMatchPolicy.normalizeRate(25f))
        assertEquals(30, FrameRateMatchPolicy.normalizeRate(29.97f))
        assertEquals(50, FrameRateMatchPolicy.normalizeRate(50f))
        assertEquals(60, FrameRateMatchPolicy.normalizeRate(59.94f))
        assertEquals(60, FrameRateMatchPolicy.normalizeRate(60f))
        assertEquals(120, FrameRateMatchPolicy.normalizeRate(119.88f))
    }

    @Test
    fun `unknown or odd rates are not normalised`() {
        assertNull(FrameRateMatchPolicy.normalizeRate(0f))
        assertNull(FrameRateMatchPolicy.normalizeRate(-1f))
        assertNull(FrameRateMatchPolicy.normalizeRate(Float.NaN))
        assertNull(FrameRateMatchPolicy.normalizeRate(15f))
        assertNull(FrameRateMatchPolicy.normalizeRate(24.5f))
        assertNull(FrameRateMatchPolicy.normalizeRate(1000f))
    }

    @Test
    fun `refresh fits only integer multiples`() {
        assertTrue(FrameRateMatchPolicy.refreshFits(50f, 25))
        assertTrue(FrameRateMatchPolicy.refreshFits(25f, 25))
        assertTrue(FrameRateMatchPolicy.refreshFits(120f, 24))
        assertTrue(FrameRateMatchPolicy.refreshFits(59.94f, 30))
        assertFalse(FrameRateMatchPolicy.refreshFits(60f, 25))
        assertFalse(FrameRateMatchPolicy.refreshFits(50f, 24))
        assertFalse(FrameRateMatchPolicy.refreshFits(24f, 50))
    }

    @Test
    fun `25 fps on 60 Hz switches to 50 Hz not 25 Hz`() {
        assertEquals(3, select(25f, current = 1, modes = stick1080))
    }

    @Test
    fun `25 fps on a 50 Hz panel keeps the current mode`() {
        assertNull(select(25f, current = 3, modes = stick1080))
    }

    @Test
    fun `50 fps on 60 Hz switches to 50 Hz`() {
        assertEquals(3, select(50f, current = 1, modes = stick1080))
    }

    @Test
    fun `24 fps prefers native 24 Hz`() {
        assertEquals(7, select(24f, current = 1, modes = stick1080))
    }

    @Test
    fun `23_976 content picks the exact 23_976 mode when both exist`() {
        assertEquals(8, select(23.976f, current = 1, modes = stick1080))
        assertEquals(7, select(24f, current = 3, modes = stick1080))
    }

    @Test
    fun `59_94 content on 60 Hz already fits and prefers exact only when switching anyway`() {
        // 60 Hz is the same family: no switch just to pick 59.94.
        assertNull(select(59.94f, current = 1, modes = stick1080))
        // Coming from 50 Hz the exact NTSC mode wins over 60.000.
        assertEquals(2, select(59.94f, current = 3, modes = stick1080))
        assertEquals(1, select(60f, current = 3, modes = stick1080))
    }

    @Test
    fun `30 fps content prefers 60 Hz over 30 Hz`() {
        assertEquals(1, select(30f, current = 3, modes = stick1080))
        assertEquals(2, select(29.97f, current = 3, modes = stick1080))
    }

    @Test
    fun `30 fps content on 60 Hz keeps the current mode`() {
        assertNull(select(30f, current = 1, modes = stick1080))
        assertNull(select(29.97f, current = 2, modes = stick1080))
    }

    @Test
    fun `unknown fps never switches`() {
        assertNull(select(0f, current = 1, modes = stick1080))
        assertNull(select(-1f, current = 1, modes = stick1080))
    }

    @Test
    fun `off never switches`() {
        assertNull(select(25f, current = 1, modes = stick1080, afr = AfrMode.OFF))
    }

    @Test
    fun `single mode never switches`() {
        assertNull(select(25f, current = 1, modes = listOf(mode(1, 1920, 1080, 60f))))
    }

    @Test
    fun `unknown current mode never switches`() {
        assertNull(select(25f, current = 99, modes = stick1080))
    }

    @Test
    fun `no fitting refresh at the current resolution keeps the mode`() {
        val sixtyOnly = listOf(mode(1, 1920, 1080, 60f), mode(2, 1920, 1080, 59.94f))
        assertNull(select(25f, current = 1, modes = sixtyOnly))
    }

    @Test
    fun `refresh only never leaves the current resolution`() {
        // 4K TV currently on 2160p60 with 25 fps 1080p content: stays 2160p.
        assertEquals(12, select(25f, current = 10, modes = tv4k, width = 1920, height = 1080))
        // Currently on 1080p60 with UHD 50 fps content: stays 1080p.
        assertEquals(22, select(50f, current = 20, modes = tv4k, width = 3840, height = 2160))
    }

    @Test
    fun `refresh and resolution follows 2160p content`() {
        assertEquals(
            12,
            select(25f, current = 20, modes = tv4k, width = 3840, height = 2160, afr = AfrMode.REFRESH_AND_RESOLUTION),
        )
        // Refresh already fits but the class does not: still switch.
        assertEquals(
            10,
            select(60f, current = 20, modes = tv4k, width = 3840, height = 2160, afr = AfrMode.REFRESH_AND_RESOLUTION),
        )
    }

    @Test
    fun `refresh and resolution drops 2160p to 1080p for 1080p content`() {
        assertEquals(
            22,
            select(25f, current = 10, modes = tv4k, width = 1920, height = 1080, afr = AfrMode.REFRESH_AND_RESOLUTION),
        )
    }

    @Test
    fun `refresh and resolution keeps the current class for sd and 720p content`() {
        // 576i on a 2160p60 panel: refresh switch only, never below 720p.
        assertEquals(
            12,
            select(25f, current = 10, modes = tv4k, width = 720, height = 576, afr = AfrMode.REFRESH_AND_RESOLUTION),
        )
        assertEquals(
            22,
            select(50f, current = 20, modes = tv4k, width = 1280, height = 720, afr = AfrMode.REFRESH_AND_RESOLUTION),
        )
    }

    @Test
    fun `refresh and resolution without a 2160p mode set falls back to the current class`() {
        assertEquals(
            3,
            select(25f, current = 1, modes = stick1080, width = 3840, height = 2160, afr = AfrMode.REFRESH_AND_RESOLUTION),
        )
    }

    @Test
    fun `refresh and resolution already at the right class and refresh keeps the mode`() {
        assertNull(
            select(50f, current = 12, modes = tv4k, width = 3840, height = 2160, afr = AfrMode.REFRESH_AND_RESOLUTION),
        )
    }

    @Test
    fun `resolution classes`() {
        assertEquals(FrameRateMatchPolicy.ResolutionClass.SD, FrameRateMatchPolicy.resolutionClass(720, 576))
        assertEquals(FrameRateMatchPolicy.ResolutionClass.HD_720, FrameRateMatchPolicy.resolutionClass(1280, 720))
        assertEquals(FrameRateMatchPolicy.ResolutionClass.HD_1080, FrameRateMatchPolicy.resolutionClass(1920, 1080))
        assertEquals(FrameRateMatchPolicy.ResolutionClass.HD_1080, FrameRateMatchPolicy.resolutionClass(1440, 1080))
        assertEquals(FrameRateMatchPolicy.ResolutionClass.UHD_2160, FrameRateMatchPolicy.resolutionClass(3840, 2160))
        assertEquals(FrameRateMatchPolicy.ResolutionClass.UHD_2160, FrameRateMatchPolicy.resolutionClass(2560, 1440))
    }

    @Test
    fun `afr mode parses unknown names as off`() {
        assertEquals(AfrMode.OFF, AfrMode.fromName(null))
        assertEquals(AfrMode.OFF, AfrMode.fromName("bogus"))
        assertEquals(AfrMode.REFRESH_ONLY, AfrMode.fromName("REFRESH_ONLY"))
    }

    private fun select(
        fps: Float,
        current: Int,
        modes: List<DisplayModeInfo>,
        width: Int = 1920,
        height: Int = 1080,
        afr: AfrMode = AfrMode.REFRESH_ONLY,
    ): Int? = FrameRateMatchPolicy.selectMode(
        contentFps = fps,
        contentWidth = width,
        contentHeight = height,
        modes = modes,
        currentModeId = current,
        afrMode = afr,
    )

    private fun mode(id: Int, width: Int, height: Int, hz: Float) =
        DisplayModeInfo(id, width, height, hz)
}
