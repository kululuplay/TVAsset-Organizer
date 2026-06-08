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
 *   - MediaCodec direct rendering LEFT ON (libVLC default) on the hardware path so
 *     the Amlogic decoder renders straight onto the SurfaceView underlay (its
 *     native hardware-video path). The old DR-off + android_display vout path could
 *     not colour-convert NV12 on the Xiaomi compositor and stayed GREEN; RV16/RV32
 *     display-chroma overrides had ZERO effect (log still showed "output: 21
 *     Biplanar", never converted), so they were dropped. ExoPlayer (Media3) is the
 *     other native MediaCodec->Surface path and is selectable as the engine.
 *     Software deinterlace applies ONLY on the forceSoftware path. See the Amlogic
 *     green-screen memory note.
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

    // One-shot per stream so the green-prone profile check fires at most once.
    private var greenCheckDone = false

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
            // MediaCodec/OMX direct rendering is LEFT ON (libVLC default) on the
            // hardware path: the Amlogic decoder then renders frames straight onto
            // the SurfaceView underlay plane, which is how the box natively shows
            // hardware video. The previous DR-off + android_display vout path could
            // not colour-convert NV12 on this compositor and stayed GREEN (RV16/RV32
            // chroma overrides had ZERO effect -- the log still showed "output: 21
            // Biplanar", never converted). forceSoftware uses avcodec-hw=none so DR
            // is irrelevant there.
            // Cut decode load so cheap TV boxes keep up, WITHOUT wrecking image
            // quality. "skiploopfilter=all" dropped the H.264/H.265 deblocking
            // filter on EVERY frame, so block errors on reference frames piled up
            // and propagated -> visible macroblocking/"rain" breakup on some live
            // channels (audio stayed fine). "nonref" keeps deblocking on the
            // reference frames that matter (no error build-up) and only skips it
            // on disposable non-reference frames, so we still save CPU. This only
            // affects the software avcodec path; the HW decoder deblocks itself.
            "--avcodec-skiploopfilter=nonref",
            "--avcodec-fast",
            // Auto-reconnect when an HTTP segment/stream connection drops.
            "--http-reconnect",
            // Report KULULUPLAY instead of the default "VLC/3.0.x LibVLC/3.0.x".
            "--http-user-agent=${AppInfo.USER_AGENT}"
        )
        // Default = decode audio to PCM (no passthrough). Disable SPDIF so AC-3/
        // E-AC-3/DTS are software-decoded to stereo PCM that any HDMI sink plays.
        if (!allowPassthrough) options.add("--no-spdif")
        // Deinterlace ONLY on the software path. On the hardware path the Amlogic
        // decoder outputs OPAQUE MediaCodec buffers (VLC logs "output: 17 unknown")
        // and deinterlaces interlaced (1080i) content natively on its underlay
        // plane; a software deinterlace filter cannot touch opaque buffers, so it
        // stalls the pipeline ("dequeue_in timeout: no input available") = frozen
        // video. Forced-software decode produces raw I420 that bob CAN deinterlace.
        if (forceSoftware) {
            options.add("--deinterlace=1")
            options.add("--deinterlace-mode=bob")
        }

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
                    maybeFlagGreenProneProfile()
                }
                // Video output created => track dimensions/frame rate are populated;
                // the most reliable point to read the profile and route the bad one
                // to software before the green frame is all the user ever sees.
                MediaPlayer.Event.Vout -> maybeFlagGreenProneProfile()
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

    /**
     * On the hardware path, route the one Amlogic-green-prone profile — 1080p with
     * a high frame rate (1080p@50/60, the failing H.264 streams) — to software for
     * THIS stream only (onVideoInvalid -> controller restarts on VLC_SW). Read once
     * the video output exists so the track dimensions/frame rate are populated.
     * Keyed on resolution + frame rate (no codec gate: libVLC's track codec field
     * is not safely typed across versions, and the fps>=49 + 1080p rule already
     * isolates the failing profile). Normal channels — lower resolution, or ~25fps
     * interlaced 1080i — stay on hardware. No-op when already forceSoftware.
     */
    private fun maybeFlagGreenProneProfile() {
        if (forceSoftware || greenCheckDone) return
        val track = mediaPlayer?.currentVideoTrack ?: return
        val den = track.frameRateDen
        val fps = if (den > 0) track.frameRateNum.toFloat() / den else 0f
        val is1080p = track.height >= GREEN_PRONE_MIN_HEIGHT || track.width >= GREEN_PRONE_MIN_WIDTH
        if (is1080p && fps >= GREEN_PRONE_MIN_FPS) {
            greenCheckDone = true
            PlaybackLog.log(
                context, engineName,
                "green-prone HW profile ${track.width}x${track.height}@${fps}fps -> software fallback"
            )
            listener?.onVideoInvalid()
        }
    }

    override fun play(url: String) {
        val vlc = libVlc ?: return
        val mp = mediaPlayer ?: return
        greenCheckDone = false
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
            // Software-path deinterlace only (opaque HW buffers can't be filtered
            // and the Amlogic HW decoder deinterlaces 1080i natively).
            if (forceSoftware) {
                addOption(":deinterlace=1")
                addOption(":deinterlace-mode=bob")
            }
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

        // Green-prone Amlogic profile: 1080p at a high frame rate (1080p@50/60).
        // Broadcast 1080i reports ~25fps (field-coded) so the >=49 threshold keeps
        // it on hardware; only progressive 1080p50/60 trips the software fallback.
        private const val GREEN_PRONE_MIN_HEIGHT = 1080
        private const val GREEN_PRONE_MIN_WIDTH = 1920
        private const val GREEN_PRONE_MIN_FPS = 49f
    }
}
