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
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicBoolean
import com.iptv.player.util.AppInfo
import com.iptv.player.util.DeviceCaps
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
    private val allowPassthrough: Boolean = false,
    /** Network/live caching in ms (user "Buffer size" setting). */
    private val networkCachingMs: Int = 3000
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
    // AtomicBoolean: maybeRouteByProfile() runs from BOTH the VLC native event
    // thread (Playing/Vout/Buffering) and the main thread (delayed re-checks), so
    // the claim must be a compare-and-set to avoid a double fallback under a race.
    private val greenCheckDone = AtomicBoolean(false)

    // Re-check scheduler. The green-prone routing keys on the track frame rate,
    // but for live TS streams VLC often reports frameRateDen=0 (fps unknown) on
    // the first Playing/Vout event, so a 1080p50 stream wrongly looks like a
    // normal channel and stays on the green-prone Amlogic hardware decoder. The
    // frame rate populates a moment later (the diagnostics overlay proves it), so
    // we re-run the check a few times after start until fps is known.
    private val profileHandler = Handler(Looper.getMainLooper())

    // Silent-audio self-heal. libVLC normally auto-selects an audio track, but a
    // stream can come up with audio present yet NO track selected (audioTrack ==
    // -1) -> picture, no sound, and libVLC raises no error for it. A short delay
    // after Playing we re-select the first real audio track. One-shot per stream.
    private val audioHandler = Handler(Looper.getMainLooper())
    private var audioHealAttempted = false

    // libVLC's audio timestamp conversion has a fixed ~3s bound. A network/live
    // caching above that makes the audio decoder request frames further ahead
    // than the bound, so it drops EVERY audio frame for the first several seconds
    // of a channel ("Timestamp conversion failed (delay 5000000 ... bound
    // 3000000)"). Cap the VLC live buffer at the bound; larger user "Buffer size"
    // values still apply to the ExoPlayer path, which has no such limit.
    private val vlcCachingMs: Int = networkCachingMs.coerceAtMost(MAX_VLC_LIVE_CACHING_MS)

    override fun bind(container: ViewGroup) {
        // Tuned for smooth IPTV on Android TV: a generous network buffer to
        // absorb jitter, plus options that keep playback real-time on weak
        // hardware instead of accumulating delay (the main cause of stutter).
        val options = arrayListOf(
            // Bigger live buffer soaks up network hiccups before they freeze.
            "--network-caching=$vlcCachingMs",
            "--live-caching=$vlcCachingMs",
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
        // E-AC-3/DTS are software-decoded to PCM that any HDMI sink plays, and
        // force a STEREO downmix so the output is 2.0.
        //
        // Field log (Amlogic box, 4K HEVC channel): the audio was 5.1 (6-channel,
        // AudioTrack channelMask 0x3f). The AudioTrack output could not open a
        // 6-channel PCM track ("too low audio sample frequency (0)" then "module
        // not functional"), so the 4K video played but there was NO sound. libVLC
        // raises no error for an audio-only output failure, so the controller's
        // fallback ladder never fires and the stream stays silent. Requesting
        // stereo makes libVLC downmix 5.1 -> 2.0 PCM that the box's AudioTrack
        // accepts; genuinely-stereo channels are unaffected (already 2.0).
        if (!allowPassthrough) {
            options.add("--no-spdif")
            options.add("--stereo-mode=1")
        }
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
                    else {
                        listener?.onPlaying()
                        // Long-tail safety net: each cache top-up gives another
                        // chance to read a now-populated frame rate, in case the
                        // delayed re-checks all fired before fps was known.
                        maybeRouteByProfile()
                    }
                }
                MediaPlayer.Event.Playing -> {
                    // VLC only accepts delays once the track is running.
                    applyDelays()
                    listener?.onPlaying()
                    maybeRouteByProfile()
                    scheduleProfileRechecks()
                    scheduleAudioCheck()
                }
                // Video output created => track dimensions/frame rate are populated;
                // the most reliable point to read the profile and route the bad one
                // to software before the green frame is all the user ever sees.
                MediaPlayer.Event.Vout -> {
                    maybeRouteByProfile()
                    scheduleProfileRechecks()
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

    /**
     * Resolution-aware decode routing, evaluated once the video output exists (so
     * track dimensions/frame rate are populated). Two opposite moves:
     *
     *  - Software path + UHD/4K (≥1440p): no TV-box CPU can software-decode 4K at
     *    80–100 Mbps in real time, so it stutters. Escalate THIS stream to the
     *    hardware decoder (onSoftwareTooSlow -> controller restarts on VLC_HW).
     *  - Hardware path + the Amlogic-green-prone profile — 1080p-class at a high
     *    frame rate (1080p@50/60 H.264) but NOT UHD — drop to software for THIS
     *    stream only (onVideoInvalid -> controller restarts on VLC_SW). 4K is
     *    excluded so true UHD keeps the hardware path it needs.
     *
     * Keyed on resolution + frame rate (no codec gate: libVLC's track codec field
     * is not safely typed across versions). Normal channels — lower resolution, or
     * ~25fps interlaced 1080i — stay where they are.
     */
    private fun maybeRouteByProfile() {
        if (greenCheckDone.get()) return
        val track = mediaPlayer?.currentVideoTrack ?: return
        // Dimensions are frequently still 0x0 on the first Playing/Vout event for
        // live TS (and at startup before the video track is parsed). Routing on
        // 0x0 is harmful: isUhd is false, so on Amlogic (greenProne = !isUhd) it
        // fires a premature software fallback — which for a real 4K HEVC stream is
        // unplayable. Bail without claiming so the rechecks re-run once the true
        // resolution is known (4K -> isUhd -> stays on hardware).
        if (track.width <= 0 || track.height <= 0) return
        val den = track.frameRateDen
        val fps = if (den > 0) track.frameRateNum.toFloat() / den else 0f
        val isUhd = track.height >= UHD_MIN_HEIGHT || track.width >= UHD_MIN_WIDTH

        if (forceSoftware) {
            // 4K cannot be software-decoded in real time -> escalate to hardware.
            if (isUhd && claimRouting()) {
                PlaybackLog.log(
                    context, engineName,
                    "UHD ${track.width}x${track.height} on software -> hardware escalation"
                )
                listener?.onSoftwareTooSlow()
            }
            return
        }

        // Hardware path: 4K must always stay on hardware (software can't keep up).
        // Below 4K, what counts as green-prone depends on the box:
        //   - Amlogic: the libVLC hardware underlay greens on EVERY non-UHD profile
        //     (1080i@25, 576p, 1080p@50…) through the box compositor — confirmed in
        //     the field — and the failure is a display-plane issue with no runtime
        //     signal, so we treat the whole non-UHD hardware path as green-prone and
        //     drop to software (where avcodec-hw=none renders real, displayable
        //     frames). ExoPlayer remains the box's working hardware-video path.
        //   - Other boxes: only the classic 1080p@50/60 high-bitrate H.264 profile
        //     trips the hardware decoder, so keep the narrow heuristic there.
        val greenProneProfile = if (DeviceCaps.isAmlogic) {
            !isUhd
        } else {
            !isUhd &&
                (track.height >= GREEN_PRONE_MIN_HEIGHT || track.width >= GREEN_PRONE_MIN_WIDTH) &&
                fps >= GREEN_PRONE_MIN_FPS
        }
        if (greenProneProfile && claimRouting()) {
            PlaybackLog.log(
                context, engineName,
                "green-prone HW profile ${track.width}x${track.height}@${fps}fps -> software fallback"
            )
            listener?.onVideoInvalid()
        }
    }

    /**
     * One-shot claim: wins the race exactly once (compare-and-set), then drops any
     * pending re-checks. Returns false if another thread already routed, so the
     * caller must NOT emit a duplicate fallback.
     */
    private fun claimRouting(): Boolean {
        if (!greenCheckDone.compareAndSet(false, true)) return false
        profileHandler.removeCallbacksAndMessages(null)
        return true
    }

    /**
     * Re-run the profile check a few times after playback starts. The frame rate
     * is frequently still unknown (frameRateDen=0) on the first Playing/Vout for
     * live TS, so a single early read misses 1080p50/60 and leaves it greening on
     * the hardware decoder. Cheap and self-cancelling: maybeRouteByProfile()
     * returns immediately once greenCheckDone is set.
     */
    private fun scheduleProfileRechecks() {
        if (greenCheckDone.get()) return
        profileHandler.removeCallbacksAndMessages(null)
        for (delay in PROFILE_RECHECK_DELAYS_MS) {
            profileHandler.postDelayed({ maybeRouteByProfile() }, delay)
        }
    }

    /**
     * Detach the running player from its VLCVideoLayout and remove that layout
     * from its parent, leaving playback (audio + decode) running. libVLC requires
     * detachViews() before the views can be re-homed. The same layout instance is
     * reused on re-attach so no new surface plumbing is created.
     */
    override fun detachVideo() {
        mediaPlayer?.detachViews()
        videoLayout?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    /**
     * Re-attach the existing VLCVideoLayout into [container] and re-wire libVLC's
     * AWindow handler, without touching the Media/playback. Called after
     * [detachVideo] (the controller inserts a short gap first so the old
     * SurfaceView is torn down before the new one is composited — the Amlogic
     * green-on-fresh-surface lesson).
     */
    override fun attachVideo(container: ViewGroup) {
        val mp = mediaPlayer ?: return
        val layout = videoLayout ?: return
        (layout.parent as? ViewGroup)?.removeView(layout)
        container.addView(layout)
        mp.attachViews(layout, null, false, false)
    }

    override fun play(url: String) {
        val vlc = libVlc ?: return
        val mp = mediaPlayer ?: return
        greenCheckDone.set(false)
        profileHandler.removeCallbacksAndMessages(null)
        // Reset the silent-audio guard and stop any stream still running on this
        // engine. play() is now also the fast-zap reuse path — the controller keeps
        // the engine alive across same-stage channel changes — so we must stop the
        // previous media before swapping in the next (single-connection contract:
        // one stream at a time, stop before start). On a freshly-bound engine this
        // stop() is a harmless no-op.
        audioHealAttempted = false
        audioHandler.removeCallbacksAndMessages(null)
        mp.stop()
        PlaybackLog.log(context, engineName, "play forceSoftware=$forceSoftware passthrough=$allowPassthrough")
        val media = Media(vlc, android.net.Uri.parse(url)).apply {
            // Hardware decoding unless software was forced (then both off).
            setHWDecoderEnabled(!forceSoftware, !forceSoftware)
            // Mirror the instance buffer/jitter tuning at the stream level.
            addOption(":network-caching=$vlcCachingMs")
            addOption(":live-caching=$vlcCachingMs")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            if (forceSoftware) addOption(":avcodec-hw=none")
            if (!allowPassthrough) {
                addOption(":no-spdif")
                // Force a 5.1 -> 2.0 stereo downmix (mirrors the instance option) so
                // boxes that can't open a 6-channel PCM AudioTrack still get sound.
                addOption(":stereo-mode=1")
            }
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

    /**
     * A short delay after playback starts, verify an audio track is actually
     * selected. If the stream carries audio but none is selected (audioTrack ==
     * -1), re-select the first real track so sound comes through. This is an
     * in-engine self-heal: it never swaps engines, so it can't trade silence for
     * a green screen on boxes where ExoPlayer can't show the underlay video. (The
     * common 5.1-too-many-channels silence is handled separately by the forced
     * stereo downmix in bind()/play().)
     */
    private fun scheduleAudioCheck() {
        if (audioHealAttempted) return
        audioHandler.removeCallbacksAndMessages(null)
        audioHandler.postDelayed({ healSilentAudio() }, AUDIO_CHECK_DELAY_MS)
    }

    private fun healSilentAudio() {
        if (audioHealAttempted) return
        val mp = mediaPlayer ?: return
        val firstReal = mp.audioTracks?.firstOrNull { it.id != -1 } ?: return
        if (mp.audioTrack == -1) {
            audioHealAttempted = true
            PlaybackLog.log(context, engineName, "audio present but unselected -> selecting track ${firstReal.id}")
            mp.audioTrack = firstReal.id
        }
    }

    override fun pause() { mediaPlayer?.pause() }
    override fun resume() { mediaPlayer?.play() }
    override fun stop() {
        profileHandler.removeCallbacksAndMessages(null)
        audioHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.stop()
    }

    override fun release() {
        profileHandler.removeCallbacksAndMessages(null)
        audioHandler.removeCallbacksAndMessages(null)
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

    /**
     * Read the live video track for the diagnostics overlay. width/height and the
     * frame-rate num/den are proven-safe across libVLC versions (already used by
     * the green-prone profile check). codec is read through an Any?-typed helper
     * so it compiles whether libVLC exposes it as an Int fourcc or a String;
     * bitrate (bits/s) is converted to kbps. Null fields render as "—".
     */
    override fun getStreamInfo(): StreamInfo? {
        val track = mediaPlayer?.currentVideoTrack ?: return null
        val den = track.frameRateDen
        val fps = if (den > 0) track.frameRateNum.toFloat() / den else 0f
        val bitrateKbps = track.bitrate.takeIf { it > 0 }?.let { it / 1000 }
        return StreamInfo(
            width = track.width,
            height = track.height,
            fps = fps,
            codec = codecLabel(track.codec),
            bitrateKbps = bitrateKbps,
            engine = engineName
        )
    }

    /**
     * libVLC's track codec field is not consistently typed across versions (an
     * Int fourcc on some, a String on others). Accept Any? so this compiles
     * regardless and decode at runtime: a fourcc Int -> its 4 ASCII chars.
     */
    private fun codecLabel(codec: Any?): String? = when (codec) {
        is String -> codec.trim().takeIf { it.isNotBlank() }
        is Int -> fourccToString(codec)
        else -> null
    }

    private fun fourccToString(fourcc: Int): String? {
        if (fourcc == 0) return null
        val chars = CharArray(4) { i -> ((fourcc shr (i * 8)) and 0xFF).toChar() }
        return String(chars).trim().takeIf { it.isNotBlank() }?.uppercase()
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
        // Green-prone Amlogic profile: 1080p at a high frame rate (1080p@50/60).
        // Broadcast 1080i reports ~25fps (field-coded) so the >=49 threshold keeps
        // it on hardware; only progressive 1080p50/60 trips the software fallback.
        private const val GREEN_PRONE_MIN_HEIGHT = 1080
        private const val GREEN_PRONE_MIN_WIDTH = 1920
        private const val GREEN_PRONE_MIN_FPS = 49f

        // UHD/4K threshold (QHD 1440p and up). Above this, software decode can't
        // keep up with 80–100 Mbps streams, so the hardware decoder is mandatory.
        private const val UHD_MIN_HEIGHT = 1440
        private const val UHD_MIN_WIDTH = 2560

        // Delays (ms after Playing/Vout) for re-reading the track frame rate. Live
        // TS often reports fps=0 on the first event, so we re-check until it
        // populates; the Buffering(100%) re-check covers any longer tail.
        private val PROFILE_RECHECK_DELAYS_MS = longArrayOf(700L, 1500L, 3000L, 5000L)

        // Delay after Playing before checking that an audio track is selected.
        private const val AUDIO_CHECK_DELAY_MS = 1500L

        // libVLC's audio timestamp conversion bound (~3s). Live network/live
        // caching must stay at or below this or the audio decoder drops frames
        // for the first several seconds of every channel.
        private const val MAX_VLC_LIVE_CACHING_MS = 3000
    }
}
