/*
 * VlcPlayerEngine.kt
 * Fallback playback backend using libVLC. Chosen when ExoPlayer can't handle a
 * codec/container (e.g. DTS/AC3/EAC3 in TS, AVI). Hardware decoding is enabled
 * but libVLC itself falls back to software decode when hardware fails.
 */
package com.iptv.player.player

import android.content.Context
import android.view.ViewGroup
import com.iptv.player.util.AppInfo
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
        // Tuned for smooth IPTV on Android TV: a generous network buffer to
        // absorb jitter, plus options that keep playback real-time on weak
        // hardware instead of accumulating delay (the main cause of stutter).
        val options = arrayListOf(
            // Bigger live buffer soaks up network hiccups before they freeze.
            "--network-caching=$NETWORK_CACHING_MS",
            "--live-caching=$NETWORK_CACHING_MS",
            // Many IPTV TS streams carry irregular PCR/timestamps; disabling the
            // jitter/synchro guards stops VLC from stalling to "catch up".
            "--clock-jitter=0",
            "--clock-synchro=0",
            // Keep playback real-time: let VLC drop late frames (the defaults)
            // rather than rendering every frame and lagging further behind.
            "--avcodec-hw=any",
            // Disable hardware "direct rendering": several Android TV panels show
            // a solid green picture (audio fine) when the MediaCodec/OMX decoder
            // pushes frames straight to the surface. Copying frames out first
            // keeps hardware decode but renders correctly.
            "--no-mediacodec-dr",
            "--no-omxil-dr",
            // Cut decode load so cheap TV boxes keep up: skip the deblocking
            // loop filter and allow fast (slightly looser) decoding.
            "--avcodec-skiploopfilter=all",
            "--avcodec-fast",
            // Auto-reconnect when an HTTP segment/stream connection drops.
            "--http-reconnect",
            // Report KULULUPLAY instead of the default "VLC/3.0.x LibVLC/3.0.x".
            "--http-user-agent=${AppInfo.USER_AGENT}"
        )
        val vlc = LibVLC(context, options)
        // Belt-and-braces: also set it on the instance (name + http UA).
        vlc.setUserAgent(AppInfo.USER_AGENT, AppInfo.USER_AGENT)
        val mp = MediaPlayer(vlc)

        val layout = VLCVideoLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(layout)
        // 3rd arg false = no subtitle surface yet (added with subtitle feature
        // later). 4th arg true = render through a TextureView instead of the
        // default SurfaceView: on several Android TV panels live channels still
        // show a solid green picture (audio fine) through the SurfaceView
        // hardware-overlay path even with mediacodec/omxil direct rendering
        // disabled. A TextureView routes decoded frames through the GPU and
        // avoids that overlay/color path entirely.
        mp.attachViews(layout, null, false, true)

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    // VLC keeps firing Buffering during normal playback (cache
                    // top-ups), ending each cycle at 100%. Only treat <100% as
                    // actual buffering; 100% means the stream is running, so hide
                    // the spinner — otherwise it stays on screen forever.
                    if (event.buffering < 100f) listener?.onBuffering()
                    else listener?.onPlaying()
                }
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
            // Mirror the instance buffer/jitter tuning at the stream level.
            addOption(":network-caching=$NETWORK_CACHING_MS")
            addOption(":live-caching=$NETWORK_CACHING_MS")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            // Auto-reconnect dropped HTTP connections instead of erroring out.
            addOption(":http-reconnect")
            // Per-stream User-Agent override (covers HTTP(S) playlist + segments).
            addOption(":http-user-agent=${AppInfo.USER_AGENT}")
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

    companion object {
        /**
         * Live/network buffer in ms. A larger buffer trades a little extra
         * zap/startup latency for far fewer stalls and freezes on jittery IPTV
         * connections — the priority for smooth Android TV playback.
         */
        private const val NETWORK_CACHING_MS = 3000
    }
}
