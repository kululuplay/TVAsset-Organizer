/*
 * ExoPlayerEngine.kt
 * Primary playback backend using AndroidX Media3 (ExoPlayer). Handles HLS/DASH/
 * progressive automatically. Tuned for fast live startup with small buffers.
 */
package com.iptv.player.player

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.iptv.player.R
import com.iptv.player.util.AppInfo
import java.util.Locale

class ExoPlayerEngine(private val context: Context) : PlayerEngine {

    override val engineName: String = "ExoPlayer"

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var listener: PlayerListener? = null

    // Remembered language preferences, re-applied via track-selection parameters
    // (which persist on the player across media items, so zapping keeps them).
    private var preferredAudioLang: String? = null
    private var preferredSubtitleLang: String? = null

    override fun bind(container: ViewGroup) {
        // Small buffers => quick channel zap on live streams.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2000,
                /* maxBufferMs = */ 8000,
                /* bufferForPlaybackMs = */ 1000,
                /* bufferForPlaybackAfterRebufferMs = */ 1500
            )
            .build()

        // Identify every HTTP(S) stream pull as KULULUPLAY (some providers gate
        // playback on the User-Agent).
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(AppInfo.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        val exo = ExoPlayer.Builder(context, buildRenderersFactory())
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener?.onBuffering()
                    Player.STATE_READY -> listener?.onPlaying()
                    Player.STATE_ENDED -> listener?.onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error.errorCodeName)
            }
        })

        // Inflated from XML so the surface is a TextureView (app:surface_type),
        // which avoids the green-screen-with-audio overlay bug seen with the
        // default SurfaceView on some Android TV panels.
        val view = (LayoutInflater.from(context)
            .inflate(R.layout.view_exo_player, container, false) as PlayerView).apply {
            this.player = exo
        }
        container.addView(view)

        player = exo
        playerView = view
    }

    /**
     * Renderers tuned so Dolby Digital (AC-3) / Dolby Digital Plus (E-AC-3) play
     * with sound on every device. Many Android TV boxes advertise Dolby
     * passthrough over HDMI even when nothing downstream can decode it, so
     * ExoPlayer hands the bitstream off and you get a picture but silence — and
     * because that is not an error, the libVLC fallback never kicks in.
     *
     * Forcing [AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES] (stereo PCM only)
     * disables passthrough, so E-AC-3 is decoded to PCM on-device and audible.
     * If the device has no Dolby decoder at all, ExoPlayer raises a real error
     * and the controller falls back to libVLC (software E-AC-3 decode).
     */
    private fun buildRenderersFactory(): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }.setEnableDecoderFallback(true)

    override fun play(url: String) {
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.playWhenReady = true
        exo.prepare()
    }

    override fun pause() { player?.playWhenReady = false }
    override fun resume() { player?.playWhenReady = true }
    override fun stop() { player?.stop() }

    override fun release() {
        player?.release()
        player = null
        playerView?.player = null
        playerView = null
        listener = null
    }

    override fun setListener(listener: PlayerListener?) {
        this.listener = listener
    }

    // ---- Track selection -------------------------------------------------

    override fun availableAudioTracks(): List<TrackOption> =
        languageOptions(C.TRACK_TYPE_AUDIO)

    override fun availableSubtitleTracks(): List<TrackOption> =
        languageOptions(C.TRACK_TYPE_TEXT)

    /** Collapses a track type's groups into one option per distinct language. */
    private fun languageOptions(trackType: Int): List<TrackOption> {
        val exo = player ?: return emptyList()
        val byLang = LinkedHashMap<String, TrackOption>()
        for (group in exo.currentTracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                val lang = group.getTrackFormat(i).language ?: continue
                val selected = group.isTrackSelected(i)
                val existing = byLang[lang]
                byLang[lang] = TrackOption(
                    token = lang,
                    label = languageLabel(lang),
                    selected = selected || (existing?.selected == true)
                )
            }
        }
        return byLang.values.toList()
    }

    override fun selectAudioTrack(token: String) {
        preferredAudioLang = token
        applyTrackPrefs()
    }

    override fun selectSubtitleTrack(token: String) {
        preferredSubtitleLang = token
        applyTrackPrefs()
    }

    private fun applyTrackPrefs() {
        val exo = player ?: return
        var params = exo.trackSelectionParameters.buildUpon()
        preferredAudioLang?.takeIf { it.isNotBlank() }?.let {
            params = params.setPreferredAudioLanguage(it)
        }
        when (val sub = preferredSubtitleLang) {
            null -> { /* untouched */ }
            PlayerEngine.SUBTITLE_OFF ->
                params = params
                    .setPreferredTextLanguage(null)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else ->
                params = params
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(sub)
        }
        exo.trackSelectionParameters = params.build()
    }

    private fun languageLabel(lang: String): String =
        runCatching { Locale(lang).getDisplayLanguage(Locale.getDefault()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != lang }
            ?.replaceFirstChar { it.uppercase(Locale.getDefault()) }
            ?: lang
}
