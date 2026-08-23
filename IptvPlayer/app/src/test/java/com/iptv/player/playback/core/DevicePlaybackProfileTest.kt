package com.iptv.player.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePlaybackProfileTest {

    @Test
    fun `auto selects compatibility from capabilities rather than model name`() {
        val profile = DevicePlaybackProfileResolver.resolve(
            signals(
                sdkInt = 25,
                abis = listOf("armeabi-v7a"),
                totalRamMb = 1_536,
                memoryClassMb = 192,
            ),
        )

        assertTrue(profile.compatibilityMode)
        assertFalse(profile.allowSoftwareHevcRescue)
        assertEquals(1_080, profile.adaptiveMaxHeight)
        assertTrue(CompatibilityReason.LIMITED_TOTAL_RAM in profile.reasons)
        assertTrue(CompatibilityReason.LEGACY_32_BIT_RUNTIME in profile.reasons)
    }

    @Test
    fun `modern capable device remains standard in auto`() {
        val profile = DevicePlaybackProfileResolver.resolve(
            signals(
                sdkInt = 34,
                abis = listOf("arm64-v8a", "armeabi-v7a"),
                totalRamMb = 4_096,
                memoryClassMb = 256,
            ),
        )

        assertEquals(EffectivePlaybackProfile.STANDARD, profile.effectiveProfile)
        assertTrue(profile.reasons.isEmpty())
        assertTrue(profile.allowSoftwareHevcRescue)
        assertNull(profile.adaptiveMaxHeight)
    }

    @Test
    fun `Android low ram flag is sufficient even with misleading heap values`() {
        val profile = DevicePlaybackProfileResolver.resolve(
            signals(
                sdkInt = 30,
                abis = listOf("arm64-v8a"),
                totalRamMb = 3_072,
                memoryClassMb = 256,
                lowRam = true,
            ),
        )

        assertTrue(profile.compatibilityMode)
        assertEquals(setOf(CompatibilityReason.ANDROID_LOW_RAM), profile.reasons)
    }

    @Test
    fun `explicit preferences override automatic classification`() {
        val weak = signals(
            sdkInt = 25,
            abis = listOf("armeabi-v7a"),
            totalRamMb = 1_024,
            memoryClassMb = 96,
        )
        val capable = signals(
            sdkInt = 34,
            abis = listOf("arm64-v8a"),
            totalRamMb = 4_096,
            memoryClassMb = 256,
        )

        assertEquals(
            EffectivePlaybackProfile.STANDARD,
            DevicePlaybackProfileResolver.resolve(
                weak,
                PlaybackProfilePreference.STANDARD,
            ).effectiveProfile,
        )
        val forcedCompatibility = DevicePlaybackProfileResolver.resolve(
            capable,
            PlaybackProfilePreference.COMPATIBILITY,
        )
        assertTrue(forcedCompatibility.compatibilityMode)
        assertEquals(setOf(CompatibilityReason.USER_SELECTED), forcedCompatibility.reasons)
    }

    @Test
    fun `missing hardware AVC decoder chooses safe compatibility path`() {
        val profile = DevicePlaybackProfileResolver.resolve(
            signals(
                sdkInt = 33,
                abis = listOf("arm64-v8a"),
                totalRamMb = 4_096,
                memoryClassMb = 256,
                hardwareAvc = false,
            ),
        )

        assertTrue(profile.compatibilityMode)
        assertEquals(setOf(CompatibilityReason.NO_HARDWARE_AVC), profile.reasons)
    }

    private fun signals(
        sdkInt: Int,
        abis: List<String>,
        totalRamMb: Long,
        memoryClassMb: Int,
        lowRam: Boolean = false,
        hardwareAvc: Boolean = true,
    ) = DevicePlaybackSignals(
        sdkInt = sdkInt,
        supportedAbis = abis,
        lowRamDevice = lowRam,
        memoryClassMb = memoryClassMb,
        totalRamMb = totalRamMb,
        hasHardwareAvcDecoder = hardwareAvc,
        hasHardwareHevcDecoder = true,
        hasAc3Decoder = true,
        hasEac3Decoder = true,
    )
}
