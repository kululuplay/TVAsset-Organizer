/*
 * FfmpegAudio.kt (ffmpeg source set)
 * Compiled in only when app/libs/media3-decoder-ffmpeg-release.aar exists at
 * Gradle configuration time (BuildConfig.FFMPEG_AUDIO == true). The sibling
 * src/noffmpeg/java copy is a stub with the same contract. Both must keep the
 * exact same public surface; ExoPlayerEngine and Media3VodEngine depend on it.
 */
package com.iptv.player.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink

/** Media3 FFmpeg software audio decoders, built from source by CI. */
@UnstableApi
internal object FfmpegAudio {
    /**
     * True when libffmpegJNI.so loaded for this ABI. A packaging problem (e.g.
     * a missing ABI) leaves this false and the caller must behave exactly like
     * the no-extension build so the libVLC fallback still covers audio.
     */
    val available: Boolean
        get() = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)

    /** FFmpeg build identity for diagnostics; null when the library is absent. */
    val version: String?
        get() = runCatching { FfmpegLibrary.getVersion() }.getOrNull()

    /**
     * A fresh FFmpeg audio renderer list for one player instance. Each call
     * creates new renderer objects: a Renderer belongs to exactly one player.
     * Empty when the native library could not be loaded.
     */
    fun audioRenderers(
        context: Context,
        handler: Handler,
        listener: AudioRendererEventListener,
        audioSink: AudioSink,
    ): List<Renderer> {
        if (!available) return emptyList()
        return listOf(FfmpegAudioRenderer(handler, listener, audioSink))
    }
}
