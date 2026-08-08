package com.iptv.player.playback.android

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.iptv.player.playback.core.AudioCapability
import com.iptv.player.playback.core.AudioCodec
import com.iptv.player.playback.core.CodecCapability
import com.iptv.player.playback.core.CodecImplementation
import com.iptv.player.playback.core.CodecProfileLevel
import com.iptv.player.playback.core.DeviceCapabilityProfile
import com.iptv.player.playback.core.DisplayCapability
import com.iptv.player.playback.core.HdrType

/** Best-effort local collector. Only the resulting SHA-256 fingerprint is uploaded. */
internal object AndroidDeviceCapabilityCollector {

    fun collect(context: Context): DeviceCapabilityProfile {
        val app = context.applicationContext
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val display = collectDisplay(app)
        val codecSnapshot = collectCodecs()
        return DeviceCapabilityProfile(
            sdkInt = Build.VERSION.SDK_INT,
            firmwareId = Build.FINGERPRINT.ifBlank { "unknown" },
            lowRamDevice = runCatching { activityManager?.isLowRamDevice }
                .getOrNull() == true,
            memoryClassMb = runCatching { activityManager?.memoryClass }
                .getOrNull()
                ?.coerceAtLeast(1)
                ?: DEFAULT_MEMORY_CLASS_MB,
            display = display,
            audio = codecSnapshot.second,
            codecs = codecSnapshot.first,
        )
    }

    @Suppress("DEPRECATION")
    private fun collectDisplay(context: Context): DisplayCapability {
        return runCatching {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = manager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val hdr = if (Build.VERSION.SDK_INT >= 24) {
                display.hdrCapabilities.supportedHdrTypes
                    .asSequence()
                    .mapNotNull { type ->
                        when (type) {
                            android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrType.HDR10
                            // HDR10+ was added after HdrCapabilities itself. Use the
                            // wire value so API 24-28 never resolve a newer field.
                            4 -> HdrType.HDR10_PLUS
                            android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> HdrType.HLG
                            android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION ->
                                HdrType.DOLBY_VISION
                            else -> null
                        }
                    }
                    .toSet()
            } else {
                emptySet()
            }
            DisplayCapability(
                maxWidth = metrics.widthPixels.coerceAtLeast(1),
                maxHeight = metrics.heightPixels.coerceAtLeast(1),
                maxRefreshRateMilliHz = safeMilliRate(display.refreshRate),
                hdrTypes = hdr,
            )
        }.getOrElse {
            // Window/display services can be absent in vendor secondary processes.
            // Resource metrics require no permission and preserve a usable, bounded
            // fingerprint instead of failing the complete codec snapshot.
            val metrics = context.resources.displayMetrics
            DisplayCapability(
                maxWidth = metrics.widthPixels.coerceAtLeast(1),
                maxHeight = metrics.heightPixels.coerceAtLeast(1),
                maxRefreshRateMilliHz = safeMilliRate(DEFAULT_REFRESH_RATE_HZ),
                hdrTypes = emptySet(),
            )
        }
    }

    private fun collectCodecs(): Pair<List<CodecCapability>, Set<AudioCapability>> {
        val video = mutableListOf<CodecCapability>()
        val audio = mutableSetOf<AudioCapability>()
        val infos = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        }.getOrDefault(emptyList())
        for (info in infos) {
            if (info.isEncoder) continue
            val types = runCatching { info.supportedTypes.toList() }.getOrDefault(emptyList())
            for (type in types) {
                val capabilities = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                    ?: continue
                when {
                    type.startsWith("video/", ignoreCase = true) -> {
                        buildVideoCapability(info, type, capabilities)?.let(video::add)
                    }
                    type.startsWith("audio/", ignoreCase = true) -> {
                        buildAudioCapability(type, capabilities)?.let(audio::add)
                    }
                }
            }
        }
        return video to audio
    }

    private fun buildVideoCapability(
        info: MediaCodecInfo,
        mimeType: String,
        capabilities: MediaCodecInfo.CodecCapabilities,
    ): CodecCapability? = runCatching {
        val videoCaps = capabilities.videoCapabilities
        CodecCapability(
            implementationName = info.name,
            mimeType = mimeType,
            implementation = implementation(info),
            vendor = isVendor(info),
            securePlayback = capabilities.isFeatureSupported(
                MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
            ),
            adaptivePlayback = capabilities.isFeatureSupported(
                MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback,
            ),
            tunneledPlayback = capabilities.isFeatureSupported(
                MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
            ),
            maxWidth = runCatching { videoCaps.supportedWidths.upper }
                .getOrNull()
                ?.takeIf { it > 0 },
            maxHeight = runCatching { videoCaps.supportedHeights.upper }
                .getOrNull()
                ?.takeIf { it > 0 },
            maxFrameRateMilliFps = runCatching {
                safeMilliRate(videoCaps.supportedFrameRates.upper.toFloat())
            }.getOrNull(),
            profileLevels = capabilities.profileLevels.orEmpty().map {
                CodecProfileLevel(profile = it.profile, level = it.level)
            }.toSet(),
        )
    }.getOrNull()

    private fun buildAudioCapability(
        mimeType: String,
        capabilities: MediaCodecInfo.CodecCapabilities,
    ): AudioCapability? = runCatching {
        val codec = audioCodec(mimeType) ?: return@runCatching null
        val maxChannels = capabilities.audioCapabilities.maxInputChannelCount.coerceAtLeast(1)
        AudioCapability(
            codec = codec,
            maxChannelCount = maxChannels,
            passthrough = false,
        )
    }.getOrNull()

    private fun implementation(info: MediaCodecInfo): CodecImplementation =
        if (Build.VERSION.SDK_INT >= 29) {
            when {
                info.isHardwareAccelerated -> CodecImplementation.HARDWARE
                info.isSoftwareOnly -> CodecImplementation.SOFTWARE
                else -> CodecImplementation.UNKNOWN
            }
        } else {
            val name = info.name.lowercase()
            if (
                name.startsWith("omx.google.") ||
                name.startsWith("c2.android.") ||
                name.contains("ffmpeg") ||
                name.contains("software")
            ) {
                CodecImplementation.SOFTWARE
            } else {
                CodecImplementation.HARDWARE
            }
        }

    private fun isVendor(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isVendor
        else implementation(info) == CodecImplementation.HARDWARE

    private fun audioCodec(mime: String): AudioCodec? = when (mime.lowercase()) {
        "audio/mp4a-latm", "audio/aac" -> AudioCodec.AAC
        "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> AudioCodec.MPEG_AUDIO
        "audio/ac3" -> AudioCodec.AC3
        "audio/eac3", "audio/eac3-joc" -> AudioCodec.EAC3
        "audio/vnd.dts", "audio/vnd.dts.hd" -> AudioCodec.DTS
        "audio/true-hd" -> AudioCodec.TRUEHD
        "audio/opus" -> AudioCodec.OPUS
        "audio/flac" -> AudioCodec.FLAC
        "audio/raw" -> AudioCodec.PCM
        else -> null
    }

    private fun safeMilliRate(rate: Float, fallback: Float = 1f): Int {
        val safeRate = rate.takeIf { it.isFinite() && it > 0f } ?: fallback
        return (safeRate * 1_000f).toInt().coerceAtLeast(1)
    }

    private const val DEFAULT_MEMORY_CLASS_MB = 128
    private const val DEFAULT_REFRESH_RATE_HZ = 60f
}
