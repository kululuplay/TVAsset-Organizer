package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegAudioRendererOrderTest {
    private val platform = listOf("MediaCodecAudioRenderer")
    private val leading = listOf("Gated(FfmpegAudioRenderer)")
    private val trailing = listOf("FfmpegAudioRenderer")

    @Test
    fun `absent extension yields no ffmpeg renderers at all`() {
        val plan = FfmpegAudioRendererOrder.plan(
            ffmpegAvailable = false,
            preferSoftwareAudio = true,
            allowPassthrough = false,
        )
        assertEquals(FfmpegAudioRendererOrder.NONE, plan)
        assertFalse(plan.hasLeading)
        assertFalse(plan.trailing)
        // Even if a caller instantiated renderers, the plan drops them.
        assertEquals(platform, FfmpegAudioRendererOrder.arrange(plan, platform, leading, trailing))
    }

    @Test
    fun `default devices keep platform decoders first and ffmpeg as software fallback`() {
        val plan = FfmpegAudioRendererOrder.plan(
            ffmpegAvailable = true,
            preferSoftwareAudio = false,
            allowPassthrough = false,
        )
        assertFalse(plan.hasLeading)
        assertTrue(plan.trailing)
        assertEquals(
            platform + trailing,
            FfmpegAudioRendererOrder.arrange(plan, platform, leading, trailing),
        )
    }

    @Test
    fun `constrained devices lead with ffmpeg gated to Dolby only`() {
        val plan = FfmpegAudioRendererOrder.plan(
            ffmpegAvailable = true,
            preferSoftwareAudio = true,
            allowPassthrough = false,
        )
        assertEquals(setOf("audio/ac3", "audio/eac3", "audio/eac3-joc"), plan.leadingMimes)
        assertTrue(plan.trailing)
        assertEquals(
            leading + platform + trailing,
            FfmpegAudioRendererOrder.arrange(plan, platform, leading, trailing),
        )
    }

    @Test
    fun `passthrough opt-in never puts software decode ahead of the bitstream path`() {
        val plan = FfmpegAudioRendererOrder.plan(
            ffmpegAvailable = true,
            preferSoftwareAudio = true,
            allowPassthrough = true,
        )
        assertFalse(plan.hasLeading)
        assertTrue(plan.trailing)
        assertEquals(
            platform + trailing,
            FfmpegAudioRendererOrder.arrange(plan, platform, leading, trailing),
        )
    }

    @Test
    fun `leading mimes exclude formats platform decoders handle well`() {
        for (mime in listOf("audio/mp4a-latm", "audio/mpeg", "audio/mpeg-l2", "audio/vnd.dts", "video/avc")) {
            assertFalse(mime, mime in FfmpegAudioRendererOrder.SOFTWARE_FIRST_MIMES)
        }
    }

    @Test
    fun `arrange preserves the internal order of every list`() {
        val plan = FfmpegAudioRendererOrder.plan(
            ffmpegAvailable = true,
            preferSoftwareAudio = true,
            allowPassthrough = false,
        )
        assertEquals(
            listOf("L1", "L2", "P1", "P2", "T1", "T2"),
            FfmpegAudioRendererOrder.arrange(
                plan,
                platform = listOf("P1", "P2"),
                leading = listOf("L1", "L2"),
                trailing = listOf("T1", "T2"),
            ),
        )
    }
}
