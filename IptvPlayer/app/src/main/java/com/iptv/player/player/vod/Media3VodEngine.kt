package com.iptv.player.player.vod

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.player.R
import com.iptv.player.playback.core.FailureSignal
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackFailureClassifier
import com.iptv.player.player.SurfaceFrameHealthMonitor
import com.iptv.player.player.VodHttpRedirectPolicy
import com.iptv.player.util.AppInfo
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class VodBufferConfig(
    val minBufferMs: Int = 15_000,
    val maxBufferMs: Int = 50_000,
    val playbackBufferMs: Int = 1_200,
    val rebufferMs: Int = 2_500,
) {
    init {
        require(minBufferMs >= 0) { "minBufferMs must not be negative" }
        require(maxBufferMs >= minBufferMs) { "maxBufferMs must be at least minBufferMs" }
        require(playbackBufferMs in 0..minBufferMs) {
            "playbackBufferMs must be in 0..minBufferMs"
        }
        require(rebufferMs in 0..minBufferMs) { "rebufferMs must be in 0..minBufferMs" }
    }
}

data class Media3VodEngineConfig(
    val buffer: VodBufferConfig = VodBufferConfig(),
    val userAgent: String = AppInfo.USER_AGENT,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 20_000,
    /** Allows only an HTTP -> HTTPS upgrade; HTTPS -> HTTP remains blocked. */
    val allowHttpToHttpsRedirects: Boolean = true,
    val enablePixelCopyValidation: Boolean = true,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    /** False forces device-side PCM; true permits HDMI encoded passthrough. */
    val allowAudioPassthrough: Boolean = false,
) {
    init {
        require(userAgent.isNotBlank()) { "userAgent must not be blank" }
        require(userAgent.length <= 200) { "userAgent must be at most 200 characters" }
        require('\r' !in userAgent && '\n' !in userAgent) {
            "userAgent must not contain control-line characters"
        }
        require(connectTimeoutMs in 1_000..120_000) {
            "connectTimeoutMs must be in 1000..120000"
        }
        require(readTimeoutMs in 1_000..120_000) {
            "readTimeoutMs must be in 1000..120000"
        }
    }
}

/**
 * Media3-first VOD backend. The class owns exactly one ExoPlayer/PlayerView pair;
 * per-item listeners are generation-bound so late callbacks from a replaced item
 * cannot mutate or finish the active session.
 */
@OptIn(markerClass = [UnstableApi::class])
class Media3VodEngine(
    private val context: Context,
    private val config: Media3VodEngineConfig = Media3VodEngineConfig(),
) : VodEngine {

    override val engineName: String = ENGINE_NAME

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var listener: VodEngine.Listener? = null
    private var generationListener: Player.Listener? = null
    private var generation: Long = 0L
    private var activeMediaId: String? = null
    private var reportedFailureGeneration: Long = VodEngine.NO_GENERATION
    private var released = false
    private var aspectMode = VodAspectMode.FIT
    private var preferredAudioLanguage = VodLanguagePolicy.normalize(config.preferredAudioLanguage)
    private var preferredSubtitleLanguage =
        VodLanguagePolicy.normalize(config.preferredSubtitleLanguage)
    private var audioTrackSnapshot: List<VodTrack> = emptyList()
    private var subtitleTrackSnapshot: List<VodTrack> = emptyList()
    private val trackRefs = LinkedHashMap<String, TrackRef>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var renderedFirstFrameGeneration = VodEngine.NO_GENERATION
    private var verifiedFirstFrameGeneration = VodEngine.NO_GENERATION
    private val videoFrameHeartbeatGeneration = AtomicLong(VodEngine.NO_GENERATION)
    private val lastVideoFrameAtMs = AtomicLong(0L)
    private val videoFrameSequence = AtomicLong(0L)
    private val videoFrameMetadataListener = VideoFrameMetadataListener { _, _, _, _ ->
        // Submitted-frame cadence is real decoder/output evidence. Pixel equality
        // is intentionally not used because a legitimate static scene must not be
        // mistaken for a frozen decoder while audio continues.
        if (videoFrameHeartbeatGeneration.get() != VodEngine.NO_GENERATION) {
            lastVideoFrameAtMs.set(SystemClock.elapsedRealtime())
            videoFrameSequence.incrementAndGet()
        }
    }
    private val surfaceFrameHealth = SurfaceFrameHealthMonitor(
        handler = mainHandler,
        onSolidGreen = { reportInvalidVideoOutputWithNativeEvidence() },
        onPersistentBlank = { reportInvalidVideoOutputWithNativeEvidence() },
        onHealthyFrame = { reportVerifiedFirstFrame() },
        onSamplingUnavailable = {
            // Protected/vendor surfaces cannot be sampled. Only after the bounded
            // capability probe is exhausted may Media3's native rendered-frame
            // callback stand in for visual verification.
            if (renderedFirstFrameGeneration == generation) reportVerifiedFirstFrame()
        },
        // VOD needs a bounded startup validation, not a permanent PixelCopy loop.
        continueAfterHealthy = false,
    )
    private val surfaceValidationDeadlineRunnable = Runnable {
        if (
            renderedFirstFrameGeneration == generation &&
            verifiedFirstFrameGeneration != generation &&
            reportedFailureGeneration != generation
        ) {
            // A PixelCopy callback can hang forever on protected/vendor output.
            // The native rendered-frame callback remains valid evidence, so the
            // sampling deadline is capability fallback rather than output failure.
            reportVerifiedFirstFrame()
        }
    }

    @MainThread
    override fun bind(container: ViewGroup) {
        checkMainThread()
        if (released) return
        val exo = ensurePlayer()
        val view = playerView ?: createPlayerView(container, exo).also { playerView = it }
        var outputRehomed = false
        (view.parent as? ViewGroup)?.let { parent ->
            if (parent !== container) {
                parent.removeView(view)
                outputRehomed = true
            }
        }
        if (view.parent == null) {
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            outputRehomed = true
        }
        applyAspectMode(view, aspectMode)
        if (outputRehomed) surfaceFrameHealth.onOutputTransition()
    }

    @MainThread
    override fun play(url: String, resumeMs: Long): Long {
        checkMainThread()
        if (released) return VodEngine.NO_GENERATION
        require(url.isNotBlank()) { "VOD URL must not be blank" }
        val exo = ensurePlayer()

        generation += 1L
        val token = generation
        val mediaId = "vod-media-$token"
        activeMediaId = mediaId
        reportedFailureGeneration = VodEngine.NO_GENERATION
        renderedFirstFrameGeneration = VodEngine.NO_GENERATION
        verifiedFirstFrameGeneration = VodEngine.NO_GENERATION
        videoFrameHeartbeatGeneration.set(VodEngine.NO_GENERATION)
        lastVideoFrameAtMs.set(0L)
        videoFrameSequence.set(0L)
        audioTrackSnapshot = emptyList()
        subtitleTrackSnapshot = emptyList()
        trackRefs.clear()
        surfaceFrameHealth.reset()
        mainHandler.removeCallbacks(surfaceValidationDeadlineRunnable)
        // Publish the token before any backend mutation that can throw. The
        // Activity must be able to correlate a synchronous stop/set-item/prepare
        // failure with this attempt instead of waiting for the startup watchdog.
        listener?.onSubmitted(token)

        detachGenerationListener(exo)
        exo.stop()
        exo.clearMediaItems()

        val boundListener = GenerationListener(token, mediaId)
        generationListener = boundListener
        exo.addListener(boundListener)

        return try {
            val item = MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(url)
                .build()
            exo.setMediaItem(item, resumeMs.coerceAtLeast(0L))
            videoFrameHeartbeatGeneration.set(token)
            exo.prepare()
            exo.playWhenReady = true
            token
        } catch (throwable: Throwable) {
            reportFailureOnce(
                token = token,
                mediaId = mediaId,
                failure = PlaybackFailureClassifier.classifyThrowable(
                    throwable,
                    PlaybackFailure.Phase.STARTUP,
                ),
                requireCurrentMediaItem = false,
            )
            token
        }
    }

    @MainThread
    override fun pause() {
        checkMainThread()
        if (!released) player?.pause()
    }

    @MainThread
    override fun resume() {
        checkMainThread()
        if (released) return
        player?.let { exo ->
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0L)
            exo.play()
        }
    }

    @MainThread
    override fun seekTo(positionMs: Long) {
        checkMainThread()
        if (released) return
        player?.let { exo ->
            val nonNegative = positionMs.coerceAtLeast(0L)
            val duration = exo.duration
            val target = if (duration != C.TIME_UNSET && duration >= 0L) {
                nonNegative.coerceAtMost(duration)
            } else {
                nonNegative
            }
            exo.seekTo(target)
        }
    }

    @MainThread
    override fun stop() {
        checkMainThread()
        if (released) return
        invalidateActiveGeneration(clearMedia = true)
    }

    @MainThread
    override fun durationMs(): Long {
        checkMainThread()
        val duration = player?.duration ?: C.TIME_UNSET
        return duration.takeIf { it != C.TIME_UNSET && it >= 0L } ?: VodEngine.TIME_UNSET_MS
    }

    @MainThread
    override fun positionMs(): Long {
        checkMainThread()
        return player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }

    @MainThread
    override fun isPlaying(): Boolean {
        checkMainThread()
        return !released && player?.isPlaying == true
    }

    @MainThread
    override fun videoLiveness(): VodVideoLiveness? {
        checkMainThread()
        val exo = player ?: return null
        val token = generation
        val mediaId = activeMediaId ?: return null
        val lastFrame = lastVideoFrameAtMs.get()
        if (
            released ||
            verifiedFirstFrameGeneration != token ||
            videoFrameHeartbeatGeneration.get() != token ||
            exo.currentMediaItem?.mediaId != mediaId ||
            exo.videoFormat == null ||
            lastFrame <= 0L
        ) {
            return null
        }
        return VodVideoLiveness(
            generation = token,
            lastFrameRealtimeMs = lastFrame,
            frameSequence = videoFrameSequence.get(),
        )
    }

    @MainThread
    override fun streamInfo(): VodStreamInfo? {
        checkMainThread()
        val exo = player ?: return null
        val video = exo.videoFormat
        val audio = exo.audioFormat
        if (video == null && audio == null) return null
        val bitrate = listOfNotNull(
            video?.peakBitrate?.takeIf { it > 0 },
            video?.averageBitrate?.takeIf { it > 0 },
            audio?.peakBitrate?.takeIf { it > 0 },
            audio?.averageBitrate?.takeIf { it > 0 },
        ).firstOrNull()
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
        return VodStreamInfo(
            width = video?.width?.coerceAtLeast(0) ?: 0,
            height = video?.height?.coerceAtLeast(0) ?: 0,
            frameRate = video?.frameRate?.takeIf { it > 0f } ?: 0f,
            videoMimeType = video?.sampleMimeType,
            videoCodecs = video?.codecs,
            audioMimeType = audio?.sampleMimeType,
            audioCodecs = audio?.codecs,
            bitrateKbps = bitrate?.div(1_000),
            durationMs = duration,
            engine = engineName,
        )
    }

    @MainThread
    override fun audioTracks(): List<VodTrack> {
        checkMainThread()
        return audioTrackSnapshot.toList()
    }

    @MainThread
    override fun subtitleTracks(): List<VodTrack> {
        checkMainThread()
        return subtitleTrackSnapshot.toList()
    }

    @MainThread
    override fun selectAudioTrack(id: String?): Boolean {
        checkMainThread()
        return selectTrack(C.TRACK_TYPE_AUDIO, id, disableWhenNull = false)
    }

    @MainThread
    override fun selectSubtitleTrack(id: String?): Boolean {
        checkMainThread()
        return selectTrack(C.TRACK_TYPE_TEXT, id, disableWhenNull = true)
    }

    @MainThread
    override fun setPreferredLanguages(audio: String?, subtitle: String?) {
        checkMainThread()
        if (released) return
        preferredAudioLanguage = VodLanguagePolicy.normalize(audio)
        preferredSubtitleLanguage = VodLanguagePolicy.normalize(subtitle)
        applyPreferredLanguages(player)
    }

    @MainThread
    override fun setAspectMode(mode: VodAspectMode) {
        checkMainThread()
        aspectMode = mode
        playerView?.let {
            applyAspectMode(it, mode)
            surfaceFrameHealth.onOutputTransition()
        }
    }

    @MainThread
    override fun setListener(listener: VodEngine.Listener?) {
        checkMainThread()
        if (!released || listener == null) this.listener = listener
    }

    @MainThread
    override fun release() {
        checkMainThread()
        if (released) return
        released = true
        generation += 1L
        activeMediaId = null
        reportedFailureGeneration = VodEngine.NO_GENERATION
        renderedFirstFrameGeneration = VodEngine.NO_GENERATION
        verifiedFirstFrameGeneration = VodEngine.NO_GENERATION
        videoFrameHeartbeatGeneration.set(VodEngine.NO_GENERATION)
        lastVideoFrameAtMs.set(0L)
        videoFrameSequence.set(0L)
        listener = null
        surfaceFrameHealth.reset()
        mainHandler.removeCallbacksAndMessages(null)
        trackRefs.clear()
        audioTrackSnapshot = emptyList()
        subtitleTrackSnapshot = emptyList()

        val exo = player
        if (exo != null) {
            detachGenerationListener(exo)
            exo.stop()
            exo.clearMediaItems()
        }
        val view = playerView
        view?.player = null
        exo?.release()
        (view?.parent as? ViewGroup)?.removeView(view)

        player = null
        playerView = null
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        check(!released) { "Released VOD engine cannot create a player" }

        val buffer = config.buffer
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                buffer.minBufferMs,
                buffer.maxBufferMs,
                buffer.playbackBufferMs,
                buffer.rebufferMs,
            )
            .build()
        val httpFactory: DataSource.Factory = UpgradeOnlyHttpDataSourceFactory(
            userAgent = config.userAgent,
            connectTimeoutMs = config.connectTimeoutMs,
            readTimeoutMs = config.readTimeoutMs,
            allowHttpToHttpsRedirects = config.allowHttpToHttpsRedirects,
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
        val selector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(false))
        }
        val renderersFactory = buildRenderersFactory()
        val exo = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .build()
        // The Activity's TvPlaybackSession is the single audio-focus owner for
        // both Media3 and VLC routes, keeping UI/lifecycle state synchronized.
        exo.setAudioAttributes(AudioAttributes.DEFAULT, false)
        exo.setVideoFrameMetadataListener(videoFrameMetadataListener)

        player = exo
        applyPreferredLanguages(exo)
        return exo
    }

    private fun buildRenderersFactory(): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            @Suppress("DEPRECATION", "UNUSED_PARAMETER")
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val builder = if (config.allowAudioPassthrough) {
                    DefaultAudioSink.Builder(context)
                } else {
                    DefaultAudioSink.Builder()
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                }
                return builder
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(false)
                    .build()
            }
        }.setEnableDecoderFallback(true)

    private fun createPlayerView(container: ViewGroup, exo: ExoPlayer): PlayerView {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.view_exo_player, container, false) as PlayerView
        check(view.videoSurfaceView is SurfaceView) {
            "Media3 VOD requires PlayerView backed by SurfaceView"
        }
        view.useController = false
        view.player = exo
        applyAspectMode(view, aspectMode)
        return view
    }

    private fun selectTrack(
        type: Int,
        id: String?,
        disableWhenNull: Boolean,
    ): Boolean {
        if (released) return false
        val exo = player ?: return false
        val builder = exo.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(type)
        if (id == null) {
            exo.trackSelectionParameters = builder
                .setTrackTypeDisabled(type, disableWhenNull)
                .build()
            return true
        }

        val ref = trackRefs[id] ?: return false
        if (ref.generation != generation || ref.type != type) return false
        exo.trackSelectionParameters = builder
            .setTrackTypeDisabled(type, false)
            .addOverride(
                TrackSelectionOverride(ref.group.mediaTrackGroup, listOf(ref.trackIndex)),
            )
            .build()
        return true
    }

    private fun applyPreferredLanguages(exo: ExoPlayer?) {
        exo ?: return
        var builder = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(preferredAudioLanguage)
            .setPreferredTextLanguage(preferredSubtitleLanguage)
        if (preferredSubtitleLanguage != null) {
            builder = builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        exo.trackSelectionParameters = builder.build()
    }

    private fun rebuildTracks(token: Long, tracks: Tracks) {
        if (token != generation) return
        trackRefs.clear()
        val audio = ArrayList<VodTrack>()
        val subtitles = ArrayList<VodTrack>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_AUDIO -> VodTrackType.AUDIO
                C.TRACK_TYPE_TEXT -> VodTrackType.SUBTITLE
                else -> return@forEachIndexed
            }
            repeat(group.length) { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                val id = "$token:${type.name.lowercase(Locale.ROOT)}:$groupIndex:$trackIndex"
                val track = VodTrack(
                    id = id,
                    type = type,
                    label = trackLabel(format, type, trackIndex),
                    language = VodLanguagePolicy.normalize(format.language),
                    roleFlags = format.roleFlags,
                    selected = group.isTrackSelected(trackIndex),
                    supported = group.isTrackSupported(
                        trackIndex,
                        /* allowExceedsCapabilities = */ true,
                    ),
                )
                trackRefs[id] = TrackRef(
                    generation = token,
                    type = group.type,
                    group = group,
                    trackIndex = trackIndex,
                )
                if (type == VodTrackType.AUDIO) audio += track else subtitles += track
            }
        }
        audioTrackSnapshot = audio
        subtitleTrackSnapshot = subtitles
        listener?.onTracksChanged(token, audio.toList(), subtitles.toList())
    }

    private fun trackLabel(format: Format, type: VodTrackType, index: Int): String {
        return format.label
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: VodLanguagePolicy.normalize(format.language)
            ?: if (type == VodTrackType.AUDIO) {
                "Audio ${index + 1}"
            } else {
                "Subtitle ${index + 1}"
            }
    }

    private fun invalidateActiveGeneration(clearMedia: Boolean) {
        generation += 1L
        activeMediaId = null
        reportedFailureGeneration = VodEngine.NO_GENERATION
        renderedFirstFrameGeneration = VodEngine.NO_GENERATION
        verifiedFirstFrameGeneration = VodEngine.NO_GENERATION
        videoFrameHeartbeatGeneration.set(VodEngine.NO_GENERATION)
        lastVideoFrameAtMs.set(0L)
        videoFrameSequence.set(0L)
        surfaceFrameHealth.reset()
        mainHandler.removeCallbacks(surfaceValidationDeadlineRunnable)
        trackRefs.clear()
        audioTrackSnapshot = emptyList()
        subtitleTrackSnapshot = emptyList()
        player?.let { exo ->
            detachGenerationListener(exo)
            exo.stop()
            if (clearMedia) exo.clearMediaItems()
        }
    }

    private fun detachGenerationListener(exo: ExoPlayer) {
        generationListener?.let(exo::removeListener)
        generationListener = null
    }

    private fun isCurrent(token: Long, mediaId: String): Boolean {
        if (released || token != generation || activeMediaId != mediaId) return false
        return player?.currentMediaItem?.mediaId == mediaId
    }

    private fun reportInvalidVideoOutput() {
        val token = generation
        val mediaId = activeMediaId ?: return
        mainHandler.removeCallbacks(surfaceValidationDeadlineRunnable)
        reportFailureOnce(
            token = token,
            mediaId = mediaId,
            failure = PlaybackFailure(
                category = PlaybackFailure.Category.OUTPUT,
                code = PlaybackFailure.Code.VIDEO_OUTPUT_FAILED,
                phase = if (verifiedFirstFrameGeneration == token) {
                    PlaybackFailure.Phase.PLAYBACK
                } else {
                    PlaybackFailure.Phase.STARTUP
                },
                component = PlaybackFailure.Component.VIDEO,
                retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_ENGINE,
            ),
        )
    }

    /** Pixel colour is actionable only when the backend also submitted video frames. */
    private fun reportInvalidVideoOutputWithNativeEvidence() {
        val token = generation
        val mediaId = activeMediaId ?: return
        if (!surfaceFrameHealth.hasHealthyFrame()) {
            // A protected/vendor hardware underlay can remain black to PixelCopy
            // even while the viewer sees valid video. Startup colour alone is
            // therefore advisory until this surface produced one healthy sample.
            reportVerifiedFirstFrame()
            return
        }
        val nativeVideoEvidence =
            renderedFirstFrameGeneration == token &&
                videoFrameHeartbeatGeneration.get() == token &&
                videoFrameSequence.get() > 0L &&
                player?.currentMediaItem?.mediaId == mediaId
        if (nativeVideoEvidence) {
            reportInvalidVideoOutput()
        } else {
            // Sampling an empty/recreated surface is inconclusive, not terminal.
            reportVerifiedFirstFrame()
        }
    }

    private fun reportFailureOnce(
        token: Long,
        mediaId: String,
        failure: PlaybackFailure,
        requireCurrentMediaItem: Boolean = true,
    ) {
        val active =
            !released && token == generation && activeMediaId == mediaId
        if (!active || reportedFailureGeneration == token) return
        if (requireCurrentMediaItem && player?.currentMediaItem?.mediaId != mediaId) return
        reportedFailureGeneration = token
        listener?.onFailure(token, failure)
    }

    /** Expose playback only after a healthy pixel sample or bounded native fallback. */
    private fun reportVerifiedFirstFrame() {
        val token = generation
        val mediaId = activeMediaId ?: return
        if (
            released ||
            renderedFirstFrameGeneration != token ||
            verifiedFirstFrameGeneration == token ||
            reportedFailureGeneration == token ||
            player?.currentMediaItem?.mediaId != mediaId
        ) {
            return
        }
        verifiedFirstFrameGeneration = token
        mainHandler.removeCallbacks(surfaceValidationDeadlineRunnable)
        listener?.onFirstFrame(token)
    }

    private fun classifyError(
        error: PlaybackException,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure {
        findHttpStatus(error)?.takeIf { it in 400..599 }?.let { status ->
            return PlaybackFailureClassifier.classify(FailureSignal.Http(status), phase)
        }

        val code = error.errorCode
        if (code in DRM_ERROR_RANGE) {
            val kind = when (code) {
                PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ->
                    FailureSignal.DrmKind.PROVISIONING
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
                    FailureSignal.DrmKind.LICENSE
                else -> FailureSignal.DrmKind.CONTENT_RESTRICTED
            }
            return PlaybackFailureClassifier.classify(FailureSignal.Drm(kind), phase)
        }
        if (code in VIDEO_OUTPUT_ERROR_RANGE) {
            return PlaybackFailureClassifier.classify(
                FailureSignal.Output(PlaybackFailure.Component.VIDEO),
                phase,
            )
        }
        if (code in AUDIO_OUTPUT_ERROR_RANGE) {
            return PlaybackFailureClassifier.classify(
                FailureSignal.Output(PlaybackFailure.Component.AUDIO),
                phase,
            )
        }
        if (code in DECODER_ERROR_RANGE) {
            if (
                code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
            ) {
                return PlaybackFailureClassifier.classify(
                    FailureSignal.Source(FailureSignal.SourceKind.UNSUPPORTED_CODEC),
                    phase,
                )
            }
            val component = decoderComponent(error)
            val kind = when (code) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                    FailureSignal.DecoderKind.INIT
                PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
                    FailureSignal.DecoderKind.RESOURCES_RECLAIMED
                else -> FailureSignal.DecoderKind.RUNTIME
            }
            return PlaybackFailureClassifier.classify(
                FailureSignal.Decoder(kind, component),
                phase,
            )
        }
        if (code in PARSING_ERROR_RANGE) {
            val kind = when (code) {
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                    FailureSignal.SourceKind.UNSUPPORTED_CONTAINER
                else -> FailureSignal.SourceKind.MALFORMED
            }
            return PlaybackFailureClassifier.classify(FailureSignal.Source(kind), phase)
        }
        if (code in IO_ERROR_RANGE) {
            return classifyIoError(error, phase)
        }
        return PlaybackFailureClassifier.classifyThrowable(error, phase)
    }

    private fun classifyIoError(
        error: PlaybackException,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            PlaybackFailureClassifier.classify(
                FailureSignal.Network(FailureSignal.NetworkKind.READ_TIMEOUT),
                phase,
            )
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailure(
            category = PlaybackFailure.Category.SOURCE,
            code = PlaybackFailure.Code.SOURCE_UNSUPPORTED,
            phase = phase,
            component = PlaybackFailure.Component.TRANSPORT,
            retryAdvice = PlaybackFailure.RetryAdvice.DO_NOT_RETRY,
        )
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlaybackFailure(
            category = PlaybackFailure.Category.NETWORK,
            code = PlaybackFailure.Code.TLS_FAILED,
            phase = phase,
            component = PlaybackFailure.Component.TRANSPORT,
            retryAdvice = PlaybackFailure.RetryAdvice.DO_NOT_RETRY,
        )
        else -> {
            val classified = PlaybackFailureClassifier.classifyThrowable(error, phase)
            if (classified.category != PlaybackFailure.Category.UNKNOWN) {
                classified
            } else {
                PlaybackFailureClassifier.classify(
                    FailureSignal.Network(FailureSignal.NetworkKind.CONNECT),
                    phase,
                )
            }
        }
    }

    private fun decoderComponent(error: PlaybackException): PlaybackFailure.Component {
        val exoError = error as? ExoPlaybackException
        val rendererType = if (
            exoError?.type == ExoPlaybackException.TYPE_RENDERER &&
            exoError.rendererIndex >= 0
        ) {
            runCatching { player?.getRendererType(exoError.rendererIndex) }.getOrNull()
        } else {
            null
        }
        return if (rendererType == C.TRACK_TYPE_AUDIO) {
            PlaybackFailure.Component.AUDIO
        } else {
            PlaybackFailure.Component.VIDEO
        }
    }

    private fun findHttpStatus(error: Throwable): Int? {
        var cause: Throwable? = error
        repeat(MAX_CAUSE_DEPTH) {
            val current = cause ?: return null
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return current.responseCode
            }
            cause = current.cause
        }
        return null
    }

    private fun applyAspectMode(view: PlayerView, mode: VodAspectMode) {
        // Keep the SurfaceView allocation full-screen and stable. Resizing it when
        // a video's dimensions arrive tears down the hardware underlay on several
        // Amlogic/Fire TV compositors and is a common source of the green-screen
        // failure this engine is designed to recover from.
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        player?.videoScalingMode = when (mode) {
            VodAspectMode.FILL,
            VodAspectMode.ZOOM -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            else -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
        val surface = view.videoSurfaceView ?: return
        view.post {
            if (released || playerView !== view) return@post
            val displayAspect = view.width.toFloat() / view.height.coerceAtLeast(1)
            val targetAspect = when (mode) {
                VodAspectMode.FIXED_WIDTH -> 16f / 9f
                VodAspectMode.FIXED_HEIGHT -> 4f / 3f
                else -> null
            }
            if (targetAspect == null || displayAspect <= 0f) {
                surface.scaleX = 1f
                surface.scaleY = 1f
            } else {
                val ratio = targetAspect / displayAspect
                surface.scaleX = ratio.coerceAtMost(1f)
                surface.scaleY = (1f / ratio).coerceAtMost(1f)
            }
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Media3VodEngine must be accessed from the Android main thread"
        }
    }

    private inner class GenerationListener(
        private val token: Long,
        private val mediaId: String,
    ) : Player.Listener {
        private var firstFrameReported = false
        private var endedReported = false
        private var failureReported = false

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isCurrent(token, mediaId)) return
            when (playbackState) {
                Player.STATE_BUFFERING -> listener?.onBuffering(token)
                Player.STATE_READY -> listener?.onReady(token)
                Player.STATE_ENDED -> if (!endedReported) {
                    endedReported = true
                    listener?.onEnded(token)
                }
            }
        }

        override fun onRenderedFirstFrame() {
            if (!isCurrent(token, mediaId) || firstFrameReported) return
            firstFrameReported = true
            lastVideoFrameAtMs.set(SystemClock.elapsedRealtime())
            videoFrameSequence.incrementAndGet()
            renderedFirstFrameGeneration = token
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                !config.enablePixelCopyValidation
            ) {
                // PixelCopy is unavailable before API 24; the native callback is
                // the only usable readiness signal on those supported devices.
                reportVerifiedFirstFrame()
                return
            }
            surfaceFrameHealth.start {
                playerView?.videoSurfaceView as? SurfaceView
            }
            mainHandler.removeCallbacks(surfaceValidationDeadlineRunnable)
            mainHandler.postDelayed(
                surfaceValidationDeadlineRunnable,
                PIXEL_VALIDATION_DEADLINE_MS,
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!isCurrent(token, mediaId)) return
            rebuildTracks(token, tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isCurrent(token, mediaId) || failureReported) return
            failureReported = true
            val phase = if (firstFrameReported) {
                PlaybackFailure.Phase.PLAYBACK
            } else {
                PlaybackFailure.Phase.STARTUP
            }
            reportFailureOnce(token, mediaId, classifyError(error, phase))
        }
    }

    private data class TrackRef(
        val generation: Long,
        val type: Int,
        val group: Tracks.Group,
        val trackIndex: Int,
    )

    companion object {
        private const val ENGINE_NAME = "Media3"
        private const val MAX_CAUSE_DEPTH = 8
        private const val PIXEL_VALIDATION_DEADLINE_MS = 9_000L
        private val IO_ERROR_RANGE = 2_000..2_999
        private val PARSING_ERROR_RANGE = 3_000..3_999
        private val DECODER_ERROR_RANGE = 4_000..4_999
        private val AUDIO_OUTPUT_ERROR_RANGE = 5_000..5_999
        private val DRM_ERROR_RANGE = 6_000..6_999
        private val VIDEO_OUTPUT_ERROR_RANGE = 7_000..7_999
    }
}

/**
 * DefaultHttpDataSource follows same-protocol redirects itself. Cross-protocol
 * following stays disabled; when an HTTP response explicitly upgrades to HTTPS,
 * this wrapper reopens exactly once at the secure target. A later downgrade is
 * rejected by the fresh delegate and never requested automatically.
 */
@OptIn(markerClass = [UnstableApi::class])
private class UpgradeOnlyHttpDataSourceFactory(
    private val userAgent: String,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val allowHttpToHttpsRedirects: Boolean,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = UpgradeOnlyHttpDataSource(
        sourceFactory = {
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(connectTimeoutMs)
                .setReadTimeoutMs(readTimeoutMs)
                .setAllowCrossProtocolRedirects(false)
                .createDataSource()
        },
        allowHttpToHttpsRedirects = allowHttpToHttpsRedirects,
    )
}

@OptIn(markerClass = [UnstableApi::class])
private class UpgradeOnlyHttpDataSource(
    private val sourceFactory: () -> HttpDataSource,
    private val allowHttpToHttpsRedirects: Boolean,
) : DataSource {
    private val transferListeners = ArrayList<TransferListener>()
    private var source: HttpDataSource = newSource()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        source.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        return try {
            source.open(dataSpec)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            val location = error.headerFields.entries
                .firstOrNull { (name, _) -> name.equals("Location", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
            val target = if (allowHttpToHttpsRedirects) {
                VodHttpRedirectPolicy.httpsUpgradeTarget(
                    originalUrl = dataSpec.uri.toString(),
                    status = error.responseCode,
                    location = location,
                )
            } else {
                null
            } ?: throw error

            runCatching { source.close() }
            source = newSource()
            source.open(dataSpec.withUri(Uri.parse(target)))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        source.read(buffer, offset, length)

    override fun getUri(): Uri? = source.uri

    override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders

    override fun close() {
        source.close()
    }

    private fun newSource(): HttpDataSource = sourceFactory().also { next ->
        transferListeners.forEach(next::addTransferListener)
    }
}
