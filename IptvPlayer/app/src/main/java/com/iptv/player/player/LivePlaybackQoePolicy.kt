package com.iptv.player.player

import com.iptv.player.playback.core.PlaybackContentKind
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackTransportKind
import com.iptv.player.data.model.StreamFormat

/**
 * Pure, closed-enum mapping at the Live UI -> QoE boundary.
 *
 * Keeping this mapping away from the Activity makes the privacy contract easy to
 * test: native engine labels are reduced to a known engine and no URL, channel
 * name, token or free-form error text can enter the session descriptor.
 */
internal object LivePlaybackQoePolicy {

    data class SessionDescriptor(
        val content: PlaybackContentKind,
        val transport: PlaybackTransportKind,
    )

    fun sessionDescriptor(
        radio: Boolean,
        hls: Boolean,
    ): SessionDescriptor = SessionDescriptor(
        content = if (radio) PlaybackContentKind.RADIO else PlaybackContentKind.LIVE_TV,
        transport = if (hls) PlaybackTransportKind.HLS else PlaybackTransportKind.MPEG_TS,
    )

    fun transport(format: StreamFormat): PlaybackTransportKind = when (format) {
        StreamFormat.HLS -> PlaybackTransportKind.HLS
        StreamFormat.TS -> PlaybackTransportKind.MPEG_TS
    }

    fun engine(engineName: String): PlaybackEngineKind = when {
        engineName.startsWith("Exo", ignoreCase = true) -> PlaybackEngineKind.EXO_PLAYER
        engineName.startsWith("VLC", ignoreCase = true) -> PlaybackEngineKind.VLC
        else -> PlaybackEngineKind.UNKNOWN
    }
}
