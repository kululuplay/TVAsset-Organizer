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
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
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
import java.util.concurrent.atomic.AtomicLong

@OptIn(markerClass = [UnstableApi::class])
class ExoPlayerEngine(
    private val context: Context,
    /** When false (default) audio is decoded to PCM; true allows HDMI passthrough. */
    private val allowPassthrough: Boolean = false,
    /** Buffer size (user "Buffer size" setting) -> DefaultLoadControl durations. */
    private val bufferMode: BufferMode = BufferMode.NORMAL,
) : PlayerEngine {

    override val engineName: String = "ExoPlayer"

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var listener: PlayerListener? = null

    private val handler = Handler(Looper.getMainLooper())
    // Per-stream health flags so each detection fires at most once.
    private var firstFrameRendered = false
    private var videoFailureReported = false
    private var videoOutputReported = false
    private var audioReported = false
    private val lastVideoFrameAtMs = AtomicLong(0L)

    private val surfaceFrameHealth = SurfaceFrameHealthMonitor(
        handler = handler,
        onSolidGreen = {
            reportVideoInvalid("persistent solid-green SurfaceView output")
        },
        onHealthyFrame = {
            reportVerifiedVideoOutput()
        },
    )

    // PixelCopy can be unavailable on protected/quirky surfaces. In that case
    // preserve the legacy first-frame behaviour after a short grace period. A
    // successfully classified green sample disables this escape hatch.
    private val surfaceValidationFallbackRunnable = Runnable {
        if (
            firstFrameRendered &&
            !videoFailureReported &&
            !videoOutputReported &&
            !surfaceFrameHealth.hasClassifiedFrame()
        ) {
            PlaybackLog.log(
                context,
                engineName,
                "PixelCopy unavailable -> accept Media3 rendered-frame signal",
            )
            reportVerifiedVideoOutput()
        }
    }

    // Track selection can momentarily report no selected audio while the
    // selector settles, so re-read once playback is READY and stable before
    // declaring the stream silent. Avoids false libVLC fallbacks.
    private val audioCheckRunnable = Runnable {
        val p = player ?: return@Runnable
        if (p.playbackState == Player.STATE_READY) checkAudioSupported(p.currentTracks)
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

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener?.onBuffering()
                    Player.STATE_READY -> {
                        // Give video a fresh grace period after a long network
                        // rebuffer before judging the prior frame timestamp stale.
                        if (firstFrameRendered) {
                            lastVideoFrameAtMs.set(SystemClock.elapsedRealtime())
                        }
                        listener?.onPlaying()
                        scheduleNoFrameCheck()
                        scheduleVideoProgressCheck()
                    }
                    Player.STATE_ENDED -> listener?.onEnded()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                handler.removeCallbacks(audioCheckRunnable)
                handler.postDelayed(audioCheckRunnable, AUDIO_CHECK_DELAY_MS)
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                lastVideoFrameAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                PlaybackLog.log(context, engineName, "onRenderedFirstFrame")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    surfaceFrameHealth.start {
                        findVideoSurface(playerView?.videoSurfaceView ?: playerView)
                    }
                    handler.removeCallbacks(surfaceValidationFallbackRunnable)
                    handler.postDelayed(
                        surfaceValidationFallbackRunnable,
                        PIXEL_VALIDATION_FALLBACK_MS,
                    )
                } else {
                    reportVerifiedVideoOutput()
                }
            }

            // Amlogic "1 second then green" tracing. A video-size change drives
            // PlayerView's aspect relayout; with resize_mode=fit that RESIZES the
            // SurfaceView, tearing down + recreating its surface so the compositor
            // greens the new one. resize_mode=fill now keeps the surface constant —
            // these two logs confirm whether any resize still slips through.
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                PlaybackLog.log(
                    context, engineName,
                    "onVideoSizeChanged ${videoSize.width}x${videoSize.height} " +
                        "par=${videoSize.pixelWidthHeightRatio}"
                )
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                PlaybackLog.log(context, engineName, "onSurfaceSizeChanged ${width}x$height")
            }

            override fun onPlayerError(error: PlaybackException) {
                PlaybackLog.log(context, engineName, "onPlayerError ${error.errorCodeName}")
                // Distinguish a hardware-DECODER failure (videoCodecError /
                // ERROR_CODE_DECODING_FAILED etc.) from a network/source drop. The
                // Amlogic MPEG2 decoder renders a frame then fails decoding ~1.5s in;
                // routing it through onDecodeError lets the controller escalate to
                // software instead of looping the reconnect on the same dead decoder.
                if (isDecoderError(error.errorCode)) {
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
                    if (rendererType == C.TRACK_TYPE_AUDIO) {
                        audioReported = true
                        listener?.onAudioUnavailable()
                    } else {
                        listener?.onDecodeError(error.errorCodeName)
                    }
                } else {
                    listener?.onError(error.errorCodeName)
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
                PlaybackLog.log(context, engineName, "videoDecoder=$decoderName")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                PlaybackLog.log(
                    context, engineName,
                    "videoInputFormat ${format.width}x${format.height} " +
                        "${format.sampleMimeType} fps=${format.frameRate}"
                )
                maybeFlagGreenProneProfile(format)
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                PlaybackLog.log(context, engineName, "droppedVideoFrames=$droppedFrames/${elapsedMs}ms")
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception
            ) {
                PlaybackLog.log(context, engineName, "videoCodecError ${videoCodecError.message}")
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
            @Suppress("DEPRECATION")
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
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                return builder.build()
            }
        }.setEnableDecoderFallback(true)

    /**
     * If the media carries an audio track but none could be selected (the codec
     * has no on-device decoder and passthrough is off), playback will be silent.
     * Report it so the controller can fall back to libVLC's software decoder.
     */
    private fun checkAudioSupported(tracks: Tracks) {
        if (audioReported) return
        var hasAudio = false
        var audioSelected = false
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                hasAudio = true
                if (group.isSelected) audioSelected = true
            }
        }
        if (hasAudio && !audioSelected) {
            audioReported = true
            PlaybackLog.log(
                context,
                engineName,
                "audio track present but unsupported/unselected -> fallback " +
                    "(state=${player?.playbackState}, groups=${tracks.groups.size})"
            )
            listener?.onAudioUnavailable()
        }
    }

    /**
     * Some Amlogic AVC decoders accept 1080p50/60, report a rendered frame and
     * then output green buffers without a codec exception. PixelCopy confirms the
     * actual colour on API 24+, while this narrow profile guard covers older TVs
     * and surfaces that cannot be copied. UHD and non-AVC streams are excluded.
     */
    private fun maybeFlagGreenProneProfile(format: Format) {
        // API 24+ devices are judged from the actual SurfaceView pixels instead
        // of a codec/profile guess. This guard is only for old TVs where
        // PixelCopy does not exist; otherwise a perfectly healthy 1080p50 stream
        // could be needlessly pushed onto a slower software decoder.
        if (videoFailureReported || Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return
        val h264 = format.sampleMimeType == MimeTypes.VIDEO_H264
        val isUhd = format.height >= UHD_MIN_HEIGHT || format.width >= UHD_MIN_WIDTH
        val is1080Class = !isUhd &&
            (format.height >= GREEN_PRONE_MIN_HEIGHT || format.width >= GREEN_PRONE_MIN_WIDTH)
        if (h264 && is1080Class && format.frameRate >= GREEN_PRONE_MIN_FPS) {
            reportVideoInvalid(
                "green-prone AVC ${format.width}x${format.height}@${format.frameRate}fps",
            )
        }
    }

    /**
     * True for ExoPlayer error codes that mean the (hardware) video decoder failed
     * — distinct from a network/source drop. These are the failures that loop on
     * the same dead decoder, so the controller escalates them to software decode.
     */
    private fun isDecoderError(code: Int): Boolean = when (code) {
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> true
        else -> false
    }

    private fun scheduleNoFrameCheck() {
        handler.postDelayed({
            // Reached READY with a video track but never rendered a frame =>
            // decoder is stuck (often the green/blank failure with no error).
            if (!videoFailureReported && !firstFrameRendered && player?.videoFormat != null) {
                reportVideoInvalid("no first frame for video track")
            }
        }, NO_FRAME_TIMEOUT_MS)
    }

    private fun scheduleVideoProgressCheck() {
        handler.removeCallbacks(videoProgressRunnable)
        handler.postDelayed(videoProgressRunnable, VIDEO_HEALTH_POLL_MS)
    }

    private fun reportVideoInvalid(detail: String) {
        if (videoFailureReported) return
        videoFailureReported = true
        surfaceFrameHealth.reset()
        handler.removeCallbacks(surfaceValidationFallbackRunnable)
        handler.removeCallbacks(videoProgressRunnable)
        PlaybackLog.log(context, engineName, "$detail -> compatibility fallback")
        listener?.onVideoInvalid()
    }

    private fun reportVerifiedVideoOutput() {
        if (videoFailureReported || videoOutputReported) return
        videoOutputReported = true
        handler.removeCallbacks(surfaceValidationFallbackRunnable)
        PlaybackLog.log(context, engineName, "healthy SurfaceView frame confirmed")
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
        resetHealth()
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
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.playWhenReady = true
        exo.prepare()
    }

    private fun resetHealth() {
        handler.removeCallbacksAndMessages(null)
        surfaceFrameHealth.reset()
        firstFrameRendered = false
        videoFailureReported = false
        videoOutputReported = false
        audioReported = false
        lastVideoFrameAtMs.set(0L)
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
        player?.release()
        player = null
        playerView?.player = null
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
        /** Debounce after a track change before judging audio as unselected. */
        private const val AUDIO_CHECK_DELAY_MS = 1200L
        /** If no frame renders this long after READY, treat video as failed. */
        private const val NO_FRAME_TIMEOUT_MS = 6000L
        private const val VIDEO_HEALTH_POLL_MS = 2000L
        private const val VIDEO_FRAME_STALL_MS = 7000L
        private const val PIXEL_VALIDATION_FALLBACK_MS = 1_800L
        private const val GREEN_PRONE_MIN_HEIGHT = 1080
        private const val GREEN_PRONE_MIN_WIDTH = 1920
        private const val GREEN_PRONE_MIN_FPS = 49f
        private const val UHD_MIN_HEIGHT = 1440
        private const val UHD_MIN_WIDTH = 2560
    }
}
