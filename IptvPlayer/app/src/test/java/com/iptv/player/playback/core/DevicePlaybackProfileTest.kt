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
    fun `remote compatibility verdict decides AUTO but never an explicit user choice`() {
        assertEquals(
            PlaybackProfilePreference.COMPATIBILITY,
            DevicePlaybackProfileResolver.preferenceWithOverride(PlaybackProfilePreference.AUTO, true),
        )
        assertEquals(
            PlaybackProfilePreference.STANDARD,
            DevicePlaybackProfileResolver.preferenceWithOverride(PlaybackProfilePreference.AUTO, false),
        )
        assertEquals(
            PlaybackProfilePreference.AUTO,
            DevicePlaybackProfileResolver.preferenceWithOverride(PlaybackProfilePreference.AUTO, null),
        )
        assertEquals(
            PlaybackProfilePreference.STANDARD,
            DevicePlaybackProfileResolver.preferenceWithOverride(PlaybackProfilePreference.STANDARD, true),
        )
        assertEquals(
            PlaybackProfilePreference.COMPATIBILITY,
            DevicePlaybackProfileResolver.preferenceWithOverride(PlaybackProfilePreference.COMPATIBILITY, false),
        )
        // A remote STANDARD verdict lifts the automatic caps on a weak box.
        val weak = signals(sdkInt = 25, abis = listOf("armeabi-v7a"), totalRamMb = 1_024, memoryClassMb = 96)
        assertFalse(
            DevicePlaybackProfileResolver.resolve(
                weak,
                DevicePlaybackProfileResolver.preferenceWithOverride(PlaybackProfilePreference.AUTO, false),
            ).compatibilityMode,
        )
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

    @Test
    fun `legacy Android and Fire OS API levels with one GiB select compatibility`() {
        for (sdk in listOf(21, 22, 23, 25, 28, 30)) {
            val profile = DevicePlaybackProfileResolver.resolve(signals(
                sdkInt = sdk,
                abis = listOf("armeabi-v7a"),
                totalRamMb = 1_024,
                memoryClassMb = 96,
            ))
            assertTrue("API $sdk must use the constrained playback budget", profile.compatibilityMode)
            assertFalse(profile.allowSoftwareHevcRescue)
            assertEquals(50, profile.adaptiveMaxFrameRate)
        }
    }

    @Test
    fun `32 bit app on 64 bit capable legacy hardware uses actual runtime bitness`() {
        val device = signals(
            sdkInt = 27,
            abis = listOf("arm64-v8a", "armeabi-v7a"),
            totalRamMb = 2_048,
            memoryClassMb = 256,
        ).copy(runtimeIs64Bit = false)
        val profile = DevicePlaybackProfileResolver.resolve(device)
        assertTrue(profile.compatibilityMode)
        assertEquals(setOf(CompatibilityReason.LEGACY_32_BIT_RUNTIME), profile.reasons)
    }

    @Test
    fun `old SDK alone does not restrict capable hardware`() {
        val profile = DevicePlaybackProfileResolver.resolve(signals(
            sdkInt = 23,
            abis = listOf("arm64-v8a"),
            totalRamMb = 4_096,
            memoryClassMb = 256,
        ))
        assertFalse(profile.compatibilityMode)
        assertNull(profile.adaptiveMaxHeight)
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
