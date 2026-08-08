package com.iptv.player.playback.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Privacy-safe, stable identifier for one device/firmware codec capability set. */
@JvmInline
value class CapabilityFingerprint(val value: String) {
    init {
        require(FORMAT.matches(value)) { "Invalid capability fingerprint" }
    }

    companion object {
        private val FORMAT = Regex("cap-v[0-9]+-[0-9a-f]{64}")
    }
}

/**
 * Android-free snapshot populated later by a platform collector. Raw device and
 * codec identifiers never leave this model; telemetry uses only [fingerprint].
 */
data class DeviceCapabilityProfile(
    val sdkInt: Int,
    val firmwareId: String,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val display: DisplayCapability,
    val audio: Set<AudioCapability>,
    val codecs: List<CodecCapability>,
) {
    init {
        require(sdkInt >= 1) { "sdkInt must be positive" }
        require(firmwareId.isNotBlank()) { "firmwareId must not be blank" }
        require(memoryClassMb > 0) { "memoryClassMb must be positive" }
    }

    fun fingerprint(): CapabilityFingerprint = DeviceCapabilityFingerprinter.fingerprint(this)
}

data class DisplayCapability(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxRefreshRateMilliHz: Int,
    val hdrTypes: Set<HdrType> = emptySet(),
) {
    init {
        require(maxWidth > 0 && maxHeight > 0) { "Display dimensions must be positive" }
        require(maxRefreshRateMilliHz > 0) { "Display refresh rate must be positive" }
    }
}

enum class HdrType {
    HDR10,
    HDR10_PLUS,
    HLG,
    DOLBY_VISION,
}

data class AudioCapability(
    val codec: AudioCodec,
    val maxChannelCount: Int,
    val passthrough: Boolean,
) {
    init {
        require(maxChannelCount > 0) { "Audio channel count must be positive" }
    }
}

enum class AudioCodec {
    AAC,
    MPEG_AUDIO,
    AC3,
    EAC3,
    DTS,
    TRUEHD,
    OPUS,
    FLAC,
    PCM,
    UNKNOWN,
}

data class CodecCapability(
    val implementationName: String,
    val mimeType: String,
    val implementation: CodecImplementation,
    val vendor: Boolean,
    val securePlayback: Boolean,
    val adaptivePlayback: Boolean,
    val tunneledPlayback: Boolean,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val maxFrameRateMilliFps: Int?,
    val profileLevels: Set<CodecProfileLevel> = emptySet(),
) {
    init {
        require(implementationName.isNotBlank()) { "Codec implementation name must not be blank" }
        require(mimeType.isNotBlank()) { "Codec MIME type must not be blank" }
        require(maxWidth == null || maxWidth > 0) { "maxWidth must be positive" }
        require(maxHeight == null || maxHeight > 0) { "maxHeight must be positive" }
        require(maxFrameRateMilliFps == null || maxFrameRateMilliFps > 0) {
            "maxFrameRateMilliFps must be positive"
        }
    }
}

enum class CodecImplementation {
    HARDWARE,
    SOFTWARE,
    UNKNOWN,
}

data class CodecProfileLevel(
    val profile: Int,
    val level: Int,
)

/** Deterministic SHA-256 fingerprint with ordering and locale normalized. */
object DeviceCapabilityFingerprinter {

    private const val SCHEMA_VERSION = 1

    fun fingerprint(profile: DeviceCapabilityProfile): CapabilityFingerprint {
        val canonical = canonical(profile)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
        return CapabilityFingerprint("cap-v$SCHEMA_VERSION-$hex")
    }

    private fun canonical(profile: DeviceCapabilityProfile): String = buildString {
        field("schema", SCHEMA_VERSION.toString())
        field("sdk", profile.sdkInt.toString())
        field("firmware", normalize(profile.firmwareId))
        field("low_ram", profile.lowRamDevice.toString())
        field("memory_mb", profile.memoryClassMb.toString())
        field("display_width", profile.display.maxWidth.toString())
        field("display_height", profile.display.maxHeight.toString())
        field("display_refresh_millihz", profile.display.maxRefreshRateMilliHz.toString())
        listField("hdr", profile.display.hdrTypes.map { it.name })
        listField("audio", profile.audio.map(::canonicalAudio))
        listField("codecs", profile.codecs.map(::canonicalCodec))
    }

    private fun canonicalAudio(capability: AudioCapability): String = buildString {
        field("codec", capability.codec.name)
        field("channels", capability.maxChannelCount.toString())
        field("passthrough", capability.passthrough.toString())
    }

    private fun canonicalCodec(capability: CodecCapability): String = buildString {
        field("name", normalize(capability.implementationName))
        field("mime", normalize(capability.mimeType))
        field("implementation", capability.implementation.name)
        field("vendor", capability.vendor.toString())
        field("secure", capability.securePlayback.toString())
        field("adaptive", capability.adaptivePlayback.toString())
        field("tunneled", capability.tunneledPlayback.toString())
        field("width", capability.maxWidth?.toString().orEmpty())
        field("height", capability.maxHeight?.toString().orEmpty())
        field("fps_milli", capability.maxFrameRateMilliFps?.toString().orEmpty())
        listField(
            "profile_levels",
            capability.profileLevels.map { "${it.profile}:${it.level}" },
        )
    }

    private fun StringBuilder.field(name: String, value: String) {
        append(name.length).append(':').append(name)
        append(value.length).append(':').append(value)
        append('|')
    }

    private fun StringBuilder.listField(name: String, values: List<String>) {
        field(name, values.sorted().joinToString(separator = "") { "${it.length}:$it" })
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    private const val HEX = "0123456789abcdef"
}
