/*
 * VodPlayerMappings.kt
 *
 * Architecture note (VOD player decomposition):
 *   Small pure mappings pulled out of VodPlayerActivity so they can be unit
 *   tested on the JVM: customer-facing error message resources, the QoE
 *   engine/transport classification and the mm:ss / h:mm:ss time label.
 *
 * Moved verbatim from VodPlayerActivity.
 */
package com.iptv.player.ui.player

import com.iptv.player.R
import com.iptv.player.player.VodPlaybackRoutingPolicy
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackTransportKind
import java.util.Locale

internal object VodErrorMessages {
    fun messageRes(message: VodPlaybackRoutingPolicy.CustomerMessage): Int = when (message) {
        VodPlaybackRoutingPolicy.CustomerMessage.AUTHORIZATION ->
            R.string.vod_error_authorization
        VodPlaybackRoutingPolicy.CustomerMessage.ACCESS_DENIED ->
            R.string.vod_error_access_denied
        VodPlaybackRoutingPolicy.CustomerMessage.CONTENT_UNAVAILABLE ->
            R.string.vod_error_content_unavailable
        VodPlaybackRoutingPolicy.CustomerMessage.RANGE_REJECTED ->
            R.string.vod_error_range_rejected
        VodPlaybackRoutingPolicy.CustomerMessage.RATE_LIMITED ->
            R.string.vod_error_rate_limited
        VodPlaybackRoutingPolicy.CustomerMessage.SERVER_UNAVAILABLE ->
            R.string.vod_error_server_unavailable
        VodPlaybackRoutingPolicy.CustomerMessage.TIMEOUT ->
            R.string.vod_error_timeout
        VodPlaybackRoutingPolicy.CustomerMessage.TLS ->
            R.string.vod_error_tls
        VodPlaybackRoutingPolicy.CustomerMessage.DNS ->
            R.string.vod_error_dns
        VodPlaybackRoutingPolicy.CustomerMessage.DECODER ->
            R.string.vod_error_decoder
        VodPlaybackRoutingPolicy.CustomerMessage.VIDEO_OUTPUT ->
            R.string.vod_error_video_output
        VodPlaybackRoutingPolicy.CustomerMessage.SOURCE ->
            R.string.vod_error_source
        VodPlaybackRoutingPolicy.CustomerMessage.GENERIC ->
            R.string.error_cannot_play_content
    }
}

internal object VodQoeMapping {
    fun engine(route: VodPlaybackRoutingPolicy.Route): PlaybackEngineKind =
        if (route == VodPlaybackRoutingPolicy.Route.EXO) {
            PlaybackEngineKind.EXO_PLAYER
        } else {
            PlaybackEngineKind.VLC
        }

    fun transport(url: String?): PlaybackTransportKind {
        val path = url.orEmpty().substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".mpd") -> PlaybackTransportKind.DASH
            path.endsWith(".ts") -> PlaybackTransportKind.MPEG_TS
            path.isNotBlank() -> PlaybackTransportKind.PROGRESSIVE
            else -> PlaybackTransportKind.UNKNOWN
        }
    }
}

internal object VodTimeFormat {
    fun format(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val locale = Locale.getDefault()
        return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s)
        else String.format(locale, "%02d:%02d", m, s)
    }
}
