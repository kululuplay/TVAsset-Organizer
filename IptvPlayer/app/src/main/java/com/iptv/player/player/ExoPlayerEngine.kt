/*
 * ExoPlayerEngine.kt
 * Primary playback backend using AndroidX Media3 (ExoPlayer). Handles HLS/DASH/
 * progressive automatically. Tuned for fast live startup with small buffers.
 */
package com.iptv.player.player

import android.content.Context
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class ExoPlayerEngine(private val context: Context) : PlayerEngine {

    override val engineName: String = "ExoPlayer"

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var listener: PlayerListener? = null

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

        val exo = ExoPlayer.Builder(context)
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

        val view = PlayerView(context).apply {
            useController = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            this.player = exo
        }
        container.addView(view)

        player = exo
        playerView = view
    }

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
}
