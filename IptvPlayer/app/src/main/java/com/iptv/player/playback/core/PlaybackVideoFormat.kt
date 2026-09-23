package com.iptv.player.playback.core

import java.util.Locale

enum class PlaybackVideoDecoder { HARDWARE, SOFTWARE, UNKNOWN }
enum class PlaybackVideoCodec {
    H264, H265, MPEG2, MPEG4, VP8, VP9, AV1, OTHER, UNKNOWN;

    companion object {
        fun from(value: String?): PlaybackVideoCodec = when (value?.trim()?.lowercase(Locale.ROOT)) {
            null, "" -> UNKNOWN
            "video/avc", "h264", "avc1", "avc3" -> H264
            "video/hevc", "h265", "hevc", "hvc1", "hev1" -> H265
            "video/mpeg2", "mpeg2", "mp2v", "mpgv" -> MPEG2
            "video/mp4v-es", "mpeg4", "mp4v", "divx", "xvid" -> MPEG4
            "video/x-vnd.on2.vp8", "vp8", "vp80" -> VP8
            "video/x-vnd.on2.vp9", "vp9", "vp90" -> VP9
            "video/av01", "av1", "av01" -> AV1
            else -> OTHER
        }
    }
}

/** Only closed enums and bounded numerical format information may leave a player. */
data class PlaybackVideoFormat(
    val codec: PlaybackVideoCodec,
    val decoder: PlaybackVideoDecoder = PlaybackVideoDecoder.UNKNOWN,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
) {
    fun toSafeFields(): Map<String, Any> = buildMap {
        put("video_codec", codec.name)
        put("video_decoder", decoder.name)
        width?.takeIf { it in 1..16384 }?.let { put("video_width", it) }
        height?.takeIf { it in 1..16384 }?.let { put("video_height", it) }
        frameRate?.takeIf { it.isFinite() && it in 1f..240f }?.let { put("frame_rate", it) }
    }
}
