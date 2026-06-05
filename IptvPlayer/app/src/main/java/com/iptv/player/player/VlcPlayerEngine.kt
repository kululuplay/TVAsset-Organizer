/*
 * VlcPlayerEngine.kt
 * Fallback playback backend using libVLC. Chosen when ExoPlayer can't handle a
 * codec/container (e.g. DTS/AC3/EAC3 in TS, AVI). Hardware decoding is enabled
 * but libVLC itself falls back to software decode when hardware fails.
 */
package com.iptv.player.player

import android.content.Context
import android.view.ViewGroup
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VlcPlayerEngine(private val context: Context) : PlayerEngine {

    override val engineName: String = "VLC"

    override val supportsDelay: Boolean = true

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var listener: PlayerListener? = null

    // Stored so they can be re-applied once playback (re)starts.
    private var audioDelayMs = 0L
    private var subtitleDelayMs = 0L

    override fun bind(container: ViewGroup) {
        // Low network-caching for fast live startup; enable HW decode.
        val options = arrayListOf(
            "--network-caching=1500",
            "--live-caching=1500",
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--avcodec-hw=any"
        )
        val vlc = LibVLC(context, options)
        val mp = MediaPlayer(vlc)

        val layout = VLCVideoLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(layout)
        // false = no subtitle surface yet (added with subtitle feature later).
        mp.attachViews(layout, null, false, false)

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> listener?.onBuffering()
                MediaPlayer.Event.Playing -> {
                    // VLC only accepts delays once the track is running.
                    applyDelays()
                    listener?.onPlaying()
                }
                MediaPlayer.Event.EndReached -> listener?.onEnded()
                MediaPlayer.Event.EncounteredError -> listener?.onError("VLC error")
            }
        }

        libVlc = vlc
        mediaPlayer = mp
        videoLayout = layout
    }

    override fun play(url: String) {
        val vlc = libVlc ?: return
        val mp = mediaPlayer ?: return
        val media = Media(vlc, android.net.Uri.parse(url)).apply {
            // Prefer hardware decoding; libVLC degrades to software on failure.
            setHWDecoderEnabled(true, true)
            addOption(":network-caching=1500")
        }
        mp.media = media
        media.release()
        mp.play()
    }

    override fun pause() { mediaPlayer?.pause() }
    override fun resume() { mediaPlayer?.play() }
    override fun stop() { mediaPlayer?.stop() }

    override fun release() {
        mediaPlayer?.setEventListener(null)
        mediaPlayer?.stop()
        mediaPlayer?.detachViews()
        mediaPlayer?.release()
        libVlc?.release()
        mediaPlayer = null
        libVlc = null
        videoLayout = null
        listener = null
    }

    override fun setListener(listener: PlayerListener?) {
        this.listener = listener
    }

    // VLC delays are expressed in microseconds.
    override fun setAudioDelayMs(ms: Long) {
        audioDelayMs = ms
        mediaPlayer?.audioDelay = ms * 1000
    }

    override fun setSubtitleDelayMs(ms: Long) {
        subtitleDelayMs = ms
        mediaPlayer?.spuDelay = ms * 1000
    }

    private fun applyDelays() {
        mediaPlayer?.let {
            it.audioDelay = audioDelayMs * 1000
            it.spuDelay = subtitleDelayMs * 1000
        }
    }
}
