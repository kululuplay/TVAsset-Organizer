package com.iptv.player.player

import android.content.Context
import androidx.annotation.StringRes
import com.iptv.player.R
import com.iptv.player.playback.core.PlaybackFailure

/** Only translated, closed messages: never display provider bodies or URLs. */
internal object LivePlaybackMessages {
    fun message(context: Context, failure: PlaybackFailure?): String = context.getString(resource(failure))

    @StringRes fun resource(failure: PlaybackFailure?): Int = when (failure?.code) {
        PlaybackFailure.Code.HTTP_UNAUTHORIZED, PlaybackFailure.Code.HTTP_FORBIDDEN -> R.string.live_error_access
        PlaybackFailure.Code.HTTP_NOT_FOUND -> R.string.live_error_missing
        PlaybackFailure.Code.HTTP_RATE_LIMITED -> R.string.live_error_busy
        PlaybackFailure.Code.HTTP_SERVER_ERROR -> R.string.live_error_server
        PlaybackFailure.Code.NETWORK_UNAVAILABLE -> R.string.error_no_internet
        PlaybackFailure.Code.TLS_FAILED -> R.string.live_error_secure_connection
        PlaybackFailure.Code.DNS_LOOKUP_FAILED, PlaybackFailure.Code.CONNECTION_FAILED,
        PlaybackFailure.Code.CONNECTION_RESET, PlaybackFailure.Code.READ_TIMEOUT,
        PlaybackFailure.Code.END_OF_STREAM, PlaybackFailure.Code.HTTP_REQUEST_TIMEOUT -> R.string.error_cannot_connect
        PlaybackFailure.Code.SOURCE_MALFORMED, PlaybackFailure.Code.SOURCE_UNSUPPORTED,
        PlaybackFailure.Code.CODEC_UNSUPPORTED -> R.string.live_error_format
        PlaybackFailure.Code.DECODER_INIT_FAILED, PlaybackFailure.Code.DECODER_RUNTIME_FAILED,
        PlaybackFailure.Code.DECODER_RESOURCES_RECLAIMED, PlaybackFailure.Code.VIDEO_OUTPUT_FAILED,
        PlaybackFailure.Code.AUDIO_SINK_FAILED, PlaybackFailure.Code.AUDIO_STALL -> R.string.live_error_player
        PlaybackFailure.Code.STARTUP_TIMEOUT, PlaybackFailure.Code.PLAYBACK_STALL -> R.string.live_error_timeout
        else -> R.string.error_live_playback_failed
    }
}
