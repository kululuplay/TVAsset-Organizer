package com.iptv.player.playback.core

/** User-facing choice. AUTO is deliberately the default. */
enum class PlaybackProfilePreference {
    AUTO,
    STANDARD,
    COMPATIBILITY,
}

enum class EffectivePlaybackProfile {
    STANDARD,
    COMPATIBILITY,
}

/**
 * Platform facts used to choose a playback profile. Model and manufacturer are
 * intentionally absent: two boxes sold under the same name can contain different
 * RAM and codec implementations, while unrelated boxes can share the same limits.
 */
data class DevicePlaybackSignals(
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val totalRamMb: Long?,
    val hasHardwareAvcDecoder: Boolean,
    val hasHardwareHevcDecoder: Boolean,
    val hasAc3Decoder: Boolean,
    val hasEac3Decoder: Boolean,
    /** Actual process bitness when the platform can report it. */
    val runtimeIs64Bit: Boolean? = null,
) {
    init {
        require(sdkInt >= 1) { "sdkInt must be positive" }
        require(memoryClassMb > 0) { "memoryClassMb must be positive" }
        require(totalRamMb == null || totalRamMb > 0L) { "totalRamMb must be positive" }
    }

    val is64Bit: Boolean
        get() = runtimeIs64Bit
            ?: supportedAbis.any { it.contains("64", ignoreCase = true) }
}

enum class CompatibilityReason {
    USER_SELECTED,
    ANDROID_LOW_RAM,
    SMALL_APP_HEAP,
    LIMITED_TOTAL_RAM,
    LEGACY_32_BIT_RUNTIME,
    NO_HARDWARE_AVC,
}

/**
 * Stable, Android-free policy which player code can consume without rescanning
 * MediaCodec. Limits are recommendations, not claims that every source offers an
 * adaptive rendition. A single-bitrate source must never be silently discarded.
 */
data class DevicePlaybackProfile(
    val preference: PlaybackProfilePreference,
    val effectiveProfile: EffectivePlaybackProfile,
    val reasons: Set<CompatibilityReason>,
    val allowSoftwareHevcRescue: Boolean,
    val adaptiveMaxHeight: Int?,
    val adaptiveMaxFrameRate: Int?,
) {
    val compatibilityMode: Boolean
        get() = effectiveProfile == EffectivePlaybackProfile.COMPATIBILITY
}

object DevicePlaybackProfileResolver {

    fun resolve(
        signals: DevicePlaybackSignals,
        preference: PlaybackProfilePreference = PlaybackProfilePreference.AUTO,
    ): DevicePlaybackProfile {
        if (preference == PlaybackProfilePreference.STANDARD) {
            return standard(preference)
        }

        val reasons = linkedSetOf<CompatibilityReason>()
        if (preference == PlaybackProfilePreference.COMPATIBILITY) {
            reasons += CompatibilityReason.USER_SELECTED
        } else {
            if (signals.lowRamDevice) reasons += CompatibilityReason.ANDROID_LOW_RAM
            if (signals.memoryClassMb <= SMALL_HEAP_MB) reasons += CompatibilityReason.SMALL_APP_HEAP
            if (signals.totalRamMb != null && signals.totalRamMb <= LIMITED_TOTAL_RAM_MB) {
                reasons += CompatibilityReason.LIMITED_TOTAL_RAM
            }
            if (
                signals.sdkInt <= LEGACY_SDK_MAX &&
                !signals.is64Bit &&
                (signals.totalRamMb == null || signals.totalRamMb <= LEGACY_TOTAL_RAM_MB)
            ) {
                reasons += CompatibilityReason.LEGACY_32_BIT_RUNTIME
            }
            if (!signals.hasHardwareAvcDecoder) reasons += CompatibilityReason.NO_HARDWARE_AVC
        }

        return if (reasons.isEmpty()) {
            standard(preference)
        } else {
            DevicePlaybackProfile(
                preference = preference,
                effectiveProfile = EffectivePlaybackProfile.COMPATIBILITY,
                reasons = reasons,
                // Software HEVC on a constrained TV process commonly causes heat,
                // dropped frames and OS kills. A player may still use an adaptive
                // lower rendition or another hardware-backed engine.
                allowSoftwareHevcRescue = false,
                adaptiveMaxHeight = 1_080,
                // Keep 50 fps sports available; only 60 fps is capped when an
                // adaptive alternative exists.
                adaptiveMaxFrameRate = 50,
            )
        }
    }

    private fun standard(preference: PlaybackProfilePreference) = DevicePlaybackProfile(
        preference = preference,
        effectiveProfile = EffectivePlaybackProfile.STANDARD,
        reasons = emptySet(),
        allowSoftwareHevcRescue = true,
        adaptiveMaxHeight = null,
        adaptiveMaxFrameRate = null,
    )

    private const val SMALL_HEAP_MB = 128
    private const val LIMITED_TOTAL_RAM_MB = 1_536L
    private const val LEGACY_TOTAL_RAM_MB = 2_048L
    private const val LEGACY_SDK_MAX = 27
}
