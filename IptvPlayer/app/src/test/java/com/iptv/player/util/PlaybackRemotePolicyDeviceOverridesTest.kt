package com.iptv.player.util

import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.PlayerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRemotePolicyDeviceOverridesTest {

    private val fireTvStick = DeviceOverrideMatcher.DeviceFacts(
        manufacturer = "Amazon",
        model = "AFTT",
        hardware = "mt8127",
        board = "sloane",
        socModel = null,
        sdk = 25,
        lowRam = true,
        totalRamMb = 1000,
    )

    private val shield = DeviceOverrideMatcher.DeviceFacts(
        manufacturer = "NVIDIA",
        model = "SHIELD Android TV",
        hardware = "mdarcy",
        board = "mdarcy",
        socModel = "Tegra X1",
        sdk = 33,
        lowRam = false,
        totalRamMb = 2900,
    )

    private fun raw(match: Map<String, Any> = emptyMap(), set: Map<String, Any>) =
        DeviceOverrideMatcher.RawRule(match, set)

    @Test
    fun `regex keys match case-insensitively with find semantics`() {
        val rules = DeviceOverrideMatcher.parseRules(
            listOf(raw(mapOf("model" to "aftm|aftt"), mapOf("bufferMode" to "HIGH"))),
        )
        assertEquals(
            BufferMode.HIGH,
            DeviceOverrideMatcher.resolve(rules, fireTvStick).bufferMode,
        )
        assertNull(DeviceOverrideMatcher.resolve(rules, shield).bufferMode)
    }

    @Test
    fun `all match keys are ANDed`() {
        val rule = DeviceOverrideMatcher.parseRule(
            raw(
                mapOf(
                    "manufacturer" to "^amazon$",
                    "hardware" to "mt81",
                    "board" to "sloane",
                    "sdkMin" to 21,
                    "sdkMax" to 27,
                    "lowRam" to true,
                    "totalRamMaxMb" to 1536,
                ),
                mapOf("compatibilityProfile" to true),
            ),
        )!!
        assertTrue(DeviceOverrideMatcher.matches(rule, fireTvStick))
        assertFalse(DeviceOverrideMatcher.matches(rule, fireTvStick.copy(sdk = 28)))
        assertFalse(DeviceOverrideMatcher.matches(rule, fireTvStick.copy(sdk = 20)))
        assertFalse(DeviceOverrideMatcher.matches(rule, fireTvStick.copy(lowRam = false)))
        assertFalse(DeviceOverrideMatcher.matches(rule, fireTvStick.copy(lowRam = null)))
        assertFalse(DeviceOverrideMatcher.matches(rule, fireTvStick.copy(totalRamMb = 2048)))
        assertFalse(DeviceOverrideMatcher.matches(rule, fireTvStick.copy(totalRamMb = null)))
        assertFalse(DeviceOverrideMatcher.matches(rule, fireTvStick.copy(board = "other")))
    }

    @Test
    fun `socModel rule never matches a device without a reported SoC`() {
        val rule = DeviceOverrideMatcher.parseRule(
            raw(mapOf("socModel" to "tegra"), mapOf("tunneling" to true)),
        )!!
        assertTrue(DeviceOverrideMatcher.matches(rule, shield))
        assertFalse(DeviceOverrideMatcher.matches(rule, fireTvStick))
    }

    @Test
    fun `an empty match block matches every device`() {
        val rules = DeviceOverrideMatcher.parseRules(
            listOf(raw(set = mapOf("livePreview" to false))),
        )
        assertEquals(false, DeviceOverrideMatcher.resolve(rules, shield).livePreviewEnabled)
        assertEquals(false, DeviceOverrideMatcher.resolve(rules, fireTvStick).livePreviewEnabled)
    }

    @Test
    fun `later matching rules win per field and non-matching rules are skipped`() {
        val rules = DeviceOverrideMatcher.parseRules(
            listOf(
                raw(
                    mapOf("lowRam" to true),
                    mapOf("bufferMode" to "LOW", "startEngine" to "VLC", "tunneling" to false),
                ),
                raw(mapOf("model" to "AFTT"), mapOf("bufferMode" to "high")),
                raw(mapOf("model" to "SHIELD"), mapOf("startEngine" to "EXOPLAYER")),
            ),
        )
        val merged = DeviceOverrideMatcher.resolve(rules, fireTvStick)
        assertEquals(BufferMode.HIGH, merged.bufferMode)
        assertEquals(PlayerMode.VLC, merged.startEngine)
        assertEquals(false, merged.tunneling)
        assertNull(merged.livePreviewEnabled)
        assertNull(merged.allowSoftwareHdFallback)
        assertNull(merged.compatibilityProfile)
    }

    @Test
    fun `invalid or over-long regex drops only that rule`() {
        val rules = DeviceOverrideMatcher.parseRules(
            listOf(
                raw(mapOf("model" to "AFT("), mapOf("bufferMode" to "HIGH")),
                raw(mapOf("model" to "a".repeat(129)), mapOf("bufferMode" to "HIGH")),
                raw(mapOf("model" to ""), mapOf("bufferMode" to "HIGH")),
                raw(mapOf("model" to 42), mapOf("bufferMode" to "HIGH")),
                raw(mapOf("model" to "AFTT"), mapOf("startEngine" to "EXOPLAYER")),
            ),
        )
        assertEquals(1, rules.size)
        val merged = DeviceOverrideMatcher.resolve(rules, fireTvStick)
        assertNull(merged.bufferMode)
        assertEquals(PlayerMode.EXOPLAYER, merged.startEngine)
    }

    @Test
    fun `unknown set values are ignored and a rule setting nothing is dropped`() {
        val rules = DeviceOverrideMatcher.parseRules(
            listOf(
                raw(set = mapOf("bufferMode" to "ULTRA", "startEngine" to 7, "tunneling" to "yes")),
                raw(set = mapOf("bufferMode" to "MEDIUM", "unknownKey" to true)),
                raw(set = mapOf("bufferMode" to "ULTRA", "allowSoftwareHdFallback" to false)),
            ),
        )
        assertEquals(1, rules.size)
        val merged = DeviceOverrideMatcher.resolve(rules, shield)
        assertNull(merged.bufferMode)
        assertNull(merged.startEngine)
        assertNull(merged.tunneling)
        assertEquals(false, merged.allowSoftwareHdFallback)
    }

    @Test
    fun `at most 32 rules are honoured`() {
        val rules = buildList {
            repeat(32) { add(raw(set = mapOf("bufferMode" to "LOW"))) }
            add(raw(set = mapOf("bufferMode" to "HIGH")))
        }
        val parsed = DeviceOverrideMatcher.parseRules(rules)
        assertEquals(32, parsed.size)
        assertEquals(BufferMode.LOW, DeviceOverrideMatcher.resolve(parsed, shield).bufferMode)
    }

    @Test
    fun `expired policy yields all-null overrides`() {
        // deviceOverrides() gates on the same expiry as snapshot(); with no policy
        // applied (expiresAt = 0) nothing may leak through regardless of nowMs.
        assertEquals(
            PlaybackRemotePolicy.DeviceOverrides(),
            PlaybackRemotePolicy.deviceOverrides(nowMs = 0L),
        )
        assertEquals(
            PlaybackRemotePolicy.DeviceOverrides(),
            PlaybackRemotePolicy.deviceOverrides(nowMs = Long.MAX_VALUE),
        )
        assertEquals(PlaybackRemotePolicy.Snapshot(), PlaybackRemotePolicy.snapshot(nowMs = 1L))
    }

    @Test
    fun `vlcDeinterlace is a nullable boolean override`() {
        val rules = DeviceOverrideMatcher.parseRules(
            listOf(raw(mapOf("model" to "aftt"), mapOf("vlcDeinterlace" to false))),
        )
        assertEquals(false, DeviceOverrideMatcher.resolve(rules, fireTvStick).vlcDeinterlace)
        assertNull(DeviceOverrideMatcher.resolve(rules, shield).vlcDeinterlace)
    }
}
