package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAudioDecoderPolicyTest {
    private data class Decoder(val name: String, val software: Boolean)
    private val hardware = Decoder("vendor", false)
    private val software = Decoder("platform", true)
    private val candidates = listOf(hardware, software)

    @Test
    fun `constrained PCM AC3 and MP2 prefer available software audio only`() {
        for (mime in listOf("audio/ac3", "audio/mpeg-l1", "audio/mpeg-l2")) {
            assertEquals(listOf(software, hardware), order(mime))
        }
    }

    @Test
    fun `video AAC EAC3 and other working routes never get reordered`() {
        for (mime in listOf("video/avc", "video/hevc", "audio/mp4a-latm", "audio/eac3", "audio/eac3-joc", "audio/mpeg")) {
            assertEquals(candidates, order(mime))
        }
    }

    @Test
    fun `passthrough secure tunneling and non constrained routes retain default selection`() {
        assertEquals(candidates, order("audio/ac3", passthrough = true))
        assertEquals(candidates, order("audio/ac3", secure = true))
        assertEquals(candidates, order("audio/ac3", tunneling = true))
        assertEquals(candidates, order("audio/ac3", constrained = false))
    }

    @Test
    fun `absent platform software decoder is never fabricated`() {
        assertEquals(listOf(hardware), order("audio/ac3", available = listOf(hardware)))
        assertEquals(emptyList<Decoder>(), order("audio/ac3", available = emptyList()))
    }

    @Test
    fun `software and hardware candidate relative priority remains stable`() {
        val secondSoftware = Decoder("platform2", true)
        val secondHardware = Decoder("vendor2", false)
        assertEquals(
            listOf(software, secondSoftware, hardware, secondHardware),
            order("audio/ac3", available = listOf(hardware, software, secondHardware, secondSoftware)),
        )
    }

    private fun order(
        mime: String,
        constrained: Boolean = true,
        passthrough: Boolean = false,
        secure: Boolean = false,
        tunneling: Boolean = false,
        available: List<Decoder> = candidates,
    ) = LiveAudioDecoderPolicy.order(
        mimeType = mime,
        preferSoftwareAudio = constrained,
        allowPassthrough = passthrough,
        requiresSecureDecoder = secure,
        requiresTunnelingDecoder = tunneling,
        candidates = available,
        isSoftware = { it.software },
    )
}
