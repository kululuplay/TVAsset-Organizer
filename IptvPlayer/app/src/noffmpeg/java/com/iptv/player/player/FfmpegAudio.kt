/*
 * FfmpegAudio.kt (noffmpeg source set)
 * Stub compiled in when app/libs/media3-decoder-ffmpeg-release.aar is absent
 * (BuildConfig.FFMPEG_AUDIO == false): local builds and any CI run that did
 * not restore the extension. Must mirror the contract of src/ffmpeg/java.
 * With this stub every pre-existing path is untouched: MediaCodec first, the
 * bundled MPEG renderer last, and libVLC via onAudioUnavailable otherwise.
 */
package com.iptv.player.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink

/** No FFmpeg extension in this build; see docs/ffmpeg-audio.md. */
@UnstableApi
internal object FfmpegAudio {
    val available: Boolean = false

    val version: String? = null

    @Suppress("UNUSED_PARAMETER")
    fun audioRenderers(
        context: Context,
        handler: Handler,
        listener: AudioRendererEventListener,
        audioSink: AudioSink,
    ): List<Renderer> = emptyList()
}
