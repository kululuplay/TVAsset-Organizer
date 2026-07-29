/*
 * VlcPlayerEngine.kt
 * Hardware/software-capable playback backend using libVLC. AUTO can route here
 * after a confirmed ExoPlayer failure; an explicit VLC selection stays here.
 * With software decode enabled, libVLC is also the broad codec/container safety
 * net for streams unsupported by a device MediaCodec.
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
 *   - forceSoftware: disable MediaCodec entirely when the decoder policy or
 *     evidence-based fallback selects VLC software.
 */
package com.iptv.player.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    private var videoOutputReported = false

    private val playbackFailureReported = AtomicBoolean(false)
    private val statsObserved = AtomicBoolean(false)
    private val decodedVideoObserved = AtomicBoolean(false)
    private val playingObserved = AtomicBoolean(false)
    private val bufferingActive = AtomicBoolean(true)
    private val voutObserved = AtomicBoolean(false)
    private val playbackHealth = VlcPlaybackHealth(softwareDecode = forceSoftware)
    private val healthHandler = Handler(Looper.getMainLooper())
    private val surfaceFrameHealth = SurfaceFrameHealthMonitor(healthHandler) {
        reportVideoInvalid("persistent solid-green SurfaceView output")
    }

    // Stored so they can be re-applied once playback (re)starts.
    private var audioDelayMs = 0L
    private var subtitleDelayMs = 0L

    // One-shot per stream so the decode-profile check fires at most once.
    // AtomicBoolean: maybeRouteByProfile() runs from BOTH the VLC native event
    // thread (Playing/Vout/Buffering) and the main thread (delayed re-checks), so
    // the claim must be a compare-and-set to avoid a double route under a race.
    private val profileCheckDone = AtomicBoolean(false)

    // Re-check scheduler. Resolution is often still 0x0 on the first Playing/Vout
    // event, so re-read briefly until the track is populated. The only proactive
    // route retained is software -> hardware for UHD, where software decode cannot
    // keep up. Device/profile guesses were removed because they made AUTO abandon
    // a healthy hardware path on the latest real-stick verification.
    private val profileHandler = Handler(Looper.getMainLooper())

    // Silent-audio self-heal. libVLC normally auto-selects an audio track, but a
    // stream can come up with audio present yet NO track selected (audioTrack ==
    // -1) -> picture, no sound, and libVLC raises no error for it. A short delay
    // after Playing we re-select the first real audio track. One-shot per stream.
    private val audioHandler = Handler(Looper.getMainLooper())
    private var audioHealAttempted = false

    private val healthRunnable = object : Runnable {
        override fun run() {
            if (pendingOps.get() > 0) {
                healthHandler.postDelayed(this, HEALTH_POLL_MS)
                return
            }
            val mp = mediaPlayer ?: return
            if (mp.currentVideoTrack != null) {
                readHealthSample(mp)?.let { sample ->
                    statsObserved.set(true)
                    if (sample.decodedVideo > 0L) decodedVideoObserved.set(true)
                    val decision = playbackHealth.evaluate(
                        sample = sample,
                        nowMs = SystemClock.elapsedRealtime(),
                        videoActivelyPlaying =
                            playingObserved.get() && !bufferingActive.get(),
                    )
                    if (decision.firstDisplayedFrame && !videoOutputReported) {
                        videoOutputReported = true
                        healthHandler.removeCallbacks(voutCompatibilityRunnable)
                        healthHandler.removeCallbacks(noDisplayedFrameRunnable)
                        healthHandler.removeCallbacks(noVideoPipelineRunnable)
                        PlaybackLog.log(context, engineName, "displayedPictures advanced -> real video output")
                        listener?.onVideoOutput()
                    }
                    when {
                        decision.videoFrozen && forceSoftware ->
                            reportSoftwareTooSlow("software video output froze while input/time advanced")
                        decision.videoFrozen ->
                            reportVideoInvalid("video output froze while input/time advanced")
                        decision.softwareTooSlow ->
                            reportSoftwareTooSlow("sustained lost-picture ratio above 25%")
                    }
                }
            }
            healthHandler.postDelayed(this, HEALTH_POLL_MS)
        }
    }

    // Very old/quirky libVLC builds may not expose media stats even with --stats.
    // Avoid leaving a healthy picture covered forever: green/profile checks get a
    // head start, then Vout is accepted only as a last-resort compatibility signal.
    private val voutCompatibilityRunnable = Runnable {
        if (
            !videoOutputReported &&
            !playbackFailureReported.get() &&
            mediaPlayer?.currentVideoTrack != null &&
            voutObserved.get() &&
            playingObserved.get() &&
            !bufferingActive.get() &&
            !statsObserved.get()
        ) {
            videoOutputReported = true
            PlaybackLog.log(context, engineName, "stats unavailable after Vout grace -> accept output")
            listener?.onVideoOutput()
        }
    }

    // When stats are available, Vout is not enough: require displayedPictures to
    // advance. This catches a decoder that creates an output surface and advances
    // audio/time but never produces even one video frame.
    private val noDisplayedFrameRunnable = Runnable {
        if (
            !videoOutputReported &&
            !playbackFailureReported.get() &&
            statsObserved.get() &&
            decodedVideoObserved.get() &&
            playingObserved.get() &&
            !bufferingActive.get() &&
            mediaPlayer?.currentVideoTrack != null
        ) {
            reportVideoInvalid("decoded video never reached the output surface")
        }
    }

    // A stream may emit Playing/Vout before any video data is actually decoded.
    // Give slow IPTV sources the full extended deadline; if a declared video
    // track still has no pipeline, classify this as STARTUP rather than green
    // video so the controller can try one bounded compatibility route.
    private val noVideoPipelineRunnable = Runnable {
        if (
            !videoOutputReported &&
            !playbackFailureReported.get() &&
            playingObserved.get() &&
            !bufferingActive.get() &&
            mediaPlayer?.currentVideoTrack != null &&
            !decodedVideoObserved.get()
        ) {
            reportStartupFailure("video pipeline produced no decoded pictures")
        }
    }

    // libVLC's audio timestamp conversion has a fixed ~3s bound. A network/live
    // caching above that makes the audio decoder request frames further ahead
    // than the bound, so it drops EVERY audio frame for the first several seconds
    // of a channel ("Timestamp conversion failed (delay 5000000 ... bound
    // 3000000)"). Cap the VLC live buffer at the bound; larger user "Buffer size"
    // values still apply to the ExoPlayer path, which has no such limit.
    private val vlcCachingMs: Int = networkCachingMs.coerceAtMost(MAX_VLC_LIVE_CACHING_MS)

    // ANR fix: MediaPlayer.stop() is SYNCHRONOUS and can block for seconds on a
    // stalled network, so every blocking libVLC call (stop / stop+setMedia+play /
    // release) runs on the shared VlcOps thread instead of main. Two guards:
    //  - opsSeq: bumped by each play()/stop()/release() on the main thread; a
    //    queued runnable no-ops when its captured seq is no longer current, so a
    //    fast zap doesn't serially execute N blocking stop+play cycles (the
    //    newest command's own stop() upholds stop-before-start).
    //  - pendingOps: >0 while a stop/play command is queued or executing; the
    //    event listener drops events during that window because they belong to
    //    the PREVIOUS media (with async stop, a stale EncounteredError from the
    //    old stalled stream could otherwise fire the controller's failure ladder
    //    for the channel the user already left).
    private val opsSeq = AtomicLong(0)
    private val pendingOps = AtomicInteger(0)
    // libVLC callbacks belong to the MediaPlayer, not to a specific Media. Bind
    // them to the current MediaChanged boundary so a late error from the channel
    // being stopped cannot fail the newly-zapped channel.
    private val eventGenerationGate = VlcEventGenerationGate()

    override fun bind(container: ViewGroup) {
        // Keep VLC close to its proven defaults. The old global clock overrides,
        // unsafe avcodec-fast mode, skipped loop filter and forced bob
        // deinterlacing caused timestamp stalls, macroblocking and CPU overload
        // on real live MPEG-TS channels.
        val options = arrayListOf(
            "--network-caching=$vlcCachingMs",
            "--live-caching=$vlcCachingMs",
            "--stats",
            // Auto-reconnect when an HTTP segment/stream connection drops.
            "--http-reconnect",
            // Report KULULUPLAY instead of the default "VLC/3.0.x LibVLC/3.0.x".
            "--http-user-agent=${AppInfo.USER_AGENT}"
        )
        if (forceSoftware) options.add("--avcodec-hw=none")
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
            // MediaChanged is the only reliable native boundary between two
            // streams on a reused MediaPlayer. Process it even while the queued
            // setMedia/play operation is still marked pending.
            if (event.type == MediaPlayer.Event.MediaChanged) {
                eventGenerationGate.onMediaChanged()
                return@setEventListener
            }
            // A queued stop/play is pending on the VLC ops thread: these events
            // are from the PREVIOUS media — drop them (see pendingOps note).
            if (pendingOps.get() > 0) return@setEventListener
            if (!eventGenerationGate.acceptsCurrentMediaEvent()) {
                return@setEventListener
            }
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    // VLC keeps firing Buffering during normal playback (cache
                    // top-ups), ending each cycle at 100%. Only treat <100% as
                    // actual buffering; 100% means the stream is running, so hide
                    // the spinner — otherwise it stays on screen forever.
                    if (event.buffering < 100f) {
                        bufferingActive.set(true)
                        healthHandler.removeCallbacks(voutCompatibilityRunnable)
                        healthHandler.removeCallbacks(noDisplayedFrameRunnable)
                        healthHandler.removeCallbacks(noVideoPipelineRunnable)
                        listener?.onBuffering()
                    } else {
                        val firstPlaying = playingObserved.compareAndSet(false, true)
                        val resumedFromBuffering = bufferingActive.getAndSet(false)
                        listener?.onPlaying()
                        // Long-tail safety net: each cache top-up gives another
                        // chance to read a now-populated frame rate, in case the
                        // delayed re-checks all fired before fps was known.
                        maybeRouteByProfile()
                        if (firstPlaying || resumedFromBuffering) {
                            scheduleHealthChecks()
                            scheduleOutputTimeouts()
                            scheduleVoutCompatibility()
                        }
                    }
                }
                MediaPlayer.Event.Playing -> {
                    val firstPlaying = playingObserved.compareAndSet(false, true)
                    val resumedFromBuffering = bufferingActive.getAndSet(false)
                    // VLC only accepts delays once the track is running.
                    applyDelays()
                    listener?.onPlaying()
                    maybeRouteByProfile()
                    scheduleProfileRechecks()
                    scheduleAudioCheck()
                    scheduleHealthChecks()
                    if (firstPlaying || resumedFromBuffering) {
                        scheduleOutputTimeouts()
                        scheduleVoutCompatibility()
                    }
                }
                // Vout means the surface/output was created, NOT that a healthy
                // picture was displayed. Stats confirms displayedPictures before
                // onVideoOutput is emitted; PixelCopy separately rejects green.
                MediaPlayer.Event.Vout -> {
                    // Vout(0) means the video surface was removed, not created.
                    if (event.voutCount <= 0) return@setEventListener
                    voutObserved.set(true)
                    maybeRouteByProfile()
                    scheduleProfileRechecks()
                    surfaceFrameHealth.start { findVideoSurface(videoLayout) }
                    scheduleHealthChecks(immediate = true)
                    scheduleVoutCompatibility()
                    scheduleOutputTimeouts()
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
     * Narrow profile routing retained for API 21-23 where PixelCopy cannot inspect
     * the actual frame. On newer devices, hardware video is judged from its real
     * pixels so a healthy 1080p50 stream is never needlessly moved to software.
     * Software UHD overload is still handled on every Android version.
     */
    private fun maybeRouteByProfile() {
        if (profileCheckDone.get()) return
        val track = mediaPlayer?.currentVideoTrack ?: return
        // Dimensions are frequently still 0x0 on the first Playing/Vout event for
        // live TS. Bail without claiming so the rechecks can identify a real UHD
        // stream before deciding whether a software path is viable.
        if (track.width <= 0 || track.height <= 0) return
        val isUhd = track.height >= UHD_MIN_HEIGHT || track.width >= UHD_MIN_WIDTH

        if (forceSoftware) {
            if (isUhd && claimRouting()) {
                reportSoftwareTooSlow(
                    "UHD ${track.width}x${track.height} cannot sustain software decode",
                )
            } else if (!isUhd) {
                claimRouting()
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            claimRouting()
            return
        }

        val den = track.frameRateDen
        val fps = if (den > 0) track.frameRateNum.toFloat() / den else 0f
        if (fps <= 0f) return // live TS metadata may populate on a later re-check
        val codec = codecLabel(track.codec)?.lowercase()
        // Some older libVLC builds expose no fourcc here. Keep the narrow
        // resolution/fps guard in that case rather than silently missing the
        // known API21-23 green profile.
        val isAvc = codec == null || codec == "h264" || codec == "avc1" || codec == "avc"
        val is1080Class = !isUhd &&
            (track.height >= GREEN_PRONE_MIN_HEIGHT || track.width >= GREEN_PRONE_MIN_WIDTH)
        if (isAvc && is1080Class && fps >= GREEN_PRONE_MIN_FPS && claimRouting()) {
            reportVideoInvalid(
                "green-prone AVC ${track.width}x${track.height}@${fps}fps hardware profile",
            )
        } else {
            claimRouting()
        }
    }

    /**
     * One-shot claim: wins the race exactly once (compare-and-set), then drops any
     * pending re-checks. Returns false if another thread already routed, so the
     * caller must NOT emit a duplicate fallback.
     */
    private fun claimRouting(): Boolean {
        if (!profileCheckDone.compareAndSet(false, true)) return false
        profileHandler.removeCallbacksAndMessages(null)
        return true
    }

    /**
     * Re-run the profile check briefly after playback starts because VLC often
     * reports 0x0 dimensions on the first Playing/Vout event. Cheap and
     * self-cancelling once [profileCheckDone] is claimed.
     */
    private fun scheduleProfileRechecks() {
        if (profileCheckDone.get()) return
        profileHandler.removeCallbacksAndMessages(null)
        for (delay in PROFILE_RECHECK_DELAYS_MS) {
            profileHandler.postDelayed({ maybeRouteByProfile() }, delay)
        }
    }

    private fun scheduleHealthChecks(immediate: Boolean = false) {
        healthHandler.removeCallbacks(healthRunnable)
        healthHandler.postDelayed(healthRunnable, if (immediate) 0L else HEALTH_POLL_MS)
    }

    private fun scheduleOutputTimeouts() {
        healthHandler.removeCallbacks(noDisplayedFrameRunnable)
        healthHandler.postDelayed(
            noDisplayedFrameRunnable,
            NO_DISPLAYED_FRAME_TIMEOUT_MS,
        )
        healthHandler.removeCallbacks(noVideoPipelineRunnable)
        healthHandler.postDelayed(
            noVideoPipelineRunnable,
            NO_VIDEO_PIPELINE_TIMEOUT_MS,
        )
    }

    private fun scheduleVoutCompatibility() {
        if (!voutObserved.get()) return
        healthHandler.removeCallbacks(voutCompatibilityRunnable)
        healthHandler.postDelayed(voutCompatibilityRunnable, VOUT_STATS_GRACE_MS)
    }

    /**
     * Get a retained snapshot of the currently-playing Media, read its counters,
     * then release that retained reference as required by libVLC.
     */
    private fun readHealthSample(mp: MediaPlayer): VlcPlaybackHealth.Sample? {
        val media = runCatching { mp.media }.getOrNull() ?: return null
        return try {
            val stats = runCatching { media.stats }.getOrNull() ?: return null
            VlcPlaybackHealth.Sample(
                readBytes = maxOf(stats.readBytes, stats.demuxReadBytes).toLong().coerceAtLeast(0L),
                decodedVideo = stats.decodedVideo.toLong().coerceAtLeast(0L),
                displayedPictures = stats.displayedPictures.toLong().coerceAtLeast(0L),
                lostPictures = stats.lostPictures.toLong().coerceAtLeast(0L),
                playbackTimeMs = mp.time.coerceAtLeast(0L),
            )
        } finally {
            media.release()
        }
    }

    private fun reportVideoInvalid(detail: String) {
        if (!playbackFailureReported.compareAndSet(false, true)) return
        healthHandler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        PlaybackLog.log(context, engineName, "$detail -> software compatibility fallback")
        listener?.onVideoInvalid()
    }

    private fun reportSoftwareTooSlow(detail: String) {
        if (!playbackFailureReported.compareAndSet(false, true)) return
        healthHandler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        PlaybackLog.log(context, engineName, "$detail -> hardware recovery")
        listener?.onSoftwareTooSlow()
    }

    private fun reportStartupFailure(detail: String) {
        if (!playbackFailureReported.compareAndSet(false, true)) return
        healthHandler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        PlaybackLog.log(context, engineName, "$detail -> startup compatibility route")
        listener?.onStartupFailure(detail)
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

    // reset is unused for libVLC: a Media can't be reused, so play() always builds
    // a fresh Media and stop()s first regardless (single-connection contract).
    override fun play(url: String, reset: Boolean) {
        val vlc = libVlc ?: return
        val mp = mediaPlayer ?: return
        profileCheckDone.set(false)
        profileHandler.removeCallbacksAndMessages(null)
        playbackFailureReported.set(false)
        statsObserved.set(false)
        decodedVideoObserved.set(false)
        playingObserved.set(false)
        bufferingActive.set(true)
        voutObserved.set(false)
        playbackHealth.reset()
        healthHandler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        videoOutputReported = false
        val mediaGeneration = eventGenerationGate.beginPlay()
        // Reset the silent-audio guard on the main thread, then hand the blocking
        // stop -> setMedia -> play sequence to the VLC ops thread as ONE runnable
        // (FIFO keeps stop-before-start; the main thread never blocks on a stalled
        // network). play() is also the fast-zap reuse path — the controller keeps
        // the engine alive across same-stage channel changes — so the previous
        // media must stop before the next starts (single-connection contract).
        // On a freshly-bound engine that stop() is a harmless no-op.
        audioHealAttempted = false
        audioHandler.removeCallbacksAndMessages(null)
        PlaybackLog.log(context, engineName, "play forceSoftware=$forceSoftware passthrough=$allowPassthrough")
        val mySeq = opsSeq.incrementAndGet()
        pendingOps.incrementAndGet()
        VlcOps.post {
            try {
                // Superseded by a newer play/stop while queued -> skip entirely
                // (the newer command's own stop() covers single-connection).
                if (opsSeq.get() != mySeq) return@post
                mp.stop()
                // Re-check after the (possibly long) stop: don't start a channel
                // the user has already zapped away from.
                if (opsSeq.get() != mySeq) return@post
                val media = Media(vlc, android.net.Uri.parse(url)).apply {
                    // Hardware is preferred but never forced past libVLC's
                    // per-device safety list. The old force=true made AUTO and
                    // Hardware identical and enabled broken MediaCodec paths.
                    setHWDecoderEnabled(!forceSoftware, false)
                    // Mirror the instance buffer tuning at the stream level.
                    addOption(":network-caching=$vlcCachingMs")
                    addOption(":live-caching=$vlcCachingMs")
                    if (forceSoftware) addOption(":avcodec-hw=none")
                    if (!allowPassthrough) {
                        addOption(":no-spdif")
                        // Force a 5.1 -> 2.0 stereo downmix (mirrors the instance
                        // option) so boxes that can't open a 6-channel PCM
                        // AudioTrack still get sound.
                        addOption(":stereo-mode=1")
                    }
                    // Auto-reconnect dropped HTTP connections instead of erroring out.
                    addOption(":http-reconnect")
                    // Per-stream User-Agent override (covers playlist + segments).
                    addOption(":http-user-agent=${AppInfo.USER_AGENT}")
                }
                try {
                    if (!eventGenerationGate.prepareMediaChange(mediaGeneration)) {
                        return@post
                    }
                    try {
                        mp.media = media
                    } catch (error: Throwable) {
                        eventGenerationGate.cancelPreparedMediaChange(mediaGeneration)
                        throw error
                    }
                } finally {
                    media.release()
                }
                // The user may have zapped again while native setMedia was
                // running. Never start the superseded channel; its callbacks are
                // already rejected by the generation gate.
                if (
                    opsSeq.get() != mySeq ||
                    !eventGenerationGate.isActive(mediaGeneration)
                ) return@post
                mp.play()
            } finally {
                pendingOps.decrementAndGet()
            }
        }
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

    // libVLC's playback clock (ms). Advances while frames flow (including from
    // cache during a top-up); freezes once a silent network stall drains the
    // buffer — exactly the signal the controller's stall watchdog keys on.
    override fun playbackPositionMs(): Long = mediaPlayer?.time ?: -1L

    override fun pause() {
        val mp = mediaPlayer ?: return
        VlcOps.post { mp.pause() }
    }

    override fun resume() {
        val mp = mediaPlayer ?: return
        VlcOps.post { mp.play() }
    }

    override fun stop() {
        profileHandler.removeCallbacksAndMessages(null)
        audioHandler.removeCallbacksAndMessages(null)
        healthHandler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        eventGenerationGate.invalidate()
        val mp = mediaPlayer ?: return
        val mySeq = opsSeq.incrementAndGet()
        pendingOps.incrementAndGet()
        VlcOps.post {
            try {
                // A newer play/stop already queued -> its own stop supersedes this.
                if (opsSeq.get() != mySeq) return@post
                mp.stop()
            } finally {
                pendingOps.decrementAndGet()
            }
        }
    }

    override fun release() {
        profileHandler.removeCallbacksAndMessages(null)
        audioHandler.removeCallbacksAndMessages(null)
        healthHandler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        eventGenerationGate.invalidate()
        // Main thread: cut events + detach the view surface, then hand the
        // blocking native teardown to the VLC ops thread. Capturing locals and
        // nulling the fields first means nothing else can touch this player, and
        // stale queued plays no-op via the seq bump. Stopping with the vout
        // already detached is fine (detachVideo() does the same on a live player).
        val mp = mediaPlayer
        val vlc = libVlc
        mp?.setEventListener(null)
        mp?.detachViews()
        mediaPlayer = null
        libVlc = null
        videoLayout = null
        listener = null
        opsSeq.incrementAndGet()
        if (mp != null || vlc != null) {
            VlcOps.post {
                mp?.stop()
                mp?.release()
                vlc?.release()
            }
        }
    }

    override fun setListener(listener: PlayerListener?) {
        this.listener = listener
    }

    /**
     * Read the live video track for the diagnostics overlay. width/height and the
     * frame-rate num/den are proven-safe across libVLC versions (already used by
     * the resolution profile check). codec is read through an Any?-typed helper
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

        private const val HEALTH_POLL_MS = 1500L
        private const val VOUT_STATS_GRACE_MS = 4500L
        private const val NO_DISPLAYED_FRAME_TIMEOUT_MS = 12_000L
        private const val NO_VIDEO_PIPELINE_TIMEOUT_MS = 20_000L

        private const val GREEN_PRONE_MIN_HEIGHT = 1080
        private const val GREEN_PRONE_MIN_WIDTH = 1920
        private const val GREEN_PRONE_MIN_FPS = 49f
    }
}
