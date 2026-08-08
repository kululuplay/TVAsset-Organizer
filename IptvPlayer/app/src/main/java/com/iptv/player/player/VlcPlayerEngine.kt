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
 *     VLC's own adaptive deinterlacing is retained; forcing bob doubled work and
 *     produced pixelation/freezes on weaker sticks.
 *   - forceSoftware: disable MediaCodec entirely when the decoder policy or
 *     evidence-based fallback selects VLC software.
 */
package com.iptv.player.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    // Desired host, not necessarily the layout's current parent while a native
    // operation owns the surface. Null means detach/remove when native becomes
    // idle. The generation makes delayed replacement attaches re-read the latest
    // preview/fullscreen destination instead of resurrecting a stale container.
    private var videoHost: ViewGroup? = null
    private var videoHostGeneration = 0L
    private var viewsAttached = false
    private var hostMovePending = false
    private var playerHasMedia = false
    private var listener: PlayerListener? = null
    private var videoOutputReported = false
    private var nativeVideoOutputObserved = false
    private var nativeOutputHasFrameEvidence = false
    private var surfaceHealthyObserved = false

    private val playbackFailureReported = AtomicBoolean(false)
    // Once a bounded JNI operation times out, its MediaPlayer/LibVLC handles are
    // quarantined. The retired worker remains their sole owner until that call
    // unwinds; no fresh worker may touch/release the same native objects.
    private val nativeHandlesAbandoned = AtomicBoolean(false)
    // A non-null stats object is not sufficient: affected libVLC/device builds
    // expose a stats object whose video counters remain permanently zero. Only
    // a real video counter signal makes stats authoritative for output health.
    private val videoStatsUsable = AtomicBoolean(false)
    private val decodedVideoObserved = AtomicBoolean(false)
    private val playingObserved = AtomicBoolean(false)
    private val bufferingActive = AtomicBoolean(true)
    private val voutObserved = AtomicBoolean(false)
    private val playbackHealth = VlcPlaybackHealth()
    private val healthHandler = Handler(Looper.getMainLooper())
    // Command continuations must survive health resets. Clearing healthHandler is
    // expected on every zap; using it for cleanup->attach accounting leaked
    // pendingOps and disabled all later health checks.
    private val commandHandler = Handler(Looper.getMainLooper())
    private val surfaceFrameHealth = SurfaceFrameHealthMonitor(
        handler = healthHandler,
        onSolidGreen = {
            reportVideoInvalid("persistent solid-green SurfaceView output")
        },
        onPersistentBlank = {
            reportVideoInvalid("persistent blank SurfaceView output")
        },
        onHealthyFrame = {
            surfaceHealthyObserved = true
            maybeReportVideoOutput()
        },
        onSamplingUnavailable = {
            if (
                nativeVideoOutputObserved &&
                nativeOutputHasFrameEvidence &&
                !videoOutputReported &&
                !playbackFailureReported.get()
            ) {
                surfaceHealthyObserved = true
                PlaybackLog.log(
                    context,
                    engineName,
                    "PixelCopy capability unavailable -> accept libVLC displayed-picture signal",
                )
                maybeReportVideoOutput()
            }
        },
        continueAfterHealthy = !forceSoftware,
    )
    private val surfaceValidationDeadlineRunnable = Runnable {
        if (
            nativeVideoOutputObserved &&
            !videoOutputReported &&
            !playbackFailureReported.get()
        ) {
            reportVideoInvalid("video surface could not be validated before deadline")
        }
    }

    // Stored so they can be re-applied once playback (re)starts.
    private var audioDelayMs = 0L
    private var subtitleDelayMs = 0L
    private var preferredAudioLanguage: String? = null
    private var subtitlePreference: LiveSubtitlePreference = LiveSubtitlePreference.Auto

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
            // A live TS video track can replace placeholder dimensions long
            // after Playing/Vout. Keep checking until software is either proven
            // UHD-incompatible or the stream ends; this is a cheap metadata read.
            if (forceSoftware && !profileCheckDone.get()) {
                maybeRouteByProfile()
                if (playbackFailureReported.get()) return
            }
            val mp = mediaPlayer ?: return
            if (mp.currentVideoTrack != null) {
                readHealthSample(mp)?.let { sample ->
                    if (
                        sample.decodedVideo > 0L ||
                        sample.displayedPictures > 0L ||
                        sample.lostPictures > 0L
                    ) {
                        videoStatsUsable.set(true)
                    }
                    if (sample.decodedVideo > 0L) decodedVideoObserved.set(true)
                    val decision = playbackHealth.evaluate(
                        sample = sample,
                        nowMs = SystemClock.elapsedRealtime(),
                        videoActivelyPlaying =
                            playingObserved.get() && !bufferingActive.get(),
                    )
                    if (decision.firstDisplayedFrame && !nativeVideoOutputObserved) {
                        observeNativeVideoOutput(
                            "displayedPictures advanced -> validate SurfaceView",
                            hasFrameEvidence = true,
                        )
                    } else if (decision.firstDisplayedFrame) {
                        observeNativeVideoOutput(
                            "displayedPictures advanced after Vout -> validate SurfaceView",
                            hasFrameEvidence = true,
                        )
                    }
                    when {
                        decision.videoFrozen && forceSoftware ->
                            reportSoftwareTooSlow("software video output froze while input/time advanced")
                        decision.videoFrozen ->
                            reportVideoInvalid("video output froze while input/time advanced")
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
            !nativeVideoOutputObserved &&
            !playbackFailureReported.get() &&
            mediaPlayer?.currentVideoTrack != null &&
            voutObserved.get() &&
            playingObserved.get() &&
            !bufferingActive.get() &&
            !videoStatsUsable.get()
        ) {
            observeNativeVideoOutput(
                "stats unavailable after Vout grace -> validate SurfaceView",
                hasFrameEvidence = false,
            )
        }
    }

    // When stats are available, Vout is not enough: require displayedPictures to
    // advance. This catches a decoder that creates an output surface and advances
    // audio/time but never produces even one video frame.
    private val noDisplayedFrameRunnable = Runnable {
        if (
            !nativeOutputHasFrameEvidence &&
            !playbackFailureReported.get() &&
            videoStatsUsable.get() &&
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
            !nativeOutputHasFrameEvidence &&
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
    // stalled network, so every blocking libVLC call (stop / setMedia+play /
    // release) runs on the shared VlcOps thread instead of main. Two guards:
    //  - opsSeq: bumped by each play()/stop()/release() on the main thread; a
    //    queued runnable no-ops when its captured seq is no longer current, so a
    //    fast zap doesn't serially execute N blocking stop+play cycles (the
    //    newest command's cleanup upholds stop-before-start).
    //  - pendingOps: >0 while a stop/play command is queued or executing. Health
    //    polling pauses during that window because reading native stats while a
    //    native operation is mutating a MediaPlayer is unsafe. Events themselves
    //    are isolated by a fresh MediaPlayer instance and an identity check.
    private val opsSeq = AtomicLong(0)
    private val eventSession = AtomicLong(0)
    private val pendingOps = AtomicInteger(0)
    private data class DeferredPlay(val url: String, val reset: Boolean)
    private var deferredPlay: DeferredPlay? = null

    /**
     * One completion gate for every bounded play/pause/resume/stop command.
     * Timeout and normal return can race, so each command decrements exactly once.
     * Every transition to zero is marshalled to the main thread where a deferred
     * host move and the latest coalesced zap are drained in a deterministic order.
     */
    private fun newPendingCompletion(): () -> Unit {
        val completed = AtomicBoolean(false)
        pendingOps.incrementAndGet()
        return completion@{
            if (!completed.compareAndSet(false, true)) return@completion
            if (decrementPendingSafely() == 0) {
                commandHandler.post { onNativeCommandsIdle() }
            }
        }
    }

    private fun decrementPendingSafely(): Int {
        while (true) {
            val current = pendingOps.get()
            if (current <= 0) {
                PlaybackLog.log(
                    context,
                    engineName,
                    "pending native command underflow prevented",
                )
                return 0
            }
            if (pendingOps.compareAndSet(current, current - 1)) return current - 1
        }
    }

    private fun onNativeCommandsIdle() {
        if (pendingOps.get() != 0) return
        if (nativeHandlesAbandoned.get()) {
            deferredPlay = null
            hostMovePending = false
            return
        }
        if (hostMovePending && !moveLayoutToDesiredHost()) return
        val deferred = deferredPlay ?: return
        // detachVideo() can complete before the controller's delayed attachVideo().
        // Keep the latest zap queued until a real destination exists.
        if (videoHost == null) return
        deferredPlay = null
        if (mediaPlayer != null && libVlc != null) {
            play(deferred.url, deferred.reset)
        }
    }

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
        // LibVLC lazily reads its native plugin cache during construction. Scope
        // that known, bounded read so debug StrictMode does not misclassify the
        // library bootstrap as an accidental app disk access.
        val previousThreadPolicy = StrictMode.allowThreadDiskReads()
        val vlc = try {
            LibVLC(context, options)
        } finally {
            StrictMode.setThreadPolicy(previousThreadPolicy)
        }
        // Belt-and-braces: also set it on the instance (name + http UA).
        try {
            vlc.setUserAgent(AppInfo.USER_AGENT, AppInfo.USER_AGENT)
        } catch (error: Throwable) {
            runCatching { vlc.release() }
            throw error
        }
        val mp = try {
            MediaPlayer(vlc)
        } catch (error: Throwable) {
            runCatching { vlc.release() }
            throw error
        }

        val layout = createVideoLayout()
        try {
            container.addView(layout)
            // attachViews fully wires libVLC's AWindow/IVLCVout handler (fixes the
            // "libvlc window: request not implemented" green-screen spam). 4th arg
            // useTextureView = false: SurfaceView is REQUIRED to show the Amlogic
            // hardware underlay video plane — a TextureView always greens out.
            // (3rd arg false = no subtitle surface yet; added with subtitles.)
            mp.attachViews(layout, null, false, false)
        } catch (error: Throwable) {
            (layout.parent as? ViewGroup)?.removeView(layout)
            VlcOps.postBounded(
                timeoutMs = NATIVE_RELEASE_TIMEOUT_MS,
                onTimeout = {},
            ) {
                VlcOps.runAllBestEffort(
                    { mp.release() },
                    { vlc.release() },
                )
            }
            throw error
        }

        libVlc = vlc
        mediaPlayer = mp
        videoLayout = layout
        videoHost = container
        videoHostGeneration++
        viewsAttached = true
        hostMovePending = false
        playerHasMedia = false
        nativeHandlesAbandoned.set(false)
    }

    private fun createVideoLayout(): VLCVideoLayout =
        VLCVideoLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

    private data class VlcEventSnapshot(
        val type: Int,
        val buffering: Float,
        val voutCount: Int,
    )

    private fun isCurrentSession(mp: MediaPlayer, session: Long): Boolean =
        eventSession.get() == session && mediaPlayer === mp

    private fun invalidateEventSession() {
        eventSession.incrementAndGet()
    }

    /**
     * Every channel gets its own native MediaPlayer. libVLC events contain no
     * Media identity, so a reused player cannot distinguish a delayed Playing,
     * Vout, Error or EndReached from the channel that was just stopped. The
     * concrete-player identity check below makes that race impossible: queued
     * events from a retired player can never mutate the current channel.
     */
    private fun installEventListener(mp: MediaPlayer, session: Long) {
        mp.setEventListener { event ->
            // libVLC invokes this callback on a native event thread. Capture only
            // primitive values here, then serialize all state/listener mutations
            // onto main and re-check both player identity and session. A retired
            // channel can therefore never pass an early identity check and race
            // the next channel while its callback is still executing.
            val type = event.type
            val snapshot = VlcEventSnapshot(
                type = type,
                buffering =
                    if (type == MediaPlayer.Event.Buffering) event.buffering else 0f,
                voutCount =
                    if (type == MediaPlayer.Event.Vout) event.voutCount else 0,
            )
            healthHandler.post event@{
                if (!isCurrentSession(mp, session)) return@event
                handleEvent(mp, snapshot)
            }
        }
    }

    private fun handleEvent(mp: MediaPlayer, event: VlcEventSnapshot) {
        when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    // VLC keeps firing Buffering during normal playback (cache
                    // top-ups), normally ending each cycle near 100%. Use a
                    // completion tolerance because native progress may stop at
                    // 99.9; otherwise the spinner stays on screen forever.
                    if (isVlcBuffering(event.buffering)) {
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
                        if (playbackFailureReported.get()) return
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
                    applyDelays(mp)
                    listener?.onPlaying()
                    maybeRouteByProfile()
                    if (playbackFailureReported.get()) return
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
                    if (event.voutCount <= 0) return
                    voutObserved.set(true)
                    maybeRouteByProfile()
                    if (playbackFailureReported.get()) return
                    scheduleProfileRechecks()
                    scheduleHealthChecks(immediate = true)
                    scheduleVoutCompatibility()
                    scheduleOutputTimeouts()
                }
                MediaPlayer.Event.ESAdded -> {
                    // Live MPEG-TS tracks often arrive after Playing. Re-apply the
                    // persisted ISO language preference once the ES list settles.
                    scheduleAudioCheck()
                }
                MediaPlayer.Event.EndReached -> listener?.onEnded()
                MediaPlayer.Event.EncounteredError -> {
                    PlaybackLog.log(context, engineName, "EncounteredError (forceSoftware=$forceSoftware)")
                    // libVLC's event exposes no HTTP status or manifest/source
                    // classification. Treating this opaque event as a source
                    // signal could rewrite TS/HLS after an authoritative
                    // 401/403/404, so the safe compatibility path is the normal
                    // bounded engine/reconnect policy with no format probing.
                    listener?.onError("VLC error")
                }
            }
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
        val isUhd =
            VlcHardwareDevicePolicy.requiresHardwareDecode(
                width = track.width,
                height = track.height,
            )

        if (forceSoftware) {
            if (isUhd && claimRouting()) {
                reportSoftwareTooSlow(
                    "UHD ${track.width}x${track.height} cannot sustain software decode",
                )
            }
            // Live TS metadata can first expose a placeholder 720p size and then
            // update to 2160p. Do not claim a non-UHD snapshot: keep all delayed
            // checks armed so that later real dimensions still escape software.
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

    private fun observeNativeVideoOutput(
        detail: String,
        hasFrameEvidence: Boolean,
    ) {
        if (playbackFailureReported.get()) return
        if (hasFrameEvidence) {
            nativeOutputHasFrameEvidence = true
            healthHandler.removeCallbacks(noDisplayedFrameRunnable)
            healthHandler.removeCallbacks(noVideoPipelineRunnable)
        }
        val firstObservation = !nativeVideoOutputObserved
        nativeVideoOutputObserved = true
        healthHandler.removeCallbacks(voutCompatibilityRunnable)
        PlaybackLog.log(context, engineName, detail)
        if (
            shouldTrustVlcNativeFrame(
                forceSoftware = forceSoftware,
                hasDisplayedPictureEvidence = hasFrameEvidence,
            )
        ) {
            // Software displayedPictures is authoritative vout evidence. Cancel
            // an earlier PixelCopy monitor that may have sampled a stale/blank
            // SurfaceView before this first real native picture arrived.
            surfaceFrameHealth.reset()
            surfaceHealthyObserved = true
            PlaybackLog.log(
                context,
                engineName,
                "software displayed-picture evidence accepted",
            )
            maybeReportVideoOutput()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (firstObservation) {
                surfaceFrameHealth.start { findVideoSurface(videoLayout) }
            }
            healthHandler.removeCallbacks(surfaceValidationDeadlineRunnable)
            healthHandler.postDelayed(
                surfaceValidationDeadlineRunnable,
                if (hasFrameEvidence) {
                    PIXEL_VALIDATION_DEADLINE_MS
                } else {
                    NO_VIDEO_PIPELINE_TIMEOUT_MS
                },
            )
        } else if (hasFrameEvidence) {
            // Older Android versions cannot sample the surface. Native displayed
            // picture counters remain authoritative; Vout alone does not.
            surfaceHealthyObserved = true
            maybeReportVideoOutput()
        }
        if (
            hasFrameEvidence &&
            surfaceFrameHealth.isSamplingUnavailable() &&
            !videoOutputReported
        ) {
            surfaceHealthyObserved = true
            maybeReportVideoOutput()
        }
    }

    private fun maybeReportVideoOutput() {
        if (
            videoOutputReported ||
            !nativeVideoOutputObserved ||
            !surfaceHealthyObserved ||
            playbackFailureReported.get()
        ) {
            return
        }
        videoOutputReported = true
        healthHandler.removeCallbacks(surfaceValidationDeadlineRunnable)
        healthHandler.removeCallbacks(noDisplayedFrameRunnable)
        healthHandler.removeCallbacks(noVideoPipelineRunnable)
        PlaybackLog.log(context, engineName, "healthy SurfaceView frame confirmed")
        listener?.onVideoOutput()
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
     * from its parent. A later hand-off replay creates a fresh MediaPlayer and
     * SurfaceView; this old layout is kept only as a host-transfer placeholder
     * until [play] completes the ordered teardown/replacement.
     */
    override fun detachVideo() {
        videoHost = null
        videoHostGeneration++
        hostMovePending = true
        // SurfaceView destruction can race the native call that is currently
        // stopping/starting the decoder. Keep both the view and its parent intact
        // until the shared completion gate reports native ownership idle.
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return
        moveLayoutToDesiredHost()
    }

    /**
     * Re-home the layout placeholder into [container]. For an idle, never-started
     * player this also attaches the native views. A running hand-off deliberately
     * waits for [play] to release the old native player before attaching a fresh
     * layout, avoiding an AWindow teardown race on Amlogic devices.
     */
    override fun attachVideo(container: ViewGroup) {
        videoHost = container
        videoHostGeneration++
        hostMovePending = true
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return
        if (moveLayoutToDesiredHost()) {
            // Post so PlayerController's immediate replay can take precedence and
            // clear the same deferred URL without causing a duplicate start.
            commandHandler.post { onNativeCommandsIdle() }
        }
    }

    /**
     * Apply the latest detach/rebind request. This is main-thread only and never
     * runs while a bounded native command is pending. [viewsAttached] is the
     * source of truth, preventing detachViews() from being called twice on a
     * libVLC AWindow that is already tearing down.
     */
    private fun moveLayoutToDesiredHost(): Boolean {
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return false
        val layout = videoLayout ?: run {
            hostMovePending = false
            return true
        }
        val target = videoHost
        val currentParent = layout.parent as? ViewGroup

        if (target == null || currentParent !== target) {
            if (viewsAttached) {
                val mp = mediaPlayer ?: return false
                try {
                    mp.detachViews()
                    viewsAttached = false
                } catch (error: Throwable) {
                    PlaybackLog.log(
                        context,
                        engineName,
                        "surface detach failed: ${error.javaClass.simpleName}",
                    )
                    listener?.onError("VLC surface detach error")
                    return false
                }
            }
            currentParent?.removeView(layout)
            if (target != null) target.addView(layout)
        }

        // A hand-off of a running player immediately calls play(), which creates
        // a fresh player/surface. Re-attaching the retired native player here
        // would race AWindow teardown. A never-started player can attach directly.
        if (target != null && !playerHasMedia && !viewsAttached) {
            val mp = mediaPlayer ?: return false
            try {
                mp.attachViews(layout, null, false, false)
                viewsAttached = true
            } catch (error: Throwable) {
                PlaybackLog.log(
                    context,
                    engineName,
                    "surface attach failed: ${error.javaClass.simpleName}",
                )
                listener?.onError("VLC surface attach error")
                return false
            }
        }
        hostMovePending = false
        return true
    }

    // reset is unused for libVLC: every play gets a fresh native MediaPlayer and
    // Media. Retiring the old player before the new one opens its URL preserves
    // the provider's single-connection contract and isolates late native events.
    override fun play(url: String, reset: Boolean) {
        if (nativeHandlesAbandoned.get()) {
            PlaybackLog.log(context, engineName, "play refused on quarantined native handles")
            listener?.onError("VLC native session unavailable")
            return
        }
        // Never detach/swap a MediaPlayer while its previous stop/start is still
        // inside JNI. Keep only the latest DPAD zap; finishPending re-enters play
        // once native ownership is idle.
        if (pendingOps.get() > 0) {
            // Cancel the continuation and every health/event callback belonging
            // to the superseded channel immediately. Without this boundary, a
            // late Playing/Vout event from the in-flight start could validate the
            // channel the controller has already switched to.
            opsSeq.incrementAndGet()
            invalidateEventSession()
            profileHandler.removeCallbacksAndMessages(null)
            audioHandler.removeCallbacksAndMessages(null)
            healthHandler.removeCallbacksAndMessages(null)
            surfaceFrameHealth.reset()
            deferredPlay = DeferredPlay(url, reset)
            PlaybackLog.log(
                context,
                engineName,
                "native command busy -> cancel stale continuation and coalesce latest zap",
            )
            return
        }
        deferredPlay = null
        val vlc = libVlc ?: return
        videoLayout ?: return
        videoHost ?: return
        val boundPlayer = mediaPlayer ?: return
        val previousPlayer = boundPlayer.takeIf { playerHasMedia }
        val mp = if (previousPlayer == null) {
            // First start after bind: keep the already-attached player/surface.
            boundPlayer
        } else {
            // Construct before disturbing the current player. If native player
            // creation fails, the old channel remains attached and recoverable
            // until the controller handles the reported startup error.
            val replacement = try {
                MediaPlayer(vlc)
            } catch (error: Throwable) {
                PlaybackLog.log(
                    context,
                    engineName,
                    "replacement creation failed: ${error.javaClass.simpleName}",
                )
                listener?.onError("VLC player creation error")
                return
            }
            invalidateEventSession()
            try {
                previousPlayer.setEventListener(null)
            } catch (error: Throwable) {
                val restoredSession = eventSession.incrementAndGet()
                installEventListener(previousPlayer, restoredSession)
                val finishReplacementRelease = newPendingCompletion()
                VlcOps.postBounded(
                    timeoutMs = NATIVE_OPERATION_TIMEOUT_MS,
                    onTimeout = {
                        nativeHandlesAbandoned.set(true)
                        deferredPlay = null
                        invalidateEventSession()
                        PlaybackLog.log(
                            context,
                            engineName,
                            "replacement release timed out after listener failure",
                        )
                        finishReplacementRelease()
                    },
                    onTimedOutActionFinished = {
                        // replacement.release() and the old player share this
                        // LibVLC. Once it times out, quarantine the entire native
                        // ownership set; only this retired worker may tear it down.
                        releaseQuarantinedHandles(
                            mp = previousPlayer,
                            vlc = vlc,
                            detail = "replacement release timeout",
                        )
                    },
                ) {
                    try {
                        replacement.release()
                    } finally {
                        finishReplacementRelease()
                    }
                }
                PlaybackLog.log(
                    context,
                    engineName,
                    "retired listener detach failed: ${error.javaClass.simpleName}",
                )
                listener?.onError("VLC listener detach error")
                return
            }
            // Keep the old SurfaceView alive until stop()+release() completes.
            // Destroying it while the Amlogic decoder still owns output buffers
            // produces EGL_BAD_NATIVE_WINDOW / unknown-buffer errors and can
            // contaminate the replacement surface.
            viewsAttached = false
            mediaPlayer = replacement
            replacement
        }
        val session = eventSession.incrementAndGet()
        installEventListener(mp, session)
        playerHasMedia = true
        profileCheckDone.set(false)
        profileHandler.removeCallbacksAndMessages(null)
        playbackFailureReported.set(false)
        videoStatsUsable.set(false)
        decodedVideoObserved.set(false)
        playingObserved.set(false)
        bufferingActive.set(true)
        voutObserved.set(false)
        playbackHealth.reset()
        healthHandler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        videoOutputReported = false
        nativeVideoOutputObserved = false
        nativeOutputHasFrameEvidence = false
        surfaceHealthyObserved = false
        // Reset the silent-audio guard on the main thread, then hand the blocking
        // old-player cleanup -> setMedia -> play sequence to the VLC ops thread
        // as ONE runnable
        // (FIFO keeps stop-before-start; the main thread never blocks on a stalled
        // network). A fresh native player also means a queued callback from the
        // previous channel cannot be mistaken for this one.
        audioHealAttempted = false
        audioHandler.removeCallbacksAndMessages(null)
        PlaybackLog.log(context, engineName, "play forceSoftware=$forceSoftware passthrough=$allowPassthrough")
        val mySeq = opsSeq.incrementAndGet()
        val finishPending = newPendingCompletion()
        if (previousPlayer == null) {
            startMediaBounded(
                vlc = vlc,
                mp = mp,
                url = url,
                sequence = mySeq,
                finishPending = finishPending,
            )
            return
        }

        VlcOps.postBounded(
            timeoutMs = NATIVE_OPERATION_TIMEOUT_MS,
            onTimeout = {
                val firstAbandon =
                    nativeHandlesAbandoned.compareAndSet(false, true)
                deferredPlay = null
                opsSeq.incrementAndGet()
                invalidateEventSession()
                if (firstAbandon) {
                    PlaybackLog.log(context, engineName, "retired player cleanup timed out")
                    // The queued latest zap depends on this same LibVLC owner.
                    // Notify the controller even when it already advanced opsSeq;
                    // otherwise it waits for the much longer startup watchdog.
                    listener?.onError("VLC cleanup timeout")
                }
                finishPending()
            },
            onTimedOutActionFinished = {
                // The retired action may have returned from stop/release after
                // the watchdog rotated the queue. The replacement and LibVLC are
                // now quarantined and must only be touched by this old worker.
                releaseQuarantinedHandles(
                    mp = mp,
                    vlc = vlc,
                    detail = "retired player cleanup timeout",
                    stopFirst = false,
                )
            },
        ) cleanup@{
            if (nativeHandlesAbandoned.get()) {
                finishPending()
                return@cleanup
            }
            val stopFailure = runCatching { previousPlayer.stop() }.exceptionOrNull()
            val releaseFailure = runCatching { previousPlayer.release() }.exceptionOrNull()
            stopFailure?.let {
                PlaybackLog.log(
                    context,
                    engineName,
                    "zap stop failed: ${it.javaClass.simpleName}",
                )
            }
            if (nativeHandlesAbandoned.get()) {
                finishPending()
                return@cleanup
            }
            if (releaseFailure != null) {
                PlaybackLog.log(
                    context,
                    engineName,
                    "zap release failed: ${releaseFailure.javaClass.simpleName}",
                )
                commandHandler.post {
                    if (
                        opsSeq.compareAndSet(mySeq, mySeq + 1L) &&
                        mediaPlayer === mp
                    ) {
                        invalidateEventSession()
                        listener?.onError("VLC cleanup error")
                    }
                }
                // Account for the native command before VlcOps dequeues anything
                // else. The error callback was posted first, so it invalidates the
                // stage before finishPending's optional deferred-zap continuation.
                finishPending()
                return@cleanup
            }

            // Re-check after the (possibly long) teardown: don't attach/start a
            // channel the user has already zapped away from.
            if (opsSeq.get() != mySeq || mediaPlayer !== mp) {
                finishPending()
                return@cleanup
            }
            commandHandler.postDelayed(attach@{
                if (opsSeq.get() != mySeq || mediaPlayer !== mp) {
                    finishPending()
                    return@attach
                }
                if (videoHost == null) {
                    // A rebind is between detachVideo() and attachVideo(). Do not
                    // bind the fresh AWindow back to the retired host; preserve
                    // the newest zap until attachVideo supplies its generation.
                    if (deferredPlay == null) {
                        deferredPlay = DeferredPlay(url, reset)
                    }
                    finishPending()
                    return@attach
                }
                try {
                    attachFreshVideoLayout(mp)
                } catch (error: Throwable) {
                    opsSeq.compareAndSet(mySeq, mySeq + 1L)
                    invalidateEventSession()
                    PlaybackLog.log(
                        context,
                        engineName,
                        "fresh surface attach failed: ${error.javaClass.simpleName}",
                    )
                    // Ownership remains with this engine; the controller's normal
                    // release path performs the single stop/release sequence.
                    listener?.onError("VLC surface error")
                    finishPending()
                    return@attach
                }
                startMediaBounded(
                    vlc = vlc,
                    mp = mp,
                    url = url,
                    sequence = mySeq,
                    finishPending = finishPending,
                )
            }, SURFACE_REPLACEMENT_DELAY_MS)
        }
    }

    /**
     * Replace the SurfaceView only after the retired native player has released.
     * Besides avoiding vendor AWindow teardown races, a new surface cannot retain
     * the prior channel's last frame and falsely validate a black new channel.
     * The destination is deliberately resolved here, not when play() began: a
     * preview/fullscreen rebind may have changed hosts while cleanup was in JNI.
     */
    private fun attachFreshVideoLayout(replacement: MediaPlayer) {
        val host = videoHost ?: error("VLC video host detached")
        val hostGeneration = videoHostGeneration
        val retiredLayout = videoLayout
        val retiredParent = retiredLayout?.parent as? ViewGroup
        val insertIndex =
            if (retiredParent === host && retiredLayout != null) {
                host.indexOfChild(retiredLayout).coerceAtLeast(0)
            } else {
                host.childCount
            }
        retiredLayout?.let { retiredParent?.removeView(it) }

        val freshLayout = createVideoLayout()
        host.addView(freshLayout, insertIndex.coerceIn(0, host.childCount))
        var attached = false
        try {
            check(videoHost === host && videoHostGeneration == hostGeneration) {
                "VLC video host changed before surface attach"
            }
            replacement.attachViews(freshLayout, null, false, false)
            attached = true
            check(videoHost === host && videoHostGeneration == hostGeneration) {
                "VLC video host changed during surface attach"
            }
        } catch (error: Throwable) {
            if (attached) runCatching { replacement.detachViews() }
            host.removeView(freshLayout)
            throw error
        }
        videoLayout = freshLayout
        viewsAttached = true
        hostMovePending = false
    }

    private fun startMediaBounded(
        vlc: LibVLC,
        mp: MediaPlayer,
        url: String,
        sequence: Long,
        finishPending: () -> Unit,
    ) {
        // This point is reached only after any retired player cleanup and Surface
        // replacement have completed. The controller's startup budget must begin
        // here—not when a rapid zap was merely stored in deferredPlay.
        listener?.onPlaybackSubmitted()
        VlcOps.postBounded(
            timeoutMs = NATIVE_OPERATION_TIMEOUT_MS,
            onTimeout = {
                val firstAbandon =
                    nativeHandlesAbandoned.compareAndSet(false, true)
                deferredPlay = null
                opsSeq.incrementAndGet()
                invalidateEventSession()
                if (firstAbandon) {
                    PlaybackLog.log(context, engineName, "native start timed out")
                    // Abandoning LibVLC is engine-wide. Even if a newer zap already
                    // invalidated [sequence], that newest request was waiting on
                    // these same handles and must be recovered immediately.
                    listener?.onError("VLC start timeout")
                }
                finishPending()
            },
            onTimedOutActionFinished = {
                releaseQuarantinedHandles(
                    mp = mp,
                    vlc = vlc,
                    detail = "native start timeout",
                )
            },
        ) start@{
            try {
                if (
                    nativeHandlesAbandoned.get() ||
                    opsSeq.get() != sequence ||
                    mediaPlayer !== mp
                ) return@start
                val media = Media(vlc, android.net.Uri.parse(url)).apply {
                    // Hardware is preferred but never forced past libVLC's
                    // per-device safety list. The old force=true made AUTO and
                    // Hardware identical and enabled broken MediaCodec paths.
                    setHWDecoderEnabled(!forceSoftware, false)
                    addOption(":network-caching=$vlcCachingMs")
                    addOption(":live-caching=$vlcCachingMs")
                    if (forceSoftware) addOption(":avcodec-hw=none")
                    if (!allowPassthrough) {
                        addOption(":no-spdif")
                        addOption(":stereo-mode=1")
                    }
                    addOption(":http-reconnect")
                    addOption(":http-user-agent=${AppInfo.USER_AGENT}")
                }
                try {
                    mp.media = media
                    if (opsSeq.get() != sequence || mediaPlayer !== mp) return@start
                    mp.play()
                } finally {
                    media.release()
                }
            } catch (error: Throwable) {
                commandHandler.post {
                    if (
                        opsSeq.compareAndSet(sequence, sequence + 1L) &&
                        mediaPlayer === mp
                    ) {
                        invalidateEventSession()
                        PlaybackLog.log(
                            context,
                            engineName,
                            "native start failed: ${error.javaClass.simpleName}",
                        )
                        listener?.onError("VLC start error")
                    }
                }
            } finally {
                finishPending()
            }
        }
    }

    /**
     * Called only through VlcOps.onTimedOutActionFinished, on the exact retired
     * worker whose operation timed out. There is intentionally no engine-global
     * "first cleanup caller" flag: a queued later operation must never claim a
     * prior operation's native handles.
     */
    private fun releaseQuarantinedHandles(
        mp: MediaPlayer?,
        vlc: LibVLC?,
        detail: String,
        stopFirst: Boolean = true,
    ) {
        val failures =
            if (stopFirst) {
                VlcOps.runAllBestEffort(
                    { mp?.stop() },
                    { mp?.release() },
                    { vlc?.release() },
                )
            } else {
                VlcOps.runAllBestEffort(
                    { mp?.release() },
                    { vlc?.release() },
                )
            }
        PlaybackLog.log(
            context,
            engineName,
            "quarantined native handles released after $detail " +
                "(failures=${failures.size})",
        )
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
        audioHandler.removeCallbacksAndMessages(null)
        audioHandler.postDelayed(
            {
                healSilentAudio()
                applyPreferredTracks()
            },
            AUDIO_CHECK_DELAY_MS,
        )
    }

    private fun healSilentAudio() {
        if (audioHealAttempted) return
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) {
            audioHandler.postDelayed({ healSilentAudio() }, AUDIO_CHECK_DELAY_MS)
            return
        }
        val mp = mediaPlayer ?: return
        val firstReal = mp.audioTracks?.firstOrNull { it.id != -1 } ?: return
        if (mp.audioTrack == -1) {
            audioHealAttempted = true
            PlaybackLog.log(context, engineName, "audio present but unselected -> selecting track ${firstReal.id}")
            mp.audioTrack = firstReal.id
        }
    }

    private fun applyPreferredTracks() {
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return
        val mp = mediaPlayer ?: return
        preferredAudioLanguage?.let { language ->
            mp.audioTracks
                ?.firstOrNull {
                    it.id != -1 && TrackLanguage.normalize(it.name) == language
                }
                ?.let { if (mp.audioTrack != it.id) mp.audioTrack = it.id }
        }
        when (val preference = subtitlePreference) {
            LiveSubtitlePreference.Auto -> Unit
            LiveSubtitlePreference.Off -> if (mp.spuTrack != -1) mp.spuTrack = -1
            is LiveSubtitlePreference.Language -> {
                mp.spuTracks
                    ?.firstOrNull {
                        it.id != -1 && TrackLanguage.normalize(it.name) == preference.code
                    }
                    ?.let { if (mp.spuTrack != it.id) mp.spuTrack = it.id }
            }
        }
    }

    // libVLC's playback clock (ms). Advances while frames flow (including from
    // cache during a top-up); freezes once a silent network stall drains the
    // buffer — exactly the signal the controller's stall watchdog keys on.
    override fun playbackPositionMs(): Long {
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return -1L
        return mediaPlayer?.time ?: -1L
    }

    override fun pause() {
        val mp = mediaPlayer ?: return
        val vlc = libVlc
        if (nativeHandlesAbandoned.get()) return
        val finishPending = newPendingCompletion()
        VlcOps.postBounded(
            timeoutMs = NATIVE_OPERATION_TIMEOUT_MS,
            onTimeout = {
                nativeHandlesAbandoned.set(true)
                deferredPlay = null
                invalidateEventSession()
                PlaybackLog.log(context, engineName, "native pause timed out")
                finishPending()
            },
            onTimedOutActionFinished = {
                releaseQuarantinedHandles(mp, vlc, "native pause timeout")
            },
        ) {
            try {
                if (!nativeHandlesAbandoned.get()) mp.pause()
            } finally {
                finishPending()
            }
        }
    }

    override fun resume() {
        val mp = mediaPlayer ?: return
        val vlc = libVlc
        if (nativeHandlesAbandoned.get()) {
            listener?.onError("VLC native session unavailable")
            return
        }
        val finishPending = newPendingCompletion()
        VlcOps.postBounded(
            timeoutMs = NATIVE_OPERATION_TIMEOUT_MS,
            onTimeout = {
                nativeHandlesAbandoned.set(true)
                deferredPlay = null
                invalidateEventSession()
                PlaybackLog.log(context, engineName, "native resume timed out")
                if (mediaPlayer === mp) listener?.onError("VLC resume timeout")
                finishPending()
            },
            onTimedOutActionFinished = {
                releaseQuarantinedHandles(mp, vlc, "native resume timeout")
            },
        ) {
            try {
                if (!nativeHandlesAbandoned.get()) mp.play()
            } finally {
                finishPending()
            }
        }
    }

    override fun stop() {
        stopAndThen { }
    }

    override fun stopAndThen(onStopped: (Boolean) -> Unit) {
        profileHandler.removeCallbacksAndMessages(null)
        audioHandler.removeCallbacksAndMessages(null)
        healthHandler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        deferredPlay = null
        val mp = mediaPlayer ?: run {
            onStopped(true)
            return
        }
        val vlc = libVlc
        if (nativeHandlesAbandoned.get()) {
            invalidateEventSession()
            onStopped(false)
            return
        }
        // Invalidate callbacks immediately in Java state. The JNI listener detach
        // itself belongs in the bounded worker below; a vendor implementation may
        // block there just like stop()/detachViews().
        invalidateEventSession()
        val mySeq = opsSeq.incrementAndGet()
        val finishPending = newPendingCompletion()
        val callbackDelivered = AtomicBoolean(false)
        fun deliverStopped(success: Boolean) {
            if (!callbackDelivered.compareAndSet(false, true)) return
            commandHandler.post { onStopped(success) }
        }
        VlcOps.postBounded(
            timeoutMs = NATIVE_OPERATION_TIMEOUT_MS,
            onTimeout = {
                nativeHandlesAbandoned.set(true)
                invalidateEventSession()
                PlaybackLog.log(context, engineName, "native stop timed out")
                finishPending()
                deliverStopped(false)
            },
            onTimedOutActionFinished = {
                releaseQuarantinedHandles(
                    mp = mp,
                    vlc = vlc,
                    detail = "native stop timeout",
                    stopFirst = false,
                )
            },
        ) nativeStop@{
            var stopped = false
            try {
                // A newer play/stop already queued -> its own stop supersedes this.
                if (
                    nativeHandlesAbandoned.get() ||
                    opsSeq.get() != mySeq
                ) return@nativeStop
                runCatching { mp.setEventListener(null) }.onFailure {
                    PlaybackLog.log(
                        context,
                        engineName,
                        "stop listener detach failed: ${it.javaClass.simpleName}",
                    )
                }
                mp.stop()
                stopped = true
            } finally {
                finishPending()
                deliverStopped(stopped)
            }
        }
    }

    override fun release() {
        profileHandler.removeCallbacksAndMessages(null)
        audioHandler.removeCallbacksAndMessages(null)
        healthHandler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        deferredPlay = null
        invalidateEventSession()
        // Reserve this exact owner generation before inspecting pending native
        // work. Public engine commands are main-thread serialized; the seq bump
        // also invalidates delayed cleanup->attach continuations.
        opsSeq.incrementAndGet()
        val retiredLayout = videoLayout
        fun removeRetiredLayout() {
            retiredLayout?.let { layout ->
                (layout.parent as? ViewGroup)?.removeView(layout)
            }
        }
        if (nativeHandlesAbandoned.get()) {
            // The timed-out worker owns these handles until it unwinds. Only
            // relinquish Java/UI ownership here; touching JNI from a fresh worker
            // would race the quarantined call.
            removeRetiredLayout()
            mediaPlayer = null
            libVlc = null
            videoLayout = null
            videoHost = null
            videoHostGeneration++
            viewsAttached = false
            hostMovePending = false
            playerHasMedia = false
            listener = null
            return
        }
        // Capture the complete native owner. The SurfaceView deliberately stays
        // attached until stop()+release() below has returned. Removing/detaching
        // it first is the exact race visible in the device log:
        // EGL_BAD_NATIVE_WINDOW, unknown buffer and dequeueBuffer(-19).
        val mp = mediaPlayer
        val vlc = libVlc
        mediaPlayer = null
        libVlc = null
        videoLayout = null
        videoHost = null
        videoHostGeneration++
        viewsAttached = false
        hostMovePending = false
        playerHasMedia = false
        listener = null
        if (mp != null || vlc != null) {
            val releaseOperationTimedOut = AtomicBoolean(false)
            VlcOps.postBounded(
                timeoutMs = NATIVE_RELEASE_TIMEOUT_MS,
                onTimeout = {
                    // Android cannot cancel a thread blocked in vendor JNI.
                    // Quarantine this owner before the fresh worker can run, and
                    // remove only its Android view on main. The retired worker
                    // remains the sole native owner and continues the teardown
                    // sequence if/when the blocked call returns.
                    releaseOperationTimedOut.set(true)
                    nativeHandlesAbandoned.set(true)
                    removeRetiredLayout()
                    PlaybackLog.log(context, engineName, "native release timed out")
                },
            ) releaseOwner@{
                // If an earlier queued operation timed out, its exact retired
                // worker owns cleanup for these handles. A fresh worker must
                // not touch them a second time.
                if (
                    nativeHandlesAbandoned.get() &&
                    !releaseOperationTimedOut.get()
                ) {
                    commandHandler.post { removeRetiredLayout() }
                    return@releaseOwner
                }
                val failures = VlcOps.runAllBestEffort(
                    { mp?.setEventListener(null) },
                    { mp?.stop() },
                    { mp?.release() },
                    { vlc?.release() },
                )
                failures.forEach { failure ->
                    PlaybackLog.log(
                        context,
                        engineName,
                        "release cleanup failed: ${failure.javaClass.simpleName}",
                    )
                }
                if (!releaseOperationTimedOut.get()) {
                    // Keep the shared VlcOps FIFO occupied until main has removed
                    // the now-retired SurfaceView. The next engine's play cannot
                    // race a still-visible old native window.
                    val uiCleanupFinished = CountDownLatch(1)
                    commandHandler.post {
                        try {
                            removeRetiredLayout()
                        } finally {
                            uiCleanupFinished.countDown()
                        }
                    }
                    try {
                        if (
                            !uiCleanupFinished.await(
                                UI_SURFACE_RETIRE_TIMEOUT_MS,
                                TimeUnit.MILLISECONDS,
                            )
                        ) {
                            PlaybackLog.log(
                                context,
                                engineName,
                                "retired surface removal exceeded UI deadline",
                            )
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
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
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return null
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

    override fun audioTracks(): List<PlayerTrack> = vlcTracks(audio = true)

    override fun subtitleTracks(): List<PlayerTrack> = vlcTracks(audio = false)

    private fun vlcTracks(audio: Boolean): List<PlayerTrack> {
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return emptyList()
        val mp = mediaPlayer ?: return emptyList()
        val selected = if (audio) mp.audioTrack else mp.spuTrack
        val tracks = if (audio) mp.audioTracks else mp.spuTracks
        return tracks
            ?.filter { it.id != -1 }
            ?.map {
                PlayerTrack(
                    id = it.id.toString(),
                    label = it.name,
                    language = TrackLanguage.normalize(it.name),
                    selected = it.id == selected,
                )
            }
            .orEmpty()
    }

    override fun selectAudioTrack(id: String): Boolean {
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return false
        val trackId = id.toIntOrNull() ?: return false
        val mp = mediaPlayer ?: return false
        if (mp.audioTracks?.none { it.id == trackId } != false) return false
        mp.audioTrack = trackId
        return true
    }

    override fun selectSubtitleTrack(id: String?): Boolean {
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return false
        val trackId = id?.toIntOrNull() ?: -1
        val mp = mediaPlayer ?: return false
        if (trackId != -1 && mp.spuTracks?.none { it.id == trackId } != false) return false
        mp.spuTrack = trackId
        return true
    }

    override fun setPreferredTrackLanguages(audio: String?, subtitle: String?) {
        preferredAudioLanguage = TrackLanguage.normalize(audio)
        LiveSubtitlePreference.Language.from(subtitle)?.let { subtitlePreference = it }
        if (pendingOps.get() == 0 && !nativeHandlesAbandoned.get()) {
            applyPreferredTracks()
        }
    }

    override fun setSubtitlePreference(preference: LiveSubtitlePreference): Boolean {
        subtitlePreference = preference
        if (pendingOps.get() > 0 || nativeHandlesAbandoned.get()) return false
        val available = mediaPlayer != null
        applyPreferredTracks()
        return available
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
        if (pendingOps.get() == 0 && !nativeHandlesAbandoned.get()) {
            mediaPlayer?.audioDelay = ms * 1000
        }
    }

    override fun setSubtitleDelayMs(ms: Long) {
        subtitleDelayMs = ms
        if (pendingOps.get() == 0 && !nativeHandlesAbandoned.get()) {
            mediaPlayer?.spuDelay = ms * 1000
        }
    }

    private fun applyDelays(mp: MediaPlayer) {
        if (nativeHandlesAbandoned.get()) return
        if (pendingOps.get() > 0) {
            commandHandler.postDelayed({
                if (mediaPlayer === mp) applyDelays(mp)
            }, NATIVE_STATE_RETRY_MS)
            return
        }
        mp.audioDelay = audioDelayMs * 1000
        mp.spuDelay = subtitleDelayMs * 1000
    }

    companion object {
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
        private const val PIXEL_VALIDATION_DEADLINE_MS = 9_000L

        private const val HEALTH_POLL_MS = 1500L
        private const val VOUT_STATS_GRACE_MS = 4500L
        private const val NO_DISPLAYED_FRAME_TIMEOUT_MS = 12_000L
        private const val NO_VIDEO_PIPELINE_TIMEOUT_MS = 20_000L
        private const val NATIVE_OPERATION_TIMEOUT_MS = 8_000L
        private const val NATIVE_RELEASE_TIMEOUT_MS = 12_000L
        private const val NATIVE_STATE_RETRY_MS = 100L
        private const val SURFACE_REPLACEMENT_DELAY_MS = 250L
        private const val UI_SURFACE_RETIRE_TIMEOUT_MS = 1_500L

        private const val GREEN_PRONE_MIN_HEIGHT = 1080
        private const val GREEN_PRONE_MIN_WIDTH = 1920
        private const val GREEN_PRONE_MIN_FPS = 49f
    }
}
