package com.iptv.player.playback.android

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.iptv.player.playback.core.AudioCodec
import com.iptv.player.playback.core.CapabilityFingerprint
import com.iptv.player.playback.core.CodecImplementation
import com.iptv.player.playback.core.DeviceCapabilityProfile
import com.iptv.player.playback.core.DevicePlaybackProfile
import com.iptv.player.playback.core.DevicePlaybackProfileResolver
import com.iptv.player.playback.core.DevicePlaybackSignals
import com.iptv.player.playback.core.PlaybackProfilePreference
import com.iptv.player.util.PlaybackRemotePolicy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class AndroidDevicePlaybackSnapshot(
    val profile: DevicePlaybackProfile,
    val capabilityFingerprint: CapabilityFingerprint?,
    // Retained so a remote compatibility verdict that lands after the scan (the
    // heartbeat response) can be re-applied without another MediaCodec pass.
    val signals: DevicePlaybackSignals? = null,
    val userPreference: PlaybackProfilePreference = PlaybackProfilePreference.AUTO,
)

/**
 * Firmware-keyed lightweight cache. Enumerating MediaCodec can be surprisingly
 * expensive on old vendor builds, so a successful scan is reused until firmware,
 * SDK, ABI or this cache schema changes.
 */
internal object AndroidDevicePlaybackProfileCache {

    @Volatile
    private var current: AndroidDevicePlaybackSnapshot = fallbackSignals().let { signals ->
        AndroidDevicePlaybackSnapshot(
            profile = DevicePlaybackProfileResolver.resolve(signals),
            capabilityFingerprint = null,
            signals = signals,
        )
    }

    /**
     * The cached profile with the current remote compatibility verdict applied.
     * Re-resolved only when that verdict changes; resolve() is a handful of
     * comparisons, but this getter sits on hot controller/engine paths.
     */
    fun currentProfile(): DevicePlaybackProfile {
        val snapshot = current
        val override = runCatching { PlaybackRemotePolicy.deviceOverrides().compatibilityProfile }
            .getOrNull()
        val signals = snapshot.signals ?: return snapshot.profile
        val effective = DevicePlaybackProfileResolver.preferenceWithOverride(
            snapshot.userPreference,
            override,
        )
        if (effective == snapshot.profile.preference) return snapshot.profile
        val resolved = DevicePlaybackProfileResolver.resolve(signals, effective)
        // Never let a re-resolve of the startup fallback snapshot overwrite a
        // scan result that loadOrCollect published concurrently: only replace
        // the snapshot this call actually derived from.
        synchronized(this) {
            if (current === snapshot) current = snapshot.copy(profile = resolved)
        }
        return resolved
    }

    /** loadOrCollect results are authoritative; publish them under the same lock. */
    private fun publish(snapshot: AndroidDevicePlaybackSnapshot) {
        synchronized(this) { current = snapshot }
    }

    fun loadOrCollect(
        context: Context,
        preference: PlaybackProfilePreference = PlaybackProfilePreference.AUTO,
    ): AndroidDevicePlaybackSnapshot {
        val app = context.applicationContext
        val memory = collectMemory(app)
        val abis = Build.SUPPORTED_ABIS.orEmpty().filter { it.isNotBlank() }
        val cacheKey = cacheKey(abis)
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val cached = if (prefs.getString(KEY_CACHE_KEY, null) == cacheKey) {
            readCached(prefs = prefs, memory = memory, abis = abis, preference = preference)
        } else {
            null
        }
        if (cached != null) {
            publish(cached)
            return cached
        }

        val capabilityProfile = AndroidDeviceCapabilityCollector.collect(app)
        val signals = signalsFrom(
            capabilityProfile = capabilityProfile,
            memory = memory,
            abis = abis,
        )
        val collected = AndroidDevicePlaybackSnapshot(
            profile = DevicePlaybackProfileResolver.resolve(signals, effectivePreference(preference)),
            capabilityFingerprint = runCatching { capabilityProfile.fingerprint() }.getOrNull(),
            signals = signals,
            userPreference = preference,
        )
        persist(prefs, cacheKey, signals, collected.capabilityFingerprint)
        publish(collected)
        return collected
    }

    private fun readCached(
        prefs: android.content.SharedPreferences,
        memory: MemorySignals,
        abis: List<String>,
        preference: PlaybackProfilePreference,
    ): AndroidDevicePlaybackSnapshot? {
        if (!prefs.contains(KEY_HARDWARE_AVC) || !prefs.contains(KEY_HARDWARE_HEVC)) return null
        val signals = DevicePlaybackSignals(
            sdkInt = Build.VERSION.SDK_INT.coerceAtLeast(1),
            supportedAbis = abis,
            lowRamDevice = memory.lowRamDevice,
            memoryClassMb = memory.memoryClassMb,
            totalRamMb = memory.totalRamMb,
            hasHardwareAvcDecoder = prefs.getBoolean(KEY_HARDWARE_AVC, false),
            hasHardwareHevcDecoder = prefs.getBoolean(KEY_HARDWARE_HEVC, false),
            hasAc3Decoder = prefs.getBoolean(KEY_AC3, false),
            hasEac3Decoder = prefs.getBoolean(KEY_EAC3, false),
            runtimeIs64Bit = runtimeIs64Bit(abis),
        )
        val fingerprint = prefs.getString(KEY_CAPABILITY_FINGERPRINT, null)
            ?.let { value -> runCatching { CapabilityFingerprint(value) }.getOrNull() }
        return AndroidDevicePlaybackSnapshot(
            profile = DevicePlaybackProfileResolver.resolve(signals, effectivePreference(preference)),
            capabilityFingerprint = fingerprint,
            signals = signals,
            userPreference = preference,
        )
    }

    /** User choice first; a remote per-device verdict only decides AUTO. */
    private fun effectivePreference(user: PlaybackProfilePreference): PlaybackProfilePreference =
        DevicePlaybackProfileResolver.preferenceWithOverride(
            user,
            runCatching { PlaybackRemotePolicy.deviceOverrides().compatibilityProfile }.getOrNull(),
        )

    private fun signalsFrom(
        capabilityProfile: DeviceCapabilityProfile,
        memory: MemorySignals,
        abis: List<String>,
    ): DevicePlaybackSignals {
        fun hasHardwareVideo(mime: String): Boolean = capabilityProfile.codecs.any { codec ->
            codec.mimeType.equals(mime, ignoreCase = true) &&
                codec.implementation == CodecImplementation.HARDWARE
        }
        return DevicePlaybackSignals(
            sdkInt = capabilityProfile.sdkInt,
            supportedAbis = abis,
            lowRamDevice = memory.lowRamDevice,
            memoryClassMb = memory.memoryClassMb,
            totalRamMb = memory.totalRamMb,
            hasHardwareAvcDecoder = hasHardwareVideo("video/avc"),
            hasHardwareHevcDecoder = hasHardwareVideo("video/hevc"),
            hasAc3Decoder = capabilityProfile.audio.any { it.codec == AudioCodec.AC3 },
            hasEac3Decoder = capabilityProfile.audio.any { it.codec == AudioCodec.EAC3 },
            runtimeIs64Bit = runtimeIs64Bit(abis),
        )
    }

    private fun collectMemory(context: Context): MemorySignals {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        val totalRamMb = runCatching {
            manager?.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem.takeIf { it > 0L }?.div(BYTES_PER_MIB)
        }.getOrNull()
        return MemorySignals(
            lowRamDevice = runCatching { manager?.isLowRamDevice }.getOrNull() == true,
            memoryClassMb = runCatching { manager?.memoryClass }
                .getOrNull()
                ?.coerceAtLeast(1)
                ?: DEFAULT_MEMORY_CLASS_MB,
            totalRamMb = totalRamMb,
        )
    }

    private fun persist(
        prefs: android.content.SharedPreferences,
        cacheKey: String,
        signals: DevicePlaybackSignals,
        fingerprint: CapabilityFingerprint?,
    ) {
        prefs.edit()
            .clear()
            .putString(KEY_CACHE_KEY, cacheKey)
            .putBoolean(KEY_HARDWARE_AVC, signals.hasHardwareAvcDecoder)
            .putBoolean(KEY_HARDWARE_HEVC, signals.hasHardwareHevcDecoder)
            .putBoolean(KEY_AC3, signals.hasAc3Decoder)
            .putBoolean(KEY_EAC3, signals.hasEac3Decoder)
            .apply {
                if (fingerprint != null) {
                    putString(KEY_CAPABILITY_FINGERPRINT, fingerprint.value)
                }
            }
            .apply()
    }

    private fun cacheKey(abis: List<String>): String {
        val material = buildString {
            append(CACHE_SCHEMA).append('|')
            append(Build.VERSION.SDK_INT).append('|')
            append(Build.FINGERPRINT.ifBlank { "unknown" }).append('|')
            append(abis.joinToString(","))
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun fallbackSignals() = DevicePlaybackSignals(
        sdkInt = Build.VERSION.SDK_INT.coerceAtLeast(1),
        supportedAbis = Build.SUPPORTED_ABIS.orEmpty().toList(),
        lowRamDevice = false,
        memoryClassMb = DEFAULT_MEMORY_CLASS_MB,
        totalRamMb = null,
        // Do not pessimistically cap every modern device during the short async
        // collection window. Legacy SDK/ABI facts still select compatibility on
        // old sticks; codec-specific limits become authoritative after the scan.
        hasHardwareAvcDecoder = true,
        hasHardwareHevcDecoder = true,
        hasAc3Decoder = false,
        hasEac3Decoder = false,
        runtimeIs64Bit = runtimeIs64Bit(Build.SUPPORTED_ABIS.orEmpty().toList()),
    )

    @Suppress("DEPRECATION")
    private fun runtimeIs64Bit(abis: List<String>): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Process.is64Bit()
        } else {
            abis.any { it.contains("64", ignoreCase = true) }
        }

    private data class MemorySignals(
        val lowRamDevice: Boolean,
        val memoryClassMb: Int,
        val totalRamMb: Long?,
    )

    private const val PREFS_NAME = "device_playback_profile_v1"
    private const val CACHE_SCHEMA = 1
    private const val KEY_CACHE_KEY = "cache_key"
    private const val KEY_CAPABILITY_FINGERPRINT = "capability_fingerprint"
    private const val KEY_HARDWARE_AVC = "hardware_avc"
    private const val KEY_HARDWARE_HEVC = "hardware_hevc"
    private const val KEY_AC3 = "ac3"
    private const val KEY_EAC3 = "eac3"
    private const val DEFAULT_MEMORY_CLASS_MB = 256
    private const val BYTES_PER_MIB = 1_048_576L
}
