/*
 * ExoPlayerEngine.kt
 * Primary playback backend using AndroidX Media3 (ExoPlayer). Handles MPEG-TS/
 * progressive automatically. Tuned for fast live startup with small buffers.
 *
 * Real-stick hardening (emulator hides these because it decodes in software):
 *   - Audio prefers decoded PCM (passthrough disabled by default); codec
 *     availability is checked below. Tunneling is off unless ExoTunnelingPolicy
 *     enables it (remote opt-in, never Amlogic: a common green/black cause).
 *   - Production builds bundle the Media3 FFmpeg audio extension (see
 *     FfmpegAudio / docs/ffmpeg-audio.md): MP2/MP3/AAC/AC-3/E-AC-3/DTS/Opus/
 *     Vorbis/FLAC/ALAC/MLP/TrueHD decode in software AFTER the platform codecs
 *     while keeping hardware video. A bundled JLayer MPEG-1 renderer stays last
 *     for builds without the extension. Anything still unsupported reports
 *     onAudioUnavailable so the controller can fall back to libVLC: the track
 *     support check below reads aggregated renderer capabilities, so it needs
 *     no FFmpeg-specific branch.
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
import android.app.ActivityManager
import android.net.Uri
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import com.iptv.player.BuildConfig
import com.iptv.player.R
import com.iptv.player.data.model.BufferMode
import com.iptv.player.playback.android.PlaybackQoeRuntime
import com.iptv.player.playback.core.AudioFailureEvidence
import com.iptv.player.util.AppInfo
import com.iptv.player.util.PlaybackLog
import com.iptv.player.util.PlaybackRemotePolicy
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
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
    private val initialRebuffers: Int = 0,
    private val constrainedDevice: Boolean =
        PlaybackQoeRuntime.devicePlaybackProfile().compatibilityMode ||
            runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                    ?.isLowRamDevice == true
            }.getOrDefault(false),
    /** Audio-only preference on constrained or known Amlogic compatibility paths. */
    private val preferSoftwareAudio: Boolean = constrainedDevice,
    /**
     * Video tunneling. Off by default and off on every Amlogic decoder (green/
     * black frames); only [ExoTunnelingPolicy] may turn it on per device.
     */
    private val tunnelingEnabled: Boolean = false,
) : PlayerEngine {

    override val engineName: String = "ExoPlayer"
    override val supportsPreciseSourceErrors: Boolean = true

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val playbackClockWindow = Timeline.Window()
    private var listener: PlayerListener? = null
    @Volatile private var playbackAttemptId: String? = null

    override fun setPlaybackAttemptId(id: String?) {
        playbackAttemptId = PlaybackAttemptTrace.safeId(id)
    }
    private var preferredAudioLanguage: String? = null
    private var subtitlePreference: LiveSubtitlePreference = LiveSubtitlePreference.Auto

    private val handler = Handler(Looper.getMainLooper())
    // Where the FFmpeg software audio renderers (CI-built extension; absent in
    // local builds) sit relative to the platform MediaCodec audio renderer.
    private val ffmpegPlan = FfmpegAudioRendererOrder.plan(
        ffmpegAvailable = FfmpegAudio.available,
        preferSoftwareAudio = preferSoftwareAudio,
        allowPassthrough = allowPassthrough,
    )
    // Absent in normal builds. This observer never feeds playback/health policy.
    private val playbackDiagnosticGate = if (BuildConfig.LIVE_PLAYBACK_DIAGNOSTICS) {
        LivePlaybackDiagnosticGate()
    } else {
        null
    }
    private var playbackDiagnosticSampler: Runnable? = null
    private var diagnosticAudioReady: Boolean? = null
    private var diagnosticVideoReady: Boolean? = null
    private val diagnosticLastVideoFrameAtMs = if (BuildConfig.LIVE_PLAYBACK_DIAGNOSTICS) {
        AtomicLong(0L)
    } else {
        null
    }
    // Per-stream health flags so each detection fires at most once.
    private var firstFrameRendered = false
    private var videoFailureReported = false
    private var videoOutputReported = false
    private var audioReported = false
    private var audioCodec = AudioFailureEvidence.Codec.UNKNOWN
    private var audioDecoder = AudioFailureEvidence.Decoder.UNKNOWN
    private var audioDecoderInitialized = false
    private var audioClockStarted = false
    private val audioUnderrunMonitor = AudioUnderrunMonitor()
    private var audioBufferingSinceMs = 0L
    private var audioBufferingHadMediaProgress = false
    private var audioBufferingVideoBaselineMs = 0L
    private var audioBufferingDurationBaselineMs = 0L
    private var audioUnderrunVideoBaselineMs = 0L
    private var audioUnderrunBufferBaselineMs = 0L
    private var audioUnderrunCheckRunnable: Runnable? = null
    private var audioStallCheckRunnable: Runnable? = null
    private val lastVideoFrameAtMs = AtomicLong(0L)
    private val frameRateEstimator = FrameRateEstimator()
    private var lastHealthPositionMs = -1L
    private var readyForPlayback = false
    private var streamGeneration = 0L
    private var activeMediaId: String? = null
    private var trackSupportCheckRunnable: Runnable? = null
    private var noFrameCheckRunnable: Runnable? = null
    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0
    private val droppedFrameHealth = DroppedFrameRecoveryGate()
    private val surfaceReadbackPolicy = SurfaceReadbackPolicy(constrainedDevice)
    // Own handler: [handler] is cleared on every zap/release, but a pending
    // socket-close completion must still reach the controller.
    private val providerConnection = ExoProviderConnection(Handler(Looper.getMainLooper()))

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
        // Even a tiny destination can require a full-resolution GPU readback on
        // old gralloc implementations. Verify startup/reattach, then let native
        // frame and clock evidence monitor ongoing playback on constrained TVs.
        continueAfterHealthy = !constrainedDevice,
        allowPeriodicSampling = {
            surfaceReadbackPolicy.mode == SurfaceReadbackPolicy.Mode.CONTINUOUS
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
            val position = exoPlaybackClockPositionMs(p, playbackClockWindow).coerceAtLeast(0L)
            val clockAdvance = if (lastHealthPositionMs >= 0L) {
                (position - lastHealthPositionMs).coerceAtLeast(0L)
            } else {
                0L
            }
            lastHealthPositionMs = position
            val lastFrame = lastVideoFrameAtMs.get()
            val decision = LiveVideoLivenessPolicy.classify(
                evidence = liveVideoEvidence(
                    player = p,
                    firstFrameRendered = firstFrameRendered && p.videoFormat != null,
                    videoFailureReported = videoFailureReported,
                    clockAdvanceMs = clockAdvance,
                    frameAgeMs = lastFrame.takeIf { it > 0L }?.let {
                        SystemClock.elapsedRealtime() - it
                    },
                ),
                frameTimeoutMs = VIDEO_FRAME_STALL_MS,
                minimumClockAdvanceMs = VIDEO_CLOCK_EVIDENCE_MS,
            )
            if (decision == LiveVideoLivenessPolicy.Decision.VIDEO_STALL) {
                    reportVideoInvalid(
                        "decoder stopped producing video frames for ${VIDEO_FRAME_STALL_MS}ms",
                    )
                    return
            }
            handler.postDelayed(this, VIDEO_HEALTH_POLL_MS)
        }
    }

    override fun bind(container: ViewGroup) {
        val loadControl = LiveLoadControl(bufferMode, constrainedDevice, initialRebuffers)
        PlaybackLog.log(
            context, engineName,
            "liveBuffer=$bufferMode constrained=$constrainedDevice priorRebuffers=$initialRebuffers",
        )

        // Identify every HTTP(S) stream pull as KULULUPLAY (some providers gate
        // playback on the User-Agent).
        val httpDataSourceFactory = DataSource.Factory {
            // Snapshot before opening: a retired transfer must keep its old id/listener.
            val attemptId = playbackAttemptId
            val observer = listener
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(AppInfo.USER_AGENT)
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(attemptId?.let {
                    mapOf("X-Kululu-Playback-Attempt" to it)
                } ?: emptyMap())
                .createDataSource().apply {
                    addTransferListener(object : TransferListener {
                        private var receivedBytes = false
                        override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) {
                            if (network) observer?.onTransportConnecting()
                        }
                        override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) = Unit
                        override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, bytes: Int) {
                            if (network && bytes > 0 && !receivedBytes) {
                                receivedBytes = true
                                observer?.onTransportBytes()
                            }
                        }
                        override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) = Unit
                    })
                }
            // Track the provider socket so stop/release can force-close it and
            // wait for the real close boundary (Media3 only cancels the Loader).
            providerConnection.wrap(http)
        }
        val mediaSourceFactory = ProgressiveMediaSource.Factory(
            DirectMediaDataSource.Factory(httpDataSourceFactory),
            ExtractorsFactory { arrayOf(TsExtractor()) },
        )

        // Video tunneling stays off unless a remote per-device opt-in AND a
        // non-Amlogic decoder were both proven (ExoTunnelingPolicy); it commonly
        // causes green/black frames on cheap Android TV sticks.
        val deviceProfile = PlaybackQoeRuntime.devicePlaybackProfile()
        if (tunnelingEnabled) PlaybackLog.log(context, engineName, "video tunneling enabled (remote opt-in)")
        val trackSelector = DefaultTrackSelector(context).apply {
            val parameters = buildUponParameters()
                .setTunnelingEnabled(tunnelingEnabled)
                .apply {
                    // These are adaptive preferences, not hard source rejection:
                    // DefaultTrackSelector's exceed-video-constraints fallback
                    // remains enabled, so a fixed/single-rendition channel still
                    // plays when it is the only available track.
                    deviceProfile.adaptiveMaxHeight?.let { maxHeight ->
                        setMaxVideoSize(maxHeight * 16 / 9, maxHeight)
                    }
                    deviceProfile.adaptiveMaxFrameRate?.let(::setMaxVideoFrameRate)
                }
            setParameters(parameters)
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
                    revalidateVideoOutput()
                }
            }
        })

        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlaybackStateChanged(
                eventTime: AnalyticsListener.EventTime,
                state: Int,
            ) {
                if (!isCurrentEvent(eventTime)) return
                if (playbackDiagnosticGate != null) {
                    recordPlaybackDiagnostic(
                        event = when (state) {
                            Player.STATE_IDLE -> PlaybackDiagnosticEvent.STATE_IDLE
                            Player.STATE_BUFFERING -> PlaybackDiagnosticEvent.STATE_BUFFERING
                            Player.STATE_READY -> PlaybackDiagnosticEvent.STATE_READY
                            Player.STATE_ENDED -> PlaybackDiagnosticEvent.STATE_ENDED
                            else -> PlaybackDiagnosticEvent.STATE_UNKNOWN
                        },
                        eventTime = eventTime,
                    )
                }
                when (state) {
                    Player.STATE_BUFFERING -> {
                        readyForPlayback = false
                        cancelNoFrameCheck()
                        scheduleAudioClockStallCheck()
                        listener?.onBuffering()
                    }
                    Player.STATE_READY -> {
                        readyForPlayback = true
                        cancelAudioClockStallCheck()
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
                        cancelAudioClockStallCheck()
                        cancelAudioUnderrunCheck()
                        listener?.onEnded()
                    }
                    Player.STATE_IDLE -> {
                        readyForPlayback = false
                        cancelNoFrameCheck()
                        cancelAudioClockStallCheck()
                        cancelAudioUnderrunCheck()
                        // A quiesced player must not keep polling video liveness
                        // or sampling the surface every 2 s.
                        cancelStreamHealthChecks()
                    }
                }
            }

            override fun onRendererReadyChanged(
                eventTime: AnalyticsListener.EventTime,
                rendererIndex: Int,
                trackType: Int,
                isReady: Boolean,
            ) {
                if (playbackDiagnosticGate == null || !isCurrentEvent(eventTime)) return
                val event = when (trackType) {
                    C.TRACK_TYPE_AUDIO -> {
                        diagnosticAudioReady = isReady
                        if (isReady) PlaybackDiagnosticEvent.AUDIO_READY
                        else PlaybackDiagnosticEvent.AUDIO_NOT_READY
                    }
                    C.TRACK_TYPE_VIDEO -> {
                        diagnosticVideoReady = isReady
                        if (isReady) PlaybackDiagnosticEvent.VIDEO_READY
                        else PlaybackDiagnosticEvent.VIDEO_NOT_READY
                    }
                    else -> return
                }
                recordPlaybackDiagnostic(event, eventTime, rendererIndex = rendererIndex)
            }

            override fun onIsLoadingChanged(
                eventTime: AnalyticsListener.EventTime,
                isLoading: Boolean,
            ) {
                if (playbackDiagnosticGate == null || !isCurrentEvent(eventTime)) return
                recordPlaybackDiagnostic(
                    if (isLoading) PlaybackDiagnosticEvent.LOADING else PlaybackDiagnosticEvent.NOT_LOADING,
                    eventTime,
                )
            }

            override fun onIsPlayingChanged(
                eventTime: AnalyticsListener.EventTime,
                isPlaying: Boolean,
            ) {
                if (playbackDiagnosticGate == null || !isCurrentEvent(eventTime)) return
                recordPlaybackDiagnostic(
                    if (isPlaying) PlaybackDiagnosticEvent.PLAYING else PlaybackDiagnosticEvent.NOT_PLAYING,
                    eventTime,
                )
            }

            override fun onTracksChanged(
                eventTime: AnalyticsListener.EventTime,
                tracks: Tracks,
            ) {
                if (!isCurrentEvent(eventTime)) return
                scheduleTrackSupportCheck()
            }

            override fun onPlayWhenReadyChanged(
                eventTime: AnalyticsListener.EventTime,
                playWhenReady: Boolean,
                reason: Int,
            ) {
                if (!isCurrentEvent(eventTime)) return
                if (playbackDiagnosticGate != null) {
                    recordPlaybackDiagnostic(
                        if (playWhenReady) PlaybackDiagnosticEvent.PLAY_WHEN_READY
                        else PlaybackDiagnosticEvent.PLAY_WHEN_NOT_READY,
                        eventTime,
                    )
                }
                if (!playWhenReady) {
                    cancelAudioClockStallCheck()
                    cancelAudioUnderrunCheck()
                } else if (exo.playbackState == Player.STATE_BUFFERING) {
                    scheduleAudioClockStallCheck()
                }
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
                if (exo.playbackState == Player.STATE_BUFFERING) {
                    scheduleAudioClockStallCheck()
                }
                PlaybackLog.log(context, engineName, "onRenderedFirstFrame")
                if (PlaybackRemotePolicy.snapshot().disablePixelCopyValidation) {
                    PlaybackLog.log(
                        context,
                        engineName,
                        "remote policy -> accept Media3 rendered-frame signal",
                    )
                    reportVerifiedVideoOutput()
                    return
                }
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
                if (playbackDiagnosticGate != null) {
                    recordPlaybackDiagnostic(
                        PlaybackDiagnosticEvent.PLAYER_ERROR,
                        eventTime,
                        errorCode = error.errorCode,
                        httpStatus = findHttpStatus(error),
                    )
                }
                PlaybackLog.log(context, engineName, "onPlayerError ${error.errorCodeName}")
                com.iptv.player.util.Logger.w(engineName, "onPlayerError ${error.errorCodeName}", error)
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
            VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                val nowMs = SystemClock.elapsedRealtime()
                lastVideoFrameAtMs.set(nowMs)
                frameRateEstimator.onFrame(presentationTimeUs)
                // Opt-in observation only: READY refreshes the health timestamp,
                // so diagnostics keep the actual frame timestamp separately.
                diagnosticLastVideoFrameAtMs?.set(nowMs)
            },
        )

        // Decoder/codec tracing so the next on-device log pins the ~1s drop:
        // decoder name, the (interlaced?) input format, dropped-frame bursts and
        // any codec error distinguish interlaced field decoding from a surface
        // recreation from a decoder error.
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (!isCurrentEvent(eventTime)) return
                audioDecoderInitialized = true
                audioDecoder = LiveAudioStallPolicy.decoderForName(decoderName)
                PlaybackLog.log(
                    context, engineName,
                    "audioDecoder=${audioDecoder.name} mpegPcm=${decoderName == MpegAudioDecoder.NAME}",
                )
                if (exo.playbackState == Player.STATE_BUFFERING) {
                    scheduleAudioClockStallCheck()
                }
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                if (!isCurrentEvent(eventTime)) return
                val nextCodec = LiveAudioStallPolicy.codecForMime(format.sampleMimeType)
                if (nextCodec != audioCodec) {
                    audioCodec = nextCodec
                    audioClockStarted = false
                    cancelAudioUnderrunCheck()
                    cancelAudioClockStallCheck()
                }
                PlaybackLog.log(
                    context,
                    engineName,
                    "audioFormat codec=${audioCodec.name} channels=${format.channelCount} " +
                        "rate=${format.sampleRate} output=${audioOutputMode().name}",
                )
                if (exo.playbackState == Player.STATE_BUFFERING) {
                    scheduleAudioClockStallCheck()
                }
            }

            override fun onAudioPositionAdvancing(
                eventTime: AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long,
            ) {
                if (!isCurrentEvent(eventTime)) return
                audioClockStarted = true
                cancelAudioClockStallCheck()
                // This is only the first advance after start/resume, not a
                // continuous health signal. The sink wrapper below observes
                // actual playout throughout an underrun recovery window.
                PlaybackLog.log(context, engineName, "audioClock=ADVANCING codec=${audioCodec.name}")
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                if (!isCurrentEvent(eventTime)) return
                val nowMs = SystemClock.elapsedRealtime()
                if (audioUnderrunMonitor.onUnderrun(nowMs)) {
                    audioUnderrunVideoBaselineMs = lastVideoFrameAtMs.get()
                    audioUnderrunBufferBaselineMs = exo.totalBufferedDuration
                }
                val observation = audioUnderrunMonitor.poll(nowMs)
                PlaybackLog.log(
                    context,
                    engineName,
                    "audioSink=UNDERRUN codec=${audioCodec.name} " +
                        "count=${observation.underruns} bufferMs=$bufferSizeMs " +
                        "feedGapMs=$elapsedSinceLastFeedMs",
                )
                // A 218 ms pair of callbacks on a working AC3 channel is one
                // short starvation episode, not proof that the decoder failed.
                scheduleAudioUnderrunCheck()
            }

            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception,
            ) {
                if (!isCurrentEvent(eventTime)) return
                PlaybackLog.log(context, engineName, "audioSink=SINK_ERROR codec=${audioCodec.name}")
                evaluateAudioStall(AudioFailureEvidence.SinkEvent.SINK_ERROR)
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception,
            ) {
                if (!isCurrentEvent(eventTime)) return
                PlaybackLog.log(context, engineName, "audioSink=CODEC_ERROR codec=${audioCodec.name}")
                evaluateAudioStall(AudioFailureEvidence.SinkEvent.CODEC_ERROR)
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (!isCurrentEvent(eventTime)) return
                val previousMode = surfaceReadbackPolicy.mode
                val decoderChanged = surfaceReadbackPolicy.onVideoDecoderInitialized(decoderName)
                if (decoderChanged) revalidateVideoOutput()
                logReadbackModeChange(previousMode)
                PlaybackLog.log(context, engineName, "videoDecoder=$decoderName")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                if (!isCurrentEvent(eventTime)) return
                val previousMode = surfaceReadbackPolicy.mode
                // A reused MediaCodec may not emit another initialized event.
                val decoderChanged = decoderReuseEvaluation?.decoderName
                    ?.let(surfaceReadbackPolicy::onVideoDecoderInitialized) == true
                val sourceChanged = surfaceReadbackPolicy.onVideoFormat(format.width, format.height)
                // A UHD decoder buffer can change while the display stays 1080p.
                // Never inherit old healthy pixels across a real source change.
                if (decoderChanged || sourceChanged) revalidateVideoOutput()
                logReadbackModeChange(previousMode)
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
                // Drops measure quality, not decoder failure. Evicting hardware
                // after a few lost 1080p50 frames can force an old stick into
                // CPU-bound software decoding. Codec errors and independently
                // confirmed frozen/invalid output still own recovery.
                val breach = droppedFrameHealth.onDroppedFrames(
                    nowMs = SystemClock.elapsedRealtime(),
                    droppedFrames = droppedFrames,
                    elapsedMs = elapsedMs,
                )
                if (breach != null) {
                    PlaybackLog.log(
                        context, engineName,
                        "sustained frame loss ${breach.droppedFrames}/${breach.windowMs}ms; " +
                            "await output evidence before decoder fallback",
                    )
                    listener?.onDroppedFrameBreach()
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

    private fun revalidateVideoOutput() {
        if (!firstFrameRendered) return
        surfaceFrameHealth.onOutputTransition()
        if (!videoOutputReported) {
            handler.removeCallbacks(surfaceValidationDeadlineRunnable)
            handler.postDelayed(surfaceValidationDeadlineRunnable, PIXEL_VALIDATION_DEADLINE_MS)
        }
        droppedFrameHealth.onOutputTransition(SystemClock.elapsedRealtime())
    }

    private fun logReadbackModeChange(previous: SurfaceReadbackPolicy.Mode) {
        if (previous != surfaceReadbackPolicy.mode) {
            PlaybackLog.log(
                context, engineName,
                "surfaceReadback=${surfaceReadbackPolicy.mode.name}; native frame watchdog retained",
            )
        }
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
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                super.buildAudioRenderers(
                    context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    audioSink, eventHandler, eventListener, out,
                )
                // FFmpeg software audio (when the CI-built extension is packaged)
                // sits AFTER the platform MediaCodec renderer: working hardware/
                // platform codecs keep priority and FFmpeg only claims formats
                // they reject (MP2/AC-3/E-AC-3/DTS/...). This is the audio-only
                // equivalent of EXTENSION_RENDERER_MODE_ON; video is untouched.
                // Constrained devices lead with a gated FFmpeg renderer for
                // Dolby so those streams decode to PCM predictably.
                val platform = ArrayList<Renderer>(out)
                out.clear()
                out.addAll(
                    FfmpegAudioRendererOrder.arrange(
                        plan = ffmpegPlan,
                        platform = platform,
                        leading = if (ffmpegPlan.hasLeading) {
                            FfmpegAudio.audioRenderers(context, eventHandler, eventListener, audioSink)
                                .map { CodecGatedAudioRenderer(it, ffmpegPlan.leadingMimes) }
                        } else {
                            emptyList()
                        },
                        trailing = if (ffmpegPlan.trailing) {
                            FfmpegAudio.audioRenderers(context, eventHandler, eventListener, audioSink)
                        } else {
                            emptyList()
                        },
                    ),
                )
                // Appending (not preferring) preserves every working platform
                // codec. Unsupported MPEG audio now gets PCM in this SAME player,
                // instead of forcing hardware video onto the full VLC_SW route.
                // Kept last: FFmpeg (if present) already handles MPEG audio, and
                // without it the JLayer renderer remains the MP2/MP3 safety net.
                out.add(MpegAudioRenderer(eventHandler, eventListener, audioSink))
                PlaybackLog.log(
                    context, engineName,
                    "audioRenderers=" + out.joinToString(",") { it.name } +
                        " ffmpeg=${FfmpegAudio.available}" +
                        (FfmpegAudio.version?.let { " ffmpegVersion=$it" } ?: "") +
                        " leading=${ffmpegPlan.leadingMimes} trailing=${ffmpegPlan.trailing}",
                )
            }

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
                return MonitoredAudioSink(builder.build(), audioUnderrunMonitor, SystemClock::elapsedRealtime)
            }
        }.setEnableDecoderFallback(true)
            .setMediaCodecSelector(MediaCodecSelector { mime, secure, tunneling ->
                LiveAudioDecoderPolicy.order(
                    mimeType = mime,
                    preferSoftwareAudio = preferSoftwareAudio,
                    allowPassthrough = allowPassthrough,
                    requiresSecureDecoder = secure,
                    requiresTunnelingDecoder = tunneling,
                    candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling),
                    isSoftware = { it.softwareOnly },
                )
            })

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

    /** Only fixed symbols/numbers are logged: never Format, media identity, or errors' text. */
    private fun recordPlaybackDiagnostic(
        event: PlaybackDiagnosticEvent,
        eventTime: AnalyticsListener.EventTime? = null,
        rendererIndex: Int? = null,
        errorCode: Int? = null,
        httpStatus: Int? = null,
    ) {
        val gate = playbackDiagnosticGate ?: return
        if (activeMediaId == null) return
        val nowMs = SystemClock.elapsedRealtime()
        val decision = gate.record(event.name, nowMs) ?: return
        val exo = player ?: return
        // Both AnalyticsListener and the sampler run on the main looper. Take
        // player snapshots only after throttling, not for every readiness flip.
        val eventRealtimeMs = eventTime?.realtimeMs ?: nowMs
        val lastFrameMs = diagnosticLastVideoFrameAtMs?.get() ?: 0L
        val frameAgeMs = if (lastFrameMs > 0L) (nowMs - lastFrameMs).coerceAtLeast(0L) else -1L
        val state = when (exo.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }
        val counts = decision.eventCounts.entries.joinToString(",") { (key, count) -> "$key:$count" }
        PlaybackLog.log(
            context,
            engineName,
            "liveDiag session=$streamGeneration event=${event.name} monoMs=$eventRealtimeMs " +
                "queueDelayMs=${(nowMs - eventRealtimeMs).coerceAtLeast(0L)} " +
                "state=$state isLoading=${exo.isLoading} isPlaying=${exo.isPlaying} " +
                "playWhenReady=${exo.playWhenReady} suppression=${exo.playbackSuppressionReason} " +
                "positionMs=${exo.currentPosition} bufferedMs=${exo.totalBufferedDuration} " +
                "lastVideoFrameAgeMs=$frameAgeMs audioCodec=${audioCodec.name} " +
                "audioReady=${diagnosticAudioReady ?: "unknown"} " +
                "videoReady=${diagnosticVideoReady ?: "unknown"} " +
                "rendererIndex=${rendererIndex ?: -1} errorCode=${errorCode ?: -1} " +
                "httpStatus=${httpStatus ?: -1} suppressed=${decision.suppressed} counts=$counts",
        )
    }

    private fun schedulePlaybackDiagnosticSampler() {
        val gate = playbackDiagnosticGate ?: return
        cancelPlaybackDiagnosticSampler()
        val generation = streamGeneration
        val sampler = object : Runnable {
            override fun run() {
                if (generation != streamGeneration || player == null || activeMediaId == null) return
                if (!gate.isActive(SystemClock.elapsedRealtime())) {
                    playbackDiagnosticSampler = null
                    return
                }
                recordPlaybackDiagnostic(PlaybackDiagnosticEvent.SAMPLE)
                if (generation == streamGeneration && gate.isActive(SystemClock.elapsedRealtime())) {
                    handler.postDelayed(this, PLAYBACK_DIAGNOSTIC_POLL_MS)
                }
            }
        }
        playbackDiagnosticSampler = sampler
        // First snapshot is posted only after the new item has been prepared.
        handler.post(sampler)
    }

    private fun cancelPlaybackDiagnosticSampler() {
        playbackDiagnosticSampler?.let(handler::removeCallbacks)
        playbackDiagnosticSampler = null
    }

    private enum class PlaybackDiagnosticEvent {
        SAMPLE,
        STATE_IDLE,
        STATE_BUFFERING,
        STATE_READY,
        STATE_ENDED,
        STATE_UNKNOWN,
        AUDIO_READY,
        AUDIO_NOT_READY,
        VIDEO_READY,
        VIDEO_NOT_READY,
        LOADING,
        NOT_LOADING,
        PLAYING,
        NOT_PLAYING,
        PLAY_WHEN_READY,
        PLAY_WHEN_NOT_READY,
        PLAYER_ERROR,
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
        cancelAudioClockStallCheck()
        cancelAudioUnderrunCheck()
        logAudioTrackSupport()
        PlaybackLog.log(context, engineName, "$detail -> audio compatibility fallback")
        listener?.onAudioUnavailable()
    }

    /** Include unsupported tracks too: input-format events only cover selected tracks. */
    private fun logAudioTrackSupport() {
        player?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO }
            ?.take(4)?.forEach { group ->
                repeat(minOf(group.length, 4)) { index ->
                    val format = group.getTrackFormat(index)
                    PlaybackLog.log(
                        context, engineName,
                        "audioCandidate codec=${LiveAudioStallPolicy.codecForMime(format.sampleMimeType)} " +
                            "channels=${format.channelCount} rate=${format.sampleRate} " +
                            "support=${group.getTrackSupport(index)} selected=${group.isTrackSelected(index)}",
                    )
                }
            }
    }

    private fun audioOutputMode(): AudioFailureEvidence.OutputMode =
        if (allowPassthrough) {
            AudioFailureEvidence.OutputMode.PASSTHROUGH
        } else {
            AudioFailureEvidence.OutputMode.PCM
        }

    private fun evaluateAudioStall(
        sinkEvent: AudioFailureEvidence.SinkEvent,
        nowMs: Long = SystemClock.elapsedRealtime(),
        bufferingDurationMs: Long = 0L,
        requireVideoProgressAfterMs: Long? = null,
        sourceProgressAfterIssue: Boolean = false,
        underrunObservation: AudioUnderrunMonitor.Observation = audioUnderrunMonitor.poll(nowMs),
    ) {
        if (audioReported) return
        val lastVideoFrameMs = lastVideoFrameAtMs.get()
        val evidence = LiveAudioStallPolicy.Evidence(
            codec = audioCodec,
            decoder = audioDecoder,
            outputMode = audioOutputMode(),
            mediaProgressObserved = firstFrameRendered || audioBufferingHadMediaProgress,
            recentVideoProgress =
                lastVideoFrameMs > 0L &&
                    (requireVideoProgressAfterMs == null ||
                        lastVideoFrameMs > requireVideoProgressAfterMs) &&
                    nowMs - lastVideoFrameMs <= LiveAudioStallPolicy.RECENT_VIDEO_PROGRESS_MS,
            sourceProgressAfterIssue = sourceProgressAfterIssue,
            decoderInitialized = audioDecoderInitialized,
            audioClockStarted = audioClockStarted,
            underrunsInWindow = underrunObservation.underruns,
            audioClockStalledForMs = underrunObservation.stalledDurationMs,
            bufferingDurationMs = bufferingDurationMs,
            sinkEvent = sinkEvent,
        )
        if (
            LiveAudioStallPolicy.decide(evidence) !=
            LiveAudioStallPolicy.Decision.FALLBACK_TO_VLC_PCM
        ) {
            return
        }
        PlaybackLog.log(
            context, engineName,
            "audio recovery evidence underruns=${evidence.underrunsInWindow} " +
                "clockStalledMs=${evidence.audioClockStalledForMs} " +
                "videoProgress=${evidence.recentVideoProgress} sourceProgress=${evidence.sourceProgressAfterIssue}",
        )
        reportAudioStall(
            AudioFailureEvidence(
                codec = evidence.codec,
                decoder = evidence.decoder,
                sinkEvent = sinkEvent,
                outputMode = evidence.outputMode,
            ),
        )
    }

    private fun scheduleAudioUnderrunCheck() {
        if (audioUnderrunCheckRunnable != null || audioReported || videoFailureReported ||
            audioCodec != AudioFailureEvidence.Codec.AC3 || allowPassthrough ||
            player?.playWhenReady != true
        ) return
        val generation = streamGeneration
        val check = object : Runnable {
            override fun run() {
                audioUnderrunCheckRunnable = null
                val exo = player ?: return
                if (generation != streamGeneration || audioReported || videoFailureReported) return
                if (!exo.playWhenReady || exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
                    exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED
                ) {
                    cancelAudioUnderrunCheck()
                    return
                }
                val nowMs = SystemClock.elapsedRealtime()
                val observation = audioUnderrunMonitor.poll(nowMs)
                if (!observation.pending) {
                    if (observation.recovered) {
                        PlaybackLog.log(context, engineName, "audio underrun recovered -> keep current decoder")
                    }
                    return
                }
                evaluateAudioStall(
                    sinkEvent = AudioFailureEvidence.SinkEvent.UNDERRUN,
                    nowMs = nowMs,
                    requireVideoProgressAfterMs = audioUnderrunVideoBaselineMs,
                    sourceProgressAfterIssue = exo.totalBufferedDuration >=
                        audioUnderrunBufferBaselineMs + MIN_BUFFER_PROGRESS_EVIDENCE_MS,
                    underrunObservation = observation,
                )
                if (!audioReported && generation == streamGeneration) {
                    audioUnderrunCheckRunnable = this
                    handler.postDelayed(this, AudioUnderrunMonitor.POLL_INTERVAL_MS)
                }
            }
        }
        audioUnderrunCheckRunnable = check
        handler.postDelayed(check, AudioUnderrunMonitor.POLL_INTERVAL_MS)
    }

    private fun cancelAudioUnderrunCheck() {
        audioUnderrunCheckRunnable?.let(handler::removeCallbacks)
        audioUnderrunCheckRunnable = null
        audioUnderrunMonitor.reset()
        audioUnderrunVideoBaselineMs = 0L
        audioUnderrunBufferBaselineMs = 0L
    }

    private fun scheduleAudioClockStallCheck() {
        if (
            audioCodec != AudioFailureEvidence.Codec.AC3 ||
            allowPassthrough ||
            audioReported ||
            videoFailureReported ||
            player?.playWhenReady != true ||
            !firstFrameRendered
        ) {
            return
        }
        if (audioBufferingSinceMs == 0L) {
            audioBufferingSinceMs = SystemClock.elapsedRealtime()
            audioBufferingHadMediaProgress = true
            audioBufferingVideoBaselineMs = lastVideoFrameAtMs.get()
            audioBufferingDurationBaselineMs = player?.totalBufferedDuration ?: 0L
        }
        audioStallCheckRunnable?.let(handler::removeCallbacks)
        val generation = streamGeneration
        val check = Runnable {
            audioStallCheckRunnable = null
            if (generation != streamGeneration || player?.playbackState != Player.STATE_BUFFERING ||
                player?.playWhenReady != true ||
                player?.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
            ) {
                return@Runnable
            }
            val nowMs = SystemClock.elapsedRealtime()
            val bufferedDurationNow = player?.totalBufferedDuration ?: 0L
            evaluateAudioStall(
                sinkEvent = AudioFailureEvidence.SinkEvent.CLOCK_STALL,
                nowMs = nowMs,
                bufferingDurationMs = (nowMs - audioBufferingSinceMs).coerceAtLeast(0L),
                requireVideoProgressAfterMs = audioBufferingVideoBaselineMs,
                sourceProgressAfterIssue =
                    bufferedDurationNow >=
                        audioBufferingDurationBaselineMs + MIN_BUFFER_PROGRESS_EVIDENCE_MS,
            )
        }
        audioStallCheckRunnable = check
        handler.postDelayed(check, LiveAudioStallPolicy.AUDIO_CLOCK_START_TIMEOUT_MS)
    }

    private fun cancelAudioClockStallCheck() {
        audioStallCheckRunnable?.let(handler::removeCallbacks)
        audioStallCheckRunnable = null
        audioBufferingSinceMs = 0L
        audioBufferingHadMediaProgress = false
        audioBufferingVideoBaselineMs = 0L
        audioBufferingDurationBaselineMs = 0L
    }

    private fun reportAudioStall(evidence: AudioFailureEvidence) {
        if (audioReported) return
        audioReported = true
        cancelTrackSupportCheck()
        cancelAudioClockStallCheck()
        cancelAudioUnderrunCheck()
        PlaybackLog.log(
            context,
            engineName,
            "audio stall codec=${evidence.codec.name} decoder=${evidence.decoder.name} " +
                "sink=${evidence.sinkEvent.name} output=${evidence.outputMode.name} -> VLC PCM",
        )
        listener?.onAudioStall(evidence)
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
        for (depth in 0 until 8) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause?.cause
        }
        return null
    }

    private fun reportDecodeFailure(detail: String) {
        if (videoFailureReported) return
        videoFailureReported = true
        cancelAudioClockStallCheck()
        cancelAudioUnderrunCheck()
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
                if (tunnelingEnabled) {
                    reportTunnelingNoFrame()
                } else {
                    reportVideoInvalid("no first frame for expected video")
                }
            }
        }
        noFrameCheckRunnable = check
        handler.postDelayed(check, NO_FRAME_TIMEOUT_MS)
    }

    /**
     * Same teardown as [reportVideoInvalid], different verdict: a tunneled
     * decoder that never surfaces a frame is first retried untunneled by the
     * controller, since tunneling itself is the most likely culprit.
     */
    private fun reportTunnelingNoFrame() {
        if (videoFailureReported) return
        videoFailureReported = true
        cancelAudioClockStallCheck()
        cancelAudioUnderrunCheck()
        surfaceFrameHealth.reset()
        droppedFrameHealth.reset()
        cancelTrackSupportCheck()
        cancelNoFrameCheck()
        handler.removeCallbacks(surfaceValidationDeadlineRunnable)
        handler.removeCallbacks(videoProgressRunnable)
        PlaybackLog.log(context, engineName, "no first frame with tunneling -> retry untunneled")
        listener?.onTunnelingNoFrame()
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
        cancelAudioClockStallCheck()
        cancelAudioUnderrunCheck()
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
        if (MediaTransportPolicy.isHlsUrl(url)) {
            exo.stop()
            listener?.onSourceFailure("HLS playback is disabled", 415)
            return
        }
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
        if (reset) {
            exo.stop()
            // stop() only cancels the Loader: the old channel's socket closes
            // later on the loader thread (never while a read is blocked). Force
            // it closed and prepare the new item only after that boundary, so a
            // zap never holds two provider connections. Bounded, then proceed.
            if (providerConnection.retireAndClose()) {
                val generation = streamGeneration
                providerConnection.awaitClosed(PROVIDER_CLOSE_TIMEOUT_MS) { closed ->
                    if (generation != streamGeneration || player !== exo) return@awaitClosed
                    if (!closed) {
                        PlaybackLog.log(
                            context,
                            engineName,
                            "provider socket close unproven after ${PROVIDER_CLOSE_TIMEOUT_MS}ms -> zap anyway",
                        )
                    }
                    submitMedia(exo, url, mediaId)
                }
                return
            }
        }
        submitMedia(exo, url, mediaId)
    }

    private fun submitMedia(exo: ExoPlayer, url: String, mediaId: String) {
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(url)
                .setMediaId(mediaId)
                .build(),
        )
        exo.playWhenReady = true
        exo.prepare()
        schedulePlaybackDiagnosticSampler()
        listener?.onPlaybackSubmitted()
    }

    private fun resetHealth(): String {
        cancelPlaybackDiagnosticSampler()
        handler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        surfaceReadbackPolicy.reset()
        droppedFrameHealth.reset()
        streamGeneration += 1
        activeMediaId = "live-$streamGeneration"
        firstFrameRendered = false
        videoFailureReported = false
        videoOutputReported = false
        audioReported = false
        audioCodec = AudioFailureEvidence.Codec.UNKNOWN
        audioDecoder = AudioFailureEvidence.Decoder.UNKNOWN
        audioDecoderInitialized = false
        audioClockStarted = false
        cancelAudioUnderrunCheck()
        audioBufferingSinceMs = 0L
        audioBufferingHadMediaProgress = false
        audioBufferingVideoBaselineMs = 0L
        audioBufferingDurationBaselineMs = 0L
        audioUnderrunVideoBaselineMs = 0L
        audioStallCheckRunnable = null
        readyForPlayback = false
        lastSurfaceWidth = 0
        lastSurfaceHeight = 0
        trackSupportCheckRunnable = null
        noFrameCheckRunnable = null
        lastVideoFrameAtMs.set(0L)
        lastHealthPositionMs = -1L
        diagnosticAudioReady = null
        diagnosticVideoReady = null
        diagnosticLastVideoFrameAtMs?.set(0L)
        playbackDiagnosticGate?.beginSession(SystemClock.elapsedRealtime())
        return checkNotNull(activeMediaId)
    }

    override fun pause() { player?.playWhenReady = false }
    override fun resume() { player?.playWhenReady = true }

    override fun stop() {
        player?.stop()
        // Media3 stop() is not a socket close: cancel the Loader, then force the
        // blocked HTTP read to unwind now instead of at the 30 s read timeout.
        providerConnection.retireAndClose()
        cancelStreamHealthChecks()
        // Late load errors from the force-closed transfer belong to no stream.
        // Also retire the generation so a zap whose submitMedia() is still
        // waiting on the previous socket close (play(reset=true)) cannot resume
        // playback after this stop.
        streamGeneration += 1
        activeMediaId = null
    }

    override fun stopAndThen(onStopped: (Boolean) -> Unit) {
        stop()
        providerConnection.awaitClosed(PROVIDER_CLOSE_TIMEOUT_MS) { closed ->
            if (!closed) {
                PlaybackLog.log(
                    context,
                    engineName,
                    "provider socket close unproven after ${PROVIDER_CLOSE_TIMEOUT_MS}ms",
                )
            }
            onStopped(closed)
        }
    }

    /** Health polls that STATE_IDLE/stop must retire along with the stream. */
    private fun cancelStreamHealthChecks() {
        handler.removeCallbacks(videoProgressRunnable)
        handler.removeCallbacks(surfaceValidationDeadlineRunnable)
        surfaceFrameHealth.reset()
    }

    // Read on the main thread (Media3 requires it); the controller's stall
    // watchdog polls from a main-looper Handler. Advances during live playback,
    // freezes on a silent source stall.
    override fun playbackPositionMs(): Long = player?.let {
        exoPlaybackClockPositionMs(it, playbackClockWindow)
    } ?: -1L

    // Progressive TS has no sliding window, so the raw buffered position is a
    // monotonic fill marker for the starvation check (main thread, like above).
    override fun bufferFillMarker(): Long = player?.bufferedPosition ?: -1L

    override fun hasRecentOutputProgress(): Boolean {
        val exo = player ?: return false
        val now = SystemClock.elapsedRealtime()
        val videoHealthy = !expectsVideo || (videoOutputReported &&
            lastVideoFrameAtMs.get() > 0L && now - lastVideoFrameAtMs.get() in 0L..6_000L)
        val audioHealthy = exo.audioFormat == null || audioUnderrunMonitor.hasRecentProgress(now)
        return exo.isPlaying && videoHealthy && audioHealthy
    }

    override fun release() {
        cancelPlaybackDiagnosticSampler()
        handler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        surfaceReadbackPolicy.reset()
        droppedFrameHealth.reset()
        streamGeneration += 1
        activeMediaId = null
        audioUnderrunMonitor.reset()
        val retiredView = playerView
        // Disconnect the render surface before releasing the codec. Several TV
        // vendor MediaCodec implementations otherwise retain a destroyed view
        // across the next engine/channel and fail configure with BAD_VALUE.
        retiredView?.player = null
        // Force the socket closed before the (bounded, blocking) player release
        // so a read stuck in HttpURLConnection unwinds in parallel with it.
        providerConnection.retireAndClose()
        player?.release()
        (retiredView?.parent as? ViewGroup)?.removeView(retiredView)
        player = null
        playerView = null
        listener = null
    }

    override fun releaseAndThen(onReleased: (Boolean) -> Unit) {
        release()
        providerConnection.awaitClosed(PROVIDER_CLOSE_TIMEOUT_MS) { closed ->
            if (!closed) {
                PlaybackLog.log(
                    context,
                    engineName,
                    "provider socket close unproven after ${PROVIDER_CLOSE_TIMEOUT_MS}ms",
                )
            }
            onReleased(closed)
        }
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
        // MPEG-TS H.264/H.265 formats carry no frame rate; fall back to the
        // cadence measured from rendered frames so frame-rate matching works.
        val fps = if (fmt.frameRate > 0f) fmt.frameRate else frameRateEstimator.estimate()
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
        private const val VIDEO_CLOCK_EVIDENCE_MS = 500L
        private const val MIN_BUFFER_PROGRESS_EVIDENCE_MS = 250L
        private const val PIXEL_VALIDATION_DEADLINE_MS = 9_000L
        private const val PLAYBACK_DIAGNOSTIC_POLL_MS = 1_000L
        /** Bound on waiting for the loader thread to close the provider socket. */
        private const val PROVIDER_CLOSE_TIMEOUT_MS = 1_500L
    }
}

/**
 * Tracks the live Media3 provider connection.
 *
 * ExoPlayer.stop()/release() only cancel the Loader; the HTTP socket is closed
 * later on the loader thread, and a read blocked inside HttpURLConnection is
 * not interruptible, so the next channel could overlap the old connection for
 * up to the read timeout. Every HTTP DataSource is wrapped so the engine can
 * (1) refuse a late open() from an already-cancelled load, (2) force the open
 * socket closed from another thread and (3) report the real close boundary.
 * Framework-light on purpose: JVM tests drive it with a mocked Handler.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class ExoProviderConnection(
    private val mainHandler: Handler,
    private val closeExecutor: (Runnable) -> Unit = { SHARED_CLOSE_EXECUTOR.execute(it) },
) {
    private class Waiter(val onClosed: (Boolean) -> Unit, val timeout: Runnable)

    private val lock = Any()
    private var generation = 0L
    private val openSources = LinkedHashSet<Tracked>()
    private val waiters = ArrayList<Waiter>()

    fun wrap(source: DataSource): DataSource = synchronized(lock) { Tracked(source, generation) }

    /** True while a retired (stopped) connection has not closed yet. */
    fun hasRetiredOpenConnection(): Boolean = synchronized(lock) { hasRetiredOpenLocked() }

    /**
     * Retire every DataSource created so far and force-close the open ones off
     * the caller's thread. Returns true when a close is still pending.
     */
    fun retireAndClose(): Boolean {
        val targets = synchronized(lock) {
            generation++
            openSources.toList()
        }
        if (targets.isEmpty()) return false
        closeExecutor(Runnable { targets.forEach { runCatching(it::close) } })
        return true
    }

    /**
     * Invoke [onClosed] on the main thread with true once every retired
     * connection has closed, or with false after [timeoutMs]. Runs inline when
     * nothing is pending.
     */
    fun awaitClosed(timeoutMs: Long, onClosed: (Boolean) -> Unit) {
        val waiter = synchronized(lock) {
            if (!hasRetiredOpenLocked()) return@synchronized null
            Waiter(onClosed, Runnable { expire() }).also { waiters += it }
        }
        if (waiter == null) {
            onClosed(true)
            return
        }
        mainHandler.postDelayed(waiter.timeout, timeoutMs)
    }

    private fun hasRetiredOpenLocked(): Boolean = openSources.any { it.generation != generation }

    private fun expire() {
        val expired = synchronized(lock) {
            if (!hasRetiredOpenLocked()) return
            waiters.toList().also { waiters.clear() }
        }
        expired.forEach { it.onClosed(false) }
    }

    private fun onSourceClosed(source: Tracked) {
        val done = synchronized(lock) {
            openSources.remove(source)
            if (hasRetiredOpenLocked() || waiters.isEmpty()) return
            waiters.toList().also { waiters.clear() }
        }
        mainHandler.post {
            done.forEach {
                mainHandler.removeCallbacks(it.timeout)
                it.onClosed(true)
            }
        }
    }

    private inner class Tracked(
        private val delegate: DataSource,
        val generation: Long,
    ) : DataSource {
        override fun open(dataSpec: DataSpec): Long {
            synchronized(lock) {
                // A cancelled Loader can still reach open(); never let a
                // stopped stream open a second provider connection.
                if (generation != this@ExoProviderConnection.generation) {
                    throw IOException("provider connection retired")
                }
                openSources += this
            }
            val length = try {
                delegate.open(dataSpec)
            } catch (error: Exception) {
                onSourceClosed(this)
                throw error
            }
            // Retired while the connect was in flight: the force-close may have
            // run before the connection existed, so close it here instead.
            if (synchronized(lock) { generation != this@ExoProviderConnection.generation }) {
                close()
                throw IOException("provider connection retired")
            }
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun addTransferListener(listener: TransferListener) =
            delegate.addTransferListener(listener)

        override fun getUri(): Uri? = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        // Idempotent: the force-close and the loader's own finally-close may
        // both run; DefaultHttpDataSource tolerates a second close.
        override fun close() {
            try {
                delegate.close()
            } finally {
                onSourceClosed(this)
            }
        }
    }

    private companion object {
        private val SHARED_CLOSE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "exo-provider-close").apply { isDaemon = true }
        }
    }
}
