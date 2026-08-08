/*
 * ExoPlayerEngine.kt
 * Primary playback backend using AndroidX Media3 (ExoPlayer). Handles HLS/DASH/
 * progressive automatically. Tuned for fast live startup with small buffers.
 *
 * Real-stick hardening (emulator hides these because it decodes in software):
 *   - Audio is forced to PCM (passthrough disabled by default) so AC-3/E-AC-3/
 *     AAC always produce sound; tunneling is disabled (a common green/black
 *     frame cause on cheap sticks).
 *   - When the device has no decoder for an audio codec (e.g. AC-3/E-AC-3/MP2),
 *     the audio track ends up unsupported/unselected -> onAudioUnavailable so the
 *     controller can fall back to libVLC (software PCM decode).
 *   - Video uses a SurfaceView (required: Amlogic and most TV SoCs composite
 *     hardware-decoded frames on an underlay plane a TextureView cannot show,
 *     which is the real green-screen cause). The PlayerView uses resize_mode=fill
 *     so the SurfaceView stays at a CONSTANT size: a video-size-driven aspect
 *     relayout would otherwise resize the SurfaceView, tearing down + recreating
 *     its surface ~1s in and greening the new surface on Amlogic. Decoder fallback
 *     + disabled tunneling guard the decode path; if no first frame arrives after
 *     READY the decoder is stuck -> onVideoInvalid, then the controller applies
 *     the selected engine/decoder policy.
 */
package com.iptv.player.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import com.iptv.player.R
import com.iptv.player.data.model.BufferMode
import com.iptv.player.util.AppInfo
import com.iptv.player.util.PlaybackLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

@OptIn(markerClass = [UnstableApi::class])
class ExoPlayerEngine(
    private val context: Context,
    /** When false (default) audio is decoded to PCM; true allows HDMI passthrough. */
    private val allowPassthrough: Boolean = false,
    /** Buffer size (user "Buffer size" setting) -> DefaultLoadControl durations. */
    private val bufferMode: BufferMode = BufferMode.NORMAL,
    /**
     * True for television/video playback, false for audio-only radio. Media3 READY
     * is only a transport/cache state; for TV it must never be reported as healthy
     * playback until a real frame has been verified on the video surface.
     */
    private val expectsVideo: Boolean = true,
) : PlayerEngine {

    override val engineName: String = "ExoPlayer"
    override val supportsPreciseSourceErrors: Boolean = true

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var listener: PlayerListener? = null
    private var preferredAudioLanguage: String? = null
    private var subtitlePreference: LiveSubtitlePreference = LiveSubtitlePreference.Auto

    private val handler = Handler(Looper.getMainLooper())
    // Per-stream health flags so each detection fires at most once.
    private var firstFrameRendered = false
    private var videoFailureReported = false
    private var videoOutputReported = false
    private var audioReported = false
    private val lastVideoFrameAtMs = AtomicLong(0L)
    private var readyForPlayback = false
    private var streamGeneration = 0L
    private var activeMediaId: String? = null
    private var trackSupportCheckRunnable: Runnable? = null
    private var noFrameCheckRunnable: Runnable? = null
    private var hardwareDecodeRequired = false
    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0
    private val droppedFrameHealth = DroppedFrameRecoveryGate()

    private val surfaceFrameHealth = SurfaceFrameHealthMonitor(
        handler = handler,
        onSolidGreen = {
            reportVideoInvalid("persistent solid-green SurfaceView output")
        },
        onPersistentBlank = {
            reportVideoInvalid("persistent blank SurfaceView output")
        },
        onHealthyFrame = {
            reportVerifiedVideoOutput()
        },
        onSamplingUnavailable = {
            if (firstFrameRendered && !videoFailureReported && !videoOutputReported) {
                PlaybackLog.log(
                    context,
                    engineName,
                    "PixelCopy capability unavailable -> accept Media3 rendered-frame signal",
                )
                reportVerifiedVideoOutput()
            }
        },
    )

    // A successful first copy that caught a transient green/black surface followed
    // by PixelCopy errors used to leave TV playback covered forever: the short
    // "PixelCopy unavailable" escape hatch was disabled by that one classified
    // sample, while the green gate could no longer gather enough evidence to decide.
    // Every rendered first frame therefore has an absolute validation deadline.
    private val surfaceValidationDeadlineRunnable = Runnable {
        if (
            expectsVideo &&
            firstFrameRendered &&
            !videoFailureReported &&
            !videoOutputReported
        ) {
            reportVideoInvalid("video surface could not be validated before deadline")
        }
    }

    private val videoProgressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (
                !videoFailureReported &&
                firstFrameRendered &&
                p.playWhenReady &&
                p.playbackState == Player.STATE_READY &&
                p.videoFormat != null
            ) {
                val lastFrame = lastVideoFrameAtMs.get()
                if (
                    lastFrame > 0L &&
                    SystemClock.elapsedRealtime() - lastFrame >= VIDEO_FRAME_STALL_MS
                ) {
                    reportVideoInvalid(
                        "decoder stopped producing video frames for ${VIDEO_FRAME_STALL_MS}ms",
                    )
                    return
                }
            }
            handler.postDelayed(this, VIDEO_HEALTH_POLL_MS)
        }
    }

    override fun bind(container: ViewGroup) {
        // Buffer durations from the user's "Buffer size" setting (smaller = faster
        // zap, larger = fewer stalls on jittery links).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ bufferMode.exoMinBufferMs,
                /* maxBufferMs = */ bufferMode.exoMaxBufferMs,
                /* bufferForPlaybackMs = */ bufferMode.exoPlaybackMs,
                /* bufferForPlaybackAfterRebufferMs = */ bufferMode.exoRebufferMs
            )
            .build()

        // Identify every HTTP(S) stream pull as KULULUPLAY (some providers gate
        // playback on the User-Agent).
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(AppInfo.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        // Explicitly disable video tunneling — it commonly causes green/black
        // frames on cheap Android TV sticks.
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(false))
        }

        val exo = ExoPlayer.Builder(context, buildRenderersFactory())
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()

        // Surface-size diagnostics are harmless across item changes. Playback
        // health events use AnalyticsListener below because its EventTime carries
        // the media item identity; Player.Listener does not, so a late READY/error
        // from the previous channel could otherwise validate the new channel.
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                PlaybackLog.log(
                    context, engineName,
                    "onVideoSizeChanged ${videoSize.width}x${videoSize.height} " +
                        "par=${videoSize.pixelWidthHeightRatio}"
                )
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                PlaybackLog.log(context, engineName, "onSurfaceSizeChanged ${width}x$height")
                if (
                    width > 0 &&
                    height > 0 &&
                    (width != lastSurfaceWidth || height != lastSurfaceHeight)
                ) {
                    lastSurfaceWidth = width
                    lastSurfaceHeight = height
                    if (firstFrameRendered) {
                        surfaceFrameHealth.onOutputTransition()
                        if (!videoOutputReported) {
                            handler.removeCallbacks(surfaceValidationDeadlineRunnable)
                            handler.postDelayed(
                                surfaceValidationDeadlineRunnable,
                                PIXEL_VALIDATION_DEADLINE_MS,
                            )
                        }
                        droppedFrameHealth.onOutputTransition(
                            SystemClock.elapsedRealtime(),
                        )
                    }
                }
            }
        })

        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlaybackStateChanged(
                eventTime: AnalyticsListener.EventTime,
                state: Int,
            ) {
                if (!isCurrentEvent(eventTime)) return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        readyForPlayback = false
                        cancelNoFrameCheck()
                        listener?.onBuffering()
                    }
                    Player.STATE_READY -> {
                        readyForPlayback = true
                        // Give video a fresh grace period after a long network
                        // rebuffer before judging the prior frame timestamp stale.
                        if (firstFrameRendered) {
                            lastVideoFrameAtMs.set(SystemClock.elapsedRealtime())
                        }
                        // READY can be driven by audio while video is unsupported,
                        // green or still waiting for a frame. Radio may be reported
                        // immediately; TV is reported only after verified pixels.
                        if (!expectsVideo || videoOutputReported) {
                            listener?.onPlaying()
                        }
                        scheduleTrackSupportCheck()
                        scheduleNoFrameCheck()
                        scheduleVideoProgressCheck()
                    }
                    Player.STATE_ENDED -> {
                        readyForPlayback = false
                        cancelNoFrameCheck()
                        listener?.onEnded()
                    }
                    Player.STATE_IDLE -> {
                        readyForPlayback = false
                        cancelNoFrameCheck()
                    }
                }
            }

            override fun onTracksChanged(
                eventTime: AnalyticsListener.EventTime,
                tracks: Tracks,
            ) {
                if (!isCurrentEvent(eventTime)) return
                scheduleTrackSupportCheck()
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                if (!isCurrentEvent(eventTime)) return
                firstFrameRendered = true
                val nowMs = SystemClock.elapsedRealtime()
                lastVideoFrameAtMs.set(nowMs)
                droppedFrameHealth.onFirstFrame(nowMs)
                cancelNoFrameCheck()
                PlaybackLog.log(context, engineName, "onRenderedFirstFrame")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    surfaceFrameHealth.start {
                        findVideoSurface(playerView?.videoSurfaceView ?: playerView)
                    }
                    handler.removeCallbacks(surfaceValidationDeadlineRunnable)
                    handler.postDelayed(
                        surfaceValidationDeadlineRunnable,
                        PIXEL_VALIDATION_DEADLINE_MS,
                    )
                } else {
                    reportVerifiedVideoOutput()
                }
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException,
            ) {
                if (!isCurrentEvent(eventTime)) return
                PlaybackLog.log(context, engineName, "onPlayerError ${error.errorCodeName}")
                val exoError = error as? ExoPlaybackException
                val rendererType =
                    if (
                        exoError?.type == ExoPlaybackException.TYPE_RENDERER &&
                        exoError.rendererIndex >= 0
                    ) {
                        runCatching { exo.getRendererType(exoError.rendererIndex) }.getOrNull()
                    } else {
                        null
                    }
                when (
                    ExoPlaybackFailureClassifier.classifyError(
                        errorCode = error.errorCode,
                        rendererType = rendererType,
                    )
                ) {
                    ExoPlaybackFailureClassifier.Failure.AUDIO ->
                        reportAudioUnavailable(error.errorCodeName)
                    ExoPlaybackFailureClassifier.Failure.DECODE ->
                        reportDecodeFailure(error.errorCodeName)
                    ExoPlaybackFailureClassifier.Failure.ERROR ->
                        if (isSourceOrManifestFailure(error.errorCode)) {
                            listener?.onSourceFailure(
                                error.errorCodeName,
                                findHttpStatus(error),
                            )
                        } else {
                            listener?.onError(error.errorCodeName)
                        }
                }
            }
        })

        // This callback advances for every video frame submitted for rendering.
        // Unlike the media clock it stops when video decoding/output freezes while
        // audio keeps playing, which lets the health watchdog recover that case.
        exo.setVideoFrameMetadataListener(
            VideoFrameMetadataListener { _, _, _, _ ->
                lastVideoFrameAtMs.set(SystemClock.elapsedRealtime())
            },
        )

        // Decoder/codec tracing so the next on-device log pins the ~1s drop:
        // decoder name, the (interlaced?) input format, dropped-frame bursts and
        // any codec error distinguish interlaced field decoding from a surface
        // recreation from a decoder error.
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (!isCurrentEvent(eventTime)) return
                PlaybackLog.log(context, engineName, "videoDecoder=$decoderName")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                if (!isCurrentEvent(eventTime)) return
                hardwareDecodeRequired =
                    hardwareDecodeRequired ||
                    VlcHardwareDevicePolicy.requiresHardwareDecode(
                        width = format.width,
                        height = format.height,
                    )
                PlaybackLog.log(
                    context, engineName,
                    "videoInputFormat ${format.width}x${format.height} " +
                        "${format.sampleMimeType} fps=${format.frameRate}"
                )
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                if (!isCurrentEvent(eventTime)) return
                PlaybackLog.log(context, engineName, "droppedVideoFrames=$droppedFrames/${elapsedMs}ms")
                // UHD software decode is categorically slower than real time on
                // TV sticks. A drop callback alone must not evict a healthy
                // MediaCodec path into that impossible route; real codec errors,
                // solid output and the frame-stall watchdog remain authoritative.
                if (hardwareDecodeRequired) {
                    PlaybackLog.log(
                        context,
                        engineName,
                        "UHD drop batch observed; keep hardware path pending real output failure",
                    )
                    return
                }
                val breach = droppedFrameHealth.onDroppedFrames(
                    nowMs = SystemClock.elapsedRealtime(),
                    droppedFrames = droppedFrames,
                    elapsedMs = elapsedMs,
                )
                if (breach != null) {
                    reportVideoInvalid(
                        "excessive frame loss ${breach.droppedFrames} frames/" +
                            "${breach.windowMs}ms",
                    )
                }
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception
            ) {
                if (!isCurrentEvent(eventTime)) return
                PlaybackLog.log(context, engineName, "videoCodecError ${videoCodecError.message}")
                reportDecodeFailure(
                    videoCodecError.message ?: videoCodecError.javaClass.simpleName,
                )
            }
        })

        // Inflated from XML with a SurfaceView surface (app:surface_type): the
        // only surface type that can show the hardware-decoded underlay plane on
        // Amlogic/most TV SoCs without greening out.
        val view = (LayoutInflater.from(context)
            .inflate(R.layout.view_exo_player, container, false) as PlayerView).apply {
            this.player = exo
        }
        container.addView(view)

        player = exo
        playerView = view
        applyPreferredTrackLanguages()
    }

    /**
     * Renderers tuned so audio always reaches the speakers. Many Android TV boxes
     * advertise Dolby/DTS passthrough over HDMI even when nothing downstream can
     * decode it, so ExoPlayer hands the bitstream off and you get a picture but
     * silence — and because that is not an error, no fallback ever kicks in.
     *
     * Forcing [AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES] (stereo PCM only)
     * disables passthrough, so audio is decoded to PCM on-device and audible.
     * Decoder fallback stays enabled for every setting. This flag is not a
     * hardware/software policy: it only allows Media3 to try another compatible
     * MediaCodec when the first codec cannot initialise. Disabling it was the
     * reason Exo + Hardware failed wholesale on some TV devices.
     */
    private fun buildRenderersFactory(): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            @Suppress("DEPRECATION", "UNUSED_PARAMETER")
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // In Media3 1.8.x setAudioCapabilities() is intentionally ignored
                // by the context-aware Builder because it derives HDMI capabilities
                // from the device. Use the legacy context-free builder only for the
                // explicit PCM-safe path; its documented default has no encoded
                // passthrough support. Passthrough opt-in keeps the modern
                // context-aware builder and the real HDMI capabilities.
                val builder = if (allowPassthrough) {
                    DefaultAudioSink.Builder(context)
                } else {
                    DefaultAudioSink.Builder()
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                }
                builder
                    .setEnableFloatOutput(enableFloatOutput)
                    // Live TV always runs at 1x. Vendor AudioTrack implementations
                    // on Amlogic/Xiaomi/Fire TV frequently expose stale/retrograde
                    // timestamps when playback-params mode is active. Let Media3
                    // use its stable default clock path instead.
                    .setEnableAudioTrackPlaybackParams(false)
                return builder.build()
            }
        }.setEnableDecoderFallback(true)

    /**
     * Track selection can momentarily be unresolved while the manifest/extractor
     * settles. Re-read after READY and classify the selected *and supported* paths;
     * checking only Group.isSelected misses selected formats that exceed the device
     * capabilities and results in silent AC-3/E-AC-3/MP2 playback.
     */
    private fun scheduleTrackSupportCheck(
        delayMs: Long = TRACK_SUPPORT_CHECK_DELAY_MS,
        selectionSettled: Boolean = false,
    ) {
        cancelTrackSupportCheck()
        val generation = streamGeneration
        val check = Runnable {
            trackSupportCheckRunnable = null
            if (generation != streamGeneration) return@Runnable
            val exo = player ?: return@Runnable
            if (exo.playbackState != Player.STATE_READY) return@Runnable
            val pendingSelection = checkTrackSupport(
                tracks = exo.currentTracks,
                selectionSettled = selectionSettled,
            )
            if (
                pendingSelection &&
                !selectionSettled &&
                generation == streamGeneration &&
                !videoFailureReported &&
                !audioReported
            ) {
                PlaybackLog.log(
                    context,
                    engineName,
                    "supported track not selected yet -> wait for live-TS metadata",
                )
                scheduleTrackSupportCheck(
                    delayMs = TRACK_PENDING_RECHECK_DELAY_MS,
                    selectionSettled = true,
                )
            }
        }
        trackSupportCheckRunnable = check
        handler.postDelayed(check, delayMs)
    }

    /**
     * Analytics events retain the timeline/item they belong to, even if a fast
     * DPAD zap has already installed another MediaItem. Only the active item's
     * callbacks may mutate health/readiness state.
     */
    private fun isCurrentEvent(eventTime: AnalyticsListener.EventTime): Boolean {
        val expected = activeMediaId ?: return false
        val timeline = eventTime.timeline
        val windowIndex = eventTime.windowIndex
        if (windowIndex < 0 || windowIndex >= timeline.windowCount) return false
        val mediaId = runCatching {
            timeline.getWindow(windowIndex, Timeline.Window()).mediaItem.mediaId
        }.getOrNull()
        return mediaId == expected
    }

    private fun cancelTrackSupportCheck() {
        trackSupportCheckRunnable?.let(handler::removeCallbacks)
        trackSupportCheckRunnable = null
    }

    private fun checkTrackSupport(
        tracks: Tracks,
        selectionSettled: Boolean,
    ): Boolean {
        val groups = tracks.groups.map { group ->
            ExoPlaybackFailureClassifier.TrackGroupState(
                type = group.type,
                selected = List(group.length) { index -> group.isTrackSelected(index) },
                // DefaultTrackSelector may deliberately select a format that
                // exceeds conservative capability declarations. If Media3 still
                // selected it, allow that path to prove itself by frame/error
                // evidence instead of forcing a false fallback after 1.2 seconds.
                supported =
                    List(group.length) { index ->
                        group.isTrackSupported(index, /* allowExceedsCapabilities = */ true)
                    },
            )
        }
        when (
            ExoPlaybackFailureClassifier.classifyTracks(
                groups = groups,
                expectsVideo = expectsVideo,
                selectionSettled = selectionSettled,
            )
        ) {
            ExoPlaybackFailureClassifier.Failure.AUDIO ->
                reportAudioUnavailable(
                    if (selectionSettled) {
                        "audio track unavailable after settle timeout"
                    } else {
                        "audio track unsupported"
                    },
                )
            ExoPlaybackFailureClassifier.Failure.DECODE ->
                reportDecodeFailure(
                    if (selectionSettled) {
                        "video track unavailable after settle timeout"
                    } else {
                        "video track unsupported"
                    },
                )
            ExoPlaybackFailureClassifier.Failure.ERROR,
            null -> Unit
        }
        return ExoPlaybackFailureClassifier.hasPendingSupportedSelection(
            groups = groups,
            expectsVideo = expectsVideo,
        )
    }

    private fun reportAudioUnavailable(detail: String) {
        if (audioReported) return
        audioReported = true
        cancelTrackSupportCheck()
        PlaybackLog.log(context, engineName, "$detail -> audio compatibility fallback")
        listener?.onAudioUnavailable()
    }

    private fun isSourceOrManifestFailure(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> true
        else -> false
    }

    private fun findHttpStatus(error: Throwable): Int? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
        }
        return null
    }

    private fun reportDecodeFailure(detail: String) {
        if (videoFailureReported) return
        videoFailureReported = true
        surfaceFrameHealth.reset()
        droppedFrameHealth.reset()
        cancelTrackSupportCheck()
        cancelNoFrameCheck()
        handler.removeCallbacks(surfaceValidationDeadlineRunnable)
        handler.removeCallbacks(videoProgressRunnable)
        PlaybackLog.log(context, engineName, "$detail -> decoder compatibility fallback")
        listener?.onDecodeError(detail)
    }

    private fun scheduleNoFrameCheck() {
        cancelNoFrameCheck()
        if (!expectsVideo || firstFrameRendered || videoFailureReported) return

        val generation = streamGeneration
        val check = Runnable {
            noFrameCheckRunnable = null
            if (
                generation != streamGeneration ||
                !expectsVideo ||
                firstFrameRendered ||
                videoFailureReported
            ) {
                return@Runnable
            }
            // READY can be driven by a playable audio renderer. TV playback still
            // requires a video frame, even when Media3 exposes no videoFormat
            // because the device could not select a video decoder at all.
            if (player?.playbackState == Player.STATE_READY) {
                reportVideoInvalid("no first frame for expected video")
            }
        }
        noFrameCheckRunnable = check
        handler.postDelayed(check, NO_FRAME_TIMEOUT_MS)
    }

    private fun cancelNoFrameCheck() {
        noFrameCheckRunnable?.let(handler::removeCallbacks)
        noFrameCheckRunnable = null
    }

    private fun scheduleVideoProgressCheck() {
        handler.removeCallbacks(videoProgressRunnable)
        handler.postDelayed(videoProgressRunnable, VIDEO_HEALTH_POLL_MS)
    }

    private fun reportVideoInvalid(detail: String) {
        if (videoFailureReported) return
        videoFailureReported = true
        surfaceFrameHealth.reset()
        droppedFrameHealth.reset()
        cancelTrackSupportCheck()
        cancelNoFrameCheck()
        handler.removeCallbacks(surfaceValidationDeadlineRunnable)
        handler.removeCallbacks(videoProgressRunnable)
        PlaybackLog.log(context, engineName, "$detail -> compatibility fallback")
        listener?.onVideoInvalid()
    }

    private fun reportVerifiedVideoOutput() {
        if (videoFailureReported || videoOutputReported) return
        videoOutputReported = true
        handler.removeCallbacks(surfaceValidationDeadlineRunnable)
        PlaybackLog.log(context, engineName, "healthy SurfaceView frame confirmed")
        // The controller uses onPlaying as its engine-success signal. For TV that
        // signal is intentionally withheld until this verified pixel output.
        if (readyForPlayback) {
            listener?.onPlaying()
        }
        listener?.onVideoOutput()
    }

    /**
     * Remove the PlayerView from its parent while keeping the ExoPlayer instance
     * attached to it, so decoding/buffering keep running during the hand-off. The
     * same PlayerView is re-homed on [attachVideo] (no new surface inflation).
     */
    override fun detachVideo() {
        playerView?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    /**
     * Re-add the existing PlayerView into [container] without recreating the
     * player or the surface. Called after [detachVideo]; the controller inserts a
     * short gap first so the old SurfaceView tears down before the new one is
     * composited (Amlogic green-on-fresh-surface lesson).
     */
    override fun attachVideo(container: ViewGroup) {
        val view = playerView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view)
    }

    override fun play(url: String, reset: Boolean) {
        val exo = player ?: return
        val mediaId = resetHealth()
        PlaybackLog.log(context, engineName, "play passthrough=$allowPassthrough reset=$reset")
        // reset=true (channel-change zap AND preview<->fullscreen hand-off): stop()
        // before swapping the media so ExoPlayer rebuilds the video codec for the
        // new stream. This is REQUIRED for correctness across a resolution change: a
        // no-stop setMediaItem+prepare can leave the Amlogic OMX decoder locked to
        // the previous channel's geometry (1080p<->720x576), so the new size never
        // renders and the picture freezes. stop() resets only the decoder/renderer
        // onto the SAME, already-attached SurfaceView — the surface is NOT recreated,
        // so there's no Amlogic green. It also fixes the hand-off "renderer
        // audio-ready but video-stalled (black with sound)" case.
        //
        // The codec reconfigure costs a short black gap (~600ms on Amlogic OMX) on a
        // zap, but that is far better than a permanently frozen picture on every
        // channel whose resolution differs from the one fullscreen started on.
        if (reset) exo.stop()
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(url)
                .setMediaId(mediaId)
                .build(),
        )
        exo.playWhenReady = true
        exo.prepare()
        listener?.onPlaybackSubmitted()
    }

    private fun resetHealth(): String {
        handler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        droppedFrameHealth.reset()
        streamGeneration += 1
        activeMediaId = "live-$streamGeneration"
        firstFrameRendered = false
        videoFailureReported = false
        videoOutputReported = false
        audioReported = false
        readyForPlayback = false
        hardwareDecodeRequired = false
        lastSurfaceWidth = 0
        lastSurfaceHeight = 0
        trackSupportCheckRunnable = null
        noFrameCheckRunnable = null
        lastVideoFrameAtMs.set(0L)
        return checkNotNull(activeMediaId)
    }

    override fun pause() { player?.playWhenReady = false }
    override fun resume() { player?.playWhenReady = true }
    override fun stop() { player?.stop() }

    // Read on the main thread (Media3 requires it); the controller's stall
    // watchdog polls from a main-looper Handler. Advances during live playback,
    // freezes on a silent source stall.
    override fun playbackPositionMs(): Long = player?.currentPosition ?: -1L

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        droppedFrameHealth.reset()
        streamGeneration += 1
        activeMediaId = null
        val retiredView = playerView
        // Disconnect the render surface before releasing the codec. Several TV
        // vendor MediaCodec implementations otherwise retain a destroyed view
        // across the next engine/channel and fail configure with BAD_VALUE.
        retiredView?.player = null
        player?.release()
        (retiredView?.parent as? ViewGroup)?.removeView(retiredView)
        player = null
        playerView = null
        listener = null
    }

    override fun setListener(listener: PlayerListener?) {
        this.listener = listener
    }

    /**
     * Stream info for the diagnostics overlay, read from the selected video
     * Format. Media3 exposes every field safely: width/height, frameRate
     * (NO_VALUE when unknown), the sample MIME type (mapped to a friendly codec
     * name) and the peak/average bitrate (bits/s -> kbps).
     */
    override fun getStreamInfo(): StreamInfo? {
        val fmt = player?.videoFormat ?: return null
        val fps = if (fmt.frameRate > 0f) fmt.frameRate else 0f
        val bits = fmt.peakBitrate.takeIf { it > 0 } ?: fmt.averageBitrate.takeIf { it > 0 }
        return StreamInfo(
            width = fmt.width.coerceAtLeast(0),
            height = fmt.height.coerceAtLeast(0),
            fps = fps,
            codec = codecLabel(fmt.sampleMimeType),
            bitrateKbps = bits?.let { it / 1000 },
            engine = engineName
        )
    }

    override fun audioTracks(): List<PlayerTrack> = tracksOfType(C.TRACK_TYPE_AUDIO)

    override fun subtitleTracks(): List<PlayerTrack> = tracksOfType(C.TRACK_TYPE_TEXT)

    private fun tracksOfType(type: Int): List<PlayerTrack> {
        val tracks = player?.currentTracks ?: return emptyList()
        return buildList {
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != type) return@forEachIndexed
                repeat(group.length) { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    val language = TrackLanguage.normalize(format.language ?: format.label)
                    val label = format.label
                        ?: format.language
                        ?: language?.uppercase(Locale.ROOT)
                        ?: "Track ${trackIndex + 1}"
                    add(
                        PlayerTrack(
                            id = "$type:$groupIndex:$trackIndex",
                            label = label,
                            language = language,
                            selected = group.isTrackSelected(trackIndex),
                        ),
                    )
                }
            }
        }
    }

    override fun selectAudioTrack(id: String): Boolean =
        selectTrack(C.TRACK_TYPE_AUDIO, id)

    override fun selectSubtitleTrack(id: String?): Boolean {
        val exo = player ?: return false
        if (id == null) {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return true
        }
        return selectTrack(C.TRACK_TYPE_TEXT, id)
    }

    private fun selectTrack(type: Int, id: String): Boolean {
        val exo = player ?: return false
        val parts = id.split(':')
        if (parts.size != 3 || parts[0].toIntOrNull() != type) return false
        val groupIndex = parts[1].toIntOrNull() ?: return false
        val trackIndex = parts[2].toIntOrNull() ?: return false
        val group = exo.currentTracks.groups.getOrNull(groupIndex) ?: return false
        if (group.type != type || trackIndex !in 0 until group.length) return false
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, false)
            .addOverride(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)),
            )
            .build()
        return true
    }

    override fun setPreferredTrackLanguages(audio: String?, subtitle: String?) {
        preferredAudioLanguage = TrackLanguage.normalize(audio)
        LiveSubtitlePreference.Language.from(subtitle)?.let { subtitlePreference = it }
        applyPreferredTrackLanguages()
    }

    override fun setSubtitlePreference(preference: LiveSubtitlePreference): Boolean {
        subtitlePreference = preference
        applyPreferredTrackLanguages()
        return player != null
    }

    private fun applyPreferredTrackLanguages() {
        val exo = player ?: return
        var builder = exo.trackSelectionParameters
            .buildUpon()
            .setPreferredAudioLanguage(preferredAudioLanguage)
        builder = when (val preference = subtitlePreference) {
            LiveSubtitlePreference.Auto -> builder
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguage(null)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            LiveSubtitlePreference.Off -> builder
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguage(null)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            is LiveSubtitlePreference.Language -> builder
                .setPreferredTextLanguage(preference.code)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        exo.trackSelectionParameters = builder.build()
    }

    private fun codecLabel(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "H.265"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_MP4V -> "MPEG-4"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_AV1 -> "AV1"
        else -> mime.substringAfter('/').uppercase()
    }

    companion object {
        /** Debounce after a track change before judging resolved track support. */
        private const val TRACK_SUPPORT_CHECK_DELAY_MS = 1200L
        /** Extra settle time for a supported live-TS track not selected yet. */
        private const val TRACK_PENDING_RECHECK_DELAY_MS = 2_800L
        /** If no frame renders this long after READY, treat video as failed. */
        private const val NO_FRAME_TIMEOUT_MS = 8000L
        private const val VIDEO_HEALTH_POLL_MS = 2000L
        private const val VIDEO_FRAME_STALL_MS = 7000L
        private const val PIXEL_VALIDATION_DEADLINE_MS = 9_000L
    }
}
