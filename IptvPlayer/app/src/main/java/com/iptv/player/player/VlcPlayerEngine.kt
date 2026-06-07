/*
 * VlcPlayerEngine.kt
 * Software-capable playback backend using libVLC. Used as the automatic fallback
 * when ExoPlayer greens out (hardware H.264 failure) or can't decode an audio
 * codec (AC-3/E-AC-3/MP2/DTS). libVLC decodes everything in software to PCM, so
 * it is the safety net that guarantees both picture and sound on weak sticks.
 *
 * Real-stick fixes:
 *   - Audio: AudioTrack output, SPDIF/passthrough OFF by default (:no-spdif) so
 *     everything is decoded to PCM stereo any HDMI sink accepts.
 *   - Video: SurfaceView output via VLCVideoLayout + attachViews (useTextureView
 *     = false) so libVLC's AWindow handler is fully wired and the Amlogic-style
 *     hardware underlay video plane can actually be shown. TextureView CANNOT
 *     display that underlay and guarantees a green frame on real sticks.
 *   - Deinterlace enabled (interlaced 1080i feeds green-screen on Amlogic HW
 *     without it); explicit RV32 display chroma + no hardware direct-rendering.
 *   - forceSoftware: disable MediaCodec entirely for streams where the hardware
 *     decoder produced a green/blank frame.
 */
package com.iptv.player.player

import android.content.Context
import android.view.ViewGroup
import com.iptv.player.util.AppInfo
import com.iptv.player.util.PlaybackLog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VlcPlayerEngine(
    private val context: Context,
    /** When true, MediaCodec is disabled and decoding is pure software. */
    private val forceSoftware: Boolean = false,
    /** When false (default) audio is decoded to PCM; true allows SPDIF passthrough. */
    private val allowPassthrough: Boolean = false
) : PlayerEngine {

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
            // Hardware decode unless we've been told to force software.
            if (forceSoftware) "--avcodec-hw=none" else "--avcodec-hw=any",
            // NOTE: do NOT disable direct rendering (--no-mediacodec-dr /
            // --no-omxil-dr). On Amlogic the hardware decoder draws onto a
            // dedicated underlay video plane; copying frames out (DR off) fights
            // that path and REINTRODUCES the green screen (A/B-confirmed on the
            // user's real hardware). Keep DR on + SurfaceView output instead.
            // Resolve the color-format mismatch behind the green frame by asking
            // for a known-good 32-bit display chroma.
            "--android-display-chroma=RV32",
            // Many live SD/HD IPTV feeds are interlaced (1080i); Amlogic hardware
            // often green-screens on interlaced content unless we deinterlace.
            // -1 = auto: only kicks in for interlaced streams, leaves progressive
            // untouched. yadif = quality (hardware path), bob = cheap (software).
            "--deinterlace=-1",
            "--deinterlace-mode=${if (forceSoftware) "bob" else "yadif"}",
            // Cut decode load so cheap TV boxes keep up: skip the deblocking
            // loop filter and allow fast (slightly looser) decoding.
            "--avcodec-skiploopfilter=all",
            "--avcodec-fast",
            // Auto-reconnect when an HTTP segment/stream connection drops.
            "--http-reconnect",
            // Report KULULUPLAY instead of the default "VLC/3.0.x LibVLC/3.0.x".
            "--http-user-agent=${AppInfo.USER_AGENT}"
        )
        // Default = decode audio to PCM (no passthrough). Disable SPDIF so AC-3/
        // E-AC-3/DTS are software-decoded to stereo PCM that any HDMI sink plays.
        if (!allowPassthrough) options.add("--no-spdif")

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
        // attachViews fully wires libVLC's AWindow/IVLCVout handler (fixes the
        // "libvlc window: request not implemented" green-screen spam). 4th arg
        // useTextureView = false: SurfaceView is REQUIRED to show the Amlogic
        // hardware underlay video plane — a TextureView always greens out.
        // (3rd arg false = no subtitle surface yet; added with subtitles.)
        mp.attachViews(layout, null, false, false)

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
                MediaPlayer.Event.EncounteredError -> {
                    PlaybackLog.log(context, engineName, "EncounteredError (forceSoftware=$forceSoftware)")
                    listener?.onError("VLC error")
                }
            }
        }

        libVlc = vlc
        mediaPlayer = mp
        videoLayout = layout
    }

    override fun play(url: String) {
        val vlc = libVlc ?: return
        val mp = mediaPlayer ?: return
        PlaybackLog.log(context, engineName, "play forceSoftware=$forceSoftware passthrough=$allowPassthrough")
        val media = Media(vlc, android.net.Uri.parse(url)).apply {
            // Hardware decoding unless software was forced (then both off).
            setHWDecoderEnabled(!forceSoftware, !forceSoftware)
            // Mirror the instance buffer/jitter tuning at the stream level.
            addOption(":network-caching=$NETWORK_CACHING_MS")
            addOption(":live-caching=$NETWORK_CACHING_MS")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            if (forceSoftware) addOption(":avcodec-hw=none")
            if (!allowPassthrough) addOption(":no-spdif")
            // Auto-deinterlace interlaced (1080i) feeds at the stream level too.
            addOption(":deinterlace=-1")
            addOption(":deinterlace-mode=${if (forceSoftware) "bob" else "yadif"}")
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
