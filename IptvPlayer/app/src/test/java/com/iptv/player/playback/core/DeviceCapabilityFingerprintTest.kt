package com.iptv.player.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityFingerprintTest {

    @Test
    fun `fingerprint is deterministic across source ordering and harmless casing`() {
        val first = profile(
            codecs = listOf(avcCodec(), hevcCodec()),
            audio = linkedSetOf(
                AudioCapability(AudioCodec.EAC3, maxChannelCount = 8, passthrough = true),
                AudioCapability(AudioCodec.AAC, maxChannelCount = 6, passthrough = false),
            ),
        )
        val reordered = profile(
            codecs = listOf(
                hevcCodec().copy(mimeType = " VIDEO/HEVC "),
                avcCodec().copy(implementationName = " C2.AMLOGIC.AVC.DECODER "),
            ),
            audio = linkedSetOf(
                AudioCapability(AudioCodec.AAC, maxChannelCount = 6, passthrough = false),
                AudioCapability(AudioCodec.EAC3, maxChannelCount = 8, passthrough = true),
            ),
        )

        assertEquals(first.fingerprint(), reordered.fingerprint())
    }

    @Test
    fun `material decoder capability changes produce a new fingerprint`() {
        val hardware = profile(codecs = listOf(avcCodec()), audio = emptySet())
        val software = profile(
            codecs = listOf(avcCodec().copy(implementation = CodecImplementation.SOFTWARE)),
            audio = emptySet(),
        )

        assertNotEquals(hardware.fingerprint(), software.fingerprint())
    }

    @Test
    fun `fingerprint is telemetry safe and does not expose raw device identity`() {
        val profile = profile(codecs = listOf(avcCodec()), audio = emptySet())
        val fingerprint = profile.fingerprint().value

        assertTrue(fingerprint.matches(Regex("cap-v1-[0-9a-f]{64}")))
        assertFalse(fingerprint.contains("amlogic", ignoreCase = true))
        assertFalse(fingerprint.contains("secret-build", ignoreCase = true))
    }

    private fun profile(
        codecs: List<CodecCapability>,
        audio: Set<AudioCapability>,
    ) = DeviceCapabilityProfile(
        sdkInt = 30,
        firmwareId = "vendor/device/secret-build:user/release-keys",
        lowRamDevice = false,
        memoryClassMb = 256,
        display = DisplayCapability(
            maxWidth = 3840,
            maxHeight = 2160,
            maxRefreshRateMilliHz = 60_000,
            hdrTypes = setOf(HdrType.HDR10, HdrType.HLG),
        ),
        audio = audio,
        codecs = codecs,
    )

    private fun avcCodec() = CodecCapability(
        implementationName = "c2.amlogic.avc.decoder",
        mimeType = "video/avc",
        implementation = CodecImplementation.HARDWARE,
        vendor = true,
        securePlayback = false,
        adaptivePlayback = true,
        tunneledPlayback = false,
        maxWidth = 3840,
        maxHeight = 2160,
        maxFrameRateMilliFps = 60_000,
        profileLevels = setOf(
            CodecProfileLevel(profile = 8, level = 2048),
            CodecProfileLevel(profile = 1, level = 1024),
        ),
    )

    private fun hevcCodec() = CodecCapability(
        implementationName = "c2.amlogic.hevc.decoder",
        mimeType = "video/hevc",
        implementation = CodecImplementation.HARDWARE,
        vendor = true,
        securePlayback = true,
        adaptivePlayback = true,
        tunneledPlayback = true,
        maxWidth = 3840,
        maxHeight = 2160,
        maxFrameRateMilliFps = 60_000,
        profileLevels = setOf(CodecProfileLevel(profile = 2, level = 4096)),
    )
}
