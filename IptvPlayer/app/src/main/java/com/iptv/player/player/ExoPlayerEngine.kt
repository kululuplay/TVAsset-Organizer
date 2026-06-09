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
 *     READY the decoder is stuck -> onVideoInvalid -> libVLC software fallback.
 */
package com.iptv.player.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.iptv.player.R
import com.iptv.player.data.model.BufferMode
import com.iptv.player.util.AppInfo
import com.iptv.player.util.DeviceCaps
import com.iptv.player.util.PlaybackLog

class ExoPlayerEngine(
    private val context: Context,
    /** When false (default) audio is decoded to PCM; true allows HDMI passthrough. */
    private val allowPassthrough: Boolean = false,
    /** Buffer size (user "Buffer size" setting) -> DefaultLoadControl durations. */
    private val bufferMode: BufferMode = BufferMode.NORMAL
) : PlayerEngine {

    override val engineName: String = "ExoPlayer"

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var listener: PlayerListener? = null

    private val handler = Handler(Looper.getMainLooper())
    // Per-stream health flags so each detection fires at most once.
    private var firstFrameRendered = false
    private var videoReported = false
    private var audioReported = false

    // Track selection can momentarily report no selected audio while the
    // selector settles, so re-read once playback is READY and stable before
    // declaring the stream silent. Avoids false libVLC fallbacks.
    private val audioCheckRunnable = Runnable {
        val p = player ?: return@Runnable
        if (p.playbackState == Player.STATE_READY) checkAudioSupported(p.currentTracks)
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
                        listener?.onPlaying()
                        scheduleNoFrameCheck()
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
                listener?.onVideoOutput()
                PlaybackLog.log(context, engineName, "onRenderedFirstFrame")
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
                if (isDecoderError(error.errorCode)) listener?.onDecodeError(error.errorCodeName)
                else listener?.onError(error.errorCodeName)
            }
        })

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
     * [setEnableDecoderFallback] lets Exo drop to another (software) decoder
     * instead of leaving a green/blank surface.
     */
    private fun buildRenderersFactory(): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val builder = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // Default = force PCM (no passthrough). Only honour device
                // passthrough capabilities when the user opts in.
                if (!allowPassthrough) {
                    builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                }
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
     * The Amlogic hardware H.264 decoder greens out on the demanding 1080p@50fps
     * (high frame-rate / high-bitrate) profile while handling normal channels fine.
     * The no-frame watchdog can't catch it: the decoder DOES push a (green) frame,
     * so onRenderedFirstFrame fires and the watchdog is satisfied. Detect that one
     * profile from the input format instead and trigger the per-stream software
     * fallback (onVideoInvalid -> controller restarts this stream on libVLC SW).
     * One-shot per stream (guarded by [videoReported]); every other stream — lower
     * resolution, lower frame rate, or non-H.264 — stays on hardware untouched.
     */
    private fun maybeFlagGreenProneProfile(format: Format) {
        if (videoReported) return
        // Proactive MPEG2 -> software on Amlogic. The Amlogic hardware MPEG2 decoder
        // (OMX.amlogic.mpeg2.decoder) is unreliable on this box: it renders a frame,
        // plays ~1.5s, then fails with ERROR_CODE_DECODING_FAILED and loops. SD MPEG2
        // is light, so route it to libVLC software decode the moment we see the codec
        // — before the glitch — rather than waiting for the reactive failure counter.
        if (DeviceCaps.isAmlogic && format.sampleMimeType == MimeTypes.VIDEO_MPEG2) {
            videoReported = true
            PlaybackLog.log(
                context, engineName,
                "Amlogic MPEG2 ${format.width}x${format.height} -> proactive software fallback"
            )
            listener?.onVideoInvalid()
            return
        }
        val h264 = format.sampleMimeType == MimeTypes.VIDEO_H264
        val is1080p = format.height >= GREEN_PRONE_MIN_HEIGHT || format.width >= GREEN_PRONE_MIN_WIDTH
        // frameRate is Format.NO_VALUE (-1f) when unknown, so the >= check already
        // excludes it; only genuine high-frame-rate streams trip the threshold.
        val highFps = format.frameRate >= GREEN_PRONE_MIN_FPS
        if (h264 && is1080p && highFps) {
            videoReported = true
            PlaybackLog.log(
                context, engineName,
                "green-prone HW profile H264 ${format.width}x${format.height}" +
                    "@${format.frameRate}fps -> software fallback"
            )
            listener?.onVideoInvalid()
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
            if (!videoReported && !firstFrameRendered && player?.videoFormat != null) {
                videoReported = true
                PlaybackLog.log(context, engineName, "no first frame for video track -> fallback")
                listener?.onVideoInvalid()
            }
        }, NO_FRAME_TIMEOUT_MS)
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
        firstFrameRendered = false
        videoReported = false
        audioReported = false
    }

    override fun pause() { player?.playWhenReady = false }
    override fun resume() { player?.playWhenReady = true }
    override fun stop() { player?.stop() }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
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

        // The green-prone Amlogic profile: H.264 at 1080p with a high frame rate
        // (1080p@50/60). Normal 1080i broadcasts report ~25fps (field-coded), so
        // the >=49 threshold separates the failing progressive 50/60fps streams
        // from the interlaced ones that decode fine on hardware.
        private const val GREEN_PRONE_MIN_HEIGHT = 1080
        private const val GREEN_PRONE_MIN_WIDTH = 1920
        private const val GREEN_PRONE_MIN_FPS = 49f
    }
}
