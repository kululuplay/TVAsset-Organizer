package com.iptv.player.player

import com.iptv.player.playback.core.PlaybackVideoCodec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Technical signature of a live stream, coarse enough that channels of the
 * same provider profile share it: video codec family, resolution bucket (as a
 * canonical width), frame-rate family (with an `i` for interlaced H.264) and
 * audio codec family. Example key: `H264|1920|50i|eac3`.
 *
 * Route learning (PlaybackLearnedRouting layer 2) keys on this, so a decoder
 * that cannot handle one 1080i50 E-AC-3 channel is not re-tried on the next
 * channel with the same signature. Never carries a URL, a channel name or a
 * decoder name.
 */
data class StreamFingerprint(
    val videoCodec: PlaybackVideoCodec,
    val widthBucket: Int,
    /** 0 = unknown, otherwise 24 / 25 / 30 / 50 / 60 (other rates rounded). */
    val frameRateFamily: Int,
    val interlaced: Boolean,
    val audioCodec: String,
) {
    val key: String
        get() = "${videoCodec.name}|$widthBucket|$frameRateFamily${if (interlaced) "i" else ""}|$audioCodec"

    companion object {
        /** Null until the video format is known (codec and dimensions). */
        fun from(
            videoMime: String?,
            width: Int,
            height: Int,
            fps: Float,
            interlaced: Boolean?,
            audioMime: String?,
        ): StreamFingerprint? {
            val codec = PlaybackVideoCodec.from(videoMime)
            if (codec == PlaybackVideoCodec.UNKNOWN || width <= 0 || height <= 0) return null
            return StreamFingerprint(
                videoCodec = codec,
                widthBucket = widthBucket(width, height),
                frameRateFamily = frameRateFamily(fps),
                interlaced = interlaced == true,
                audioCodec = audioFamily(audioMime),
            )
        }

        /**
         * Canonical width of the resolution class. Height decides because
         * anamorphic 1440x1080 and 1920x1080 are the same decoder load.
         */
        fun widthBucket(width: Int, height: Int): Int = when {
            height <= 0 && width <= 0 -> 0
            height in 1..600 || (height <= 0 && width <= 1024) -> 720
            height in 601..800 || (height <= 0 && width <= 1400) -> 1280
            height in 801..1200 || (height <= 0 && width <= 2000) -> 1920
            height in 1201..1500 || (height <= 0 && width <= 2600) -> 2560
            else -> 3840
        }

        fun frameRateFamily(fps: Float): Int {
            if (!fps.isFinite() || fps <= 0f) return 0
            val families = intArrayOf(24, 25, 30, 50, 60)
            val nearest = families.minByOrNull { abs(it - fps) } ?: return fps.roundToInt()
            return if (abs(nearest - fps) <= 1.5f) nearest else fps.roundToInt()
        }

        /** Media3 MIME types and libVLC fourcc names fold into one family name. */
        fun audioFamily(audioMime: String?): String {
            val raw = audioMime?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (raw.isEmpty()) return "?"
            return when {
                raw.contains("eac3") || raw.contains("e-ac3") || raw.contains("ec-3") -> "eac3"
                raw.contains("ac3") || raw.contains("a52") || raw.contains("ac-3") -> "ac3"
                raw.contains("mp4a") || raw.contains("aac") -> "aac"
                raw.contains("mpeg-l2") || raw.contains("mpeg-l1") || raw == "mp2" || raw == "mpga" -> "mp2"
                raw.contains("mpeg") || raw == "mp3" -> "mp3"
                raw.contains("dts") || raw.contains("dca") -> "dts"
                raw.contains("opus") -> "opus"
                raw.contains("vorbis") || raw.contains("vorb") -> "vorbis"
                raw.contains("pcm") || raw.contains("raw") || raw.startsWith("s16") || raw.startsWith("f32") -> "pcm"
                raw == "none" -> "none"
                else -> raw.substringAfter('/').take(8).ifEmpty { "?" }
            }
        }
    }
}
