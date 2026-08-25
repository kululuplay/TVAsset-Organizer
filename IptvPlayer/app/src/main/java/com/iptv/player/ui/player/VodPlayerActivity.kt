/*
 * VodPlayerActivity.kt
 * On-demand player for movies and series episodes. Built on libVLC so that, like
 * live TV, VOD deliberately plays through VLC for the widest
 * codec/container support (DTS/AC3/EAC3, AVI, MKV, …). Provides:
 *   - play/pause, a seekbar with current/total time
 *   - +10s / -10s skipping
 *   - audio-track and subtitle-track selection menus
 *   - aspect-ratio cycling (AspectRatio.next())
 *   - resume support: prompts to continue from a saved position and periodically
 *     persists progress via the repository.
 * Receives EXTRA_STREAM_URL, EXTRA_TITLE and EXTRA_RESUME_ID.
 */
package com.iptv.player.ui.player

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iptv.player.R
import com.iptv.player.cast.CastController
import com.iptv.player.cast.ProviderConnectionSafety
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.AspectRatio
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.Episode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.ResumeMeta
import com.iptv.player.data.model.Season
import com.iptv.player.databinding.ActivityVodPlayerBinding
import com.iptv.player.player.StreamInfo
import com.iptv.player.player.SurfaceFrameHealthMonitor
import com.iptv.player.player.TrackLanguage
import com.iptv.player.player.TvPlaybackSession
import com.iptv.player.player.VlcOps
import com.iptv.player.player.VlcNativeCleanup
import com.iptv.player.player.VlcNativeCleanupResult
import com.iptv.player.player.VodPlaybackRoutingPolicy
import com.iptv.player.player.findVideoSurface
import com.iptv.player.player.isVlcBuffering
import com.iptv.player.player.vod.Media3VodEngine
import com.iptv.player.player.vod.Media3VodEngineConfig
import com.iptv.player.player.vod.VodAspectMode
import com.iptv.player.player.vod.VodBufferConfig
import com.iptv.player.player.vod.VodConnectionLifecyclePolicy
import com.iptv.player.player.vod.VodEngine
import com.iptv.player.player.vod.VodPlaybackCoordinator
import com.iptv.player.player.vod.VodRouteKey
import com.iptv.player.player.vod.VodTrack
import com.iptv.player.playback.core.FailureSignal
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackResourceGovernor
import com.iptv.player.playback.core.PlaybackResourceToken
import com.iptv.player.playback.core.PlaybackFailureClassifier
import com.iptv.player.playback.android.PlaybackQoeRuntime
import com.iptv.player.playback.android.PlaybackProcessRecovery
import com.iptv.player.playback.android.PlaybackProcessRecoveryTargetProvider
import com.iptv.player.playback.core.PlaybackContentKind
import com.iptv.player.playback.core.PlaybackEndReason
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackSessionId
import com.iptv.player.playback.core.PlaybackTransportKind
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.SleepTimer
import com.iptv.player.util.AppInfo
import com.iptv.player.util.DebugOverlayBinder
import com.iptv.player.util.NowPlaying
import com.iptv.player.util.PlaybackLog
import com.iptv.player.util.PlaybackRouteMemory
import com.iptv.player.util.PlaybackRemotePolicy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VodPlayerActivity : BaseActivity(), PlaybackProcessRecoveryTargetProvider {

    private enum class RecoveryEvidence {
        GENERIC,
        CONFIRMED_VIDEO_FAILURE,
    }

    private class NativePlayerOwner(
        val generation: Long,
        val libVlc: LibVLC,
        val mediaPlayer: MediaPlayer,
        val videoLayout: VLCVideoLayout,
    ) {
        val abandoned = AtomicBoolean(false)
        val cleanupScheduled = AtomicBoolean(false)
        val cleanupClaimed = AtomicBoolean(false)
        val ownershipDefinitivelyReleased = AtomicBoolean(false)
        val activeOperation = AtomicReference<OwnerOperation?>(null)
        val providerUncertaintyToken = AtomicLong(0L)
    }

    private class OwnerOperation(
        val owner: NativePlayerOwner,
    ) {
        val state = AtomicInteger(STATE_ACTIVE)
        val onAbandoned = AtomicReference<(() -> Unit)?>(null)

        companion object {
            const val STATE_ACTIVE = 0
            const val STATE_ABANDONED = 1
            const val STATE_BODY_FINISHED = 2
            const val STATE_CLEANED = 3
        }
    }

    private data class VlcPlaybackSnapshot(
        val ownerGeneration: Long,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val streamInfo: StreamInfo?,
        val decodedVideo: Int?,
        val displayedPictures: Int?,
        val playedAudioBuffers: Int?,
        val readBytes: Int?,
    )

    /** Never cover movie, episode or catch-up playback with the idle saver. */
    protected override val idleScreensaverEnabledForScreen: Boolean
        get() = false

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_RESUME_ID = "extra_resume_id"
        // Display + navigation metadata persisted with the resume position so the
        // Continue Watching rail can render and reopen this content later.
        const val EXTRA_RESUME_TITLE = "extra_resume_title"
        const val EXTRA_RESUME_TYPE = "extra_resume_type"
        const val EXTRA_POSTER_URL = "extra_poster_url"
        const val EXTRA_VOD_ID = "extra_vod_id"
        const val EXTRA_SERIES_ID = "extra_series_id"
        const val EXTRA_SEASON = "extra_season"
        const val EXTRA_EPISODE = "extra_episode"
        // When true (e.g. launched from the rail) skip the prompt and resume directly.
        const val EXTRA_AUTO_RESUME = "extra_auto_resume"
        private const val EXTRA_RECOVERY_POSITION_MS = "extra_recovery_position_ms"
        private const val SKIP_MS = 10_000L
        private const val SAVE_INTERVAL_MS = 10_000L
        // UHD/4K threshold (QHD 1440p+). AUTO's software fallback cannot keep up
        // with 4K at 80–100 Mbps, so above this it returns to hardware.
        private const val UHD_MIN_HEIGHT = 1440
        private const val UHD_MIN_WIDTH = 2560
        // VLC frequently exposes video dimensions only after Playing/ESAdded.
        // Re-check across that discovery window rather than trusting the first
        // Playing callback (where currentVideoTrack is commonly still null).
        private const val UHD_RECHECK_1_MS = 700L
        private const val UHD_RECHECK_2_MS = 1_500L
        private const val UHD_RECHECK_3_MS = 3_000L
        private const val UHD_RECHECK_4_MS = 5_000L
        // How long the "languages / subtitles available" banner stays visible.
        private const val TRACK_HINT_MS = 4500L
        // Debounce after track-detection events before evaluating the hint, so we
        // wait for the full set of audio/subtitle tracks to be parsed by VLC.
        private const val TRACK_HINT_DEBOUNCE_MS = 1200L
        // Auto-hide the on-screen controls after this much inactivity.
        private const val CONTROLS_TIMEOUT_MS = 5000L
        private const val NEXT_EPISODE_COUNTDOWN_SECONDS = 8
        // A decoder path is considered recovered only after continuous playback.
        private const val STABLE_PLAYBACK_MS = 10_000L
        private const val VOD_RETRY_DELAY_MS = 1_000L
        // A dead CDN/socket can stay in VLC's buffering state forever without
        // emitting EncounteredError. Bound every startup/retry so the viewer
        // always reaches recovery or a visible Retry action.
        private const val STARTUP_TIMEOUT_MS = 25_000L
        // Native libVLC calls can deadlock inside vendor JNI. Keep playback
        // recovery moving on a fresh shared worker instead of queuing forever.
        private const val NATIVE_OPERATION_TIMEOUT_MS = 8_000L
        private const val NATIVE_RELEASE_TIMEOUT_MS = 12_000L
        private const val NATIVE_EVENT_RETRY_MS = 16L
        private const val VLC_SNAPSHOT_INTERVAL_MS = 1_000L
        // After playback has started, a half-open HTTP connection can freeze
        // without EOF/error. Position must advance inside this window.
        private const val STALL_TIMEOUT_MS = 30_000L
        // Media3's renderer heartbeat catches the inverse failure: audio/media
        // time advances while video frames stop. Keep the window conservative so
        // seek/rebuffer transitions cannot be mistaken for a decoder freeze.
        private const val VIDEO_FRAME_STALL_TIMEOUT_MS = 12_000L
        private const val VIDEO_CLOCK_EVIDENCE_MS = 4_000L
        private const val TRACK_DISABLED = "__off__"
    }

    private lateinit var binding: ActivityVodPlayerBinding
    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private var libVlc: LibVLC? = null
    @Volatile private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    @Volatile private var nativeOwner: NativePlayerOwner? = null
    private val nativeOwnerGeneration = AtomicLong(0L)
    @Volatile private var vlcPlaybackSnapshot: VlcPlaybackSnapshot? = null
    private var lastVlcSnapshotRequestAtMs = 0L
    private var debugBinder: DebugOverlayBinder? = null

    private var streamUrl: String? = null
    private var resumeId: String? = null
    private var resumeMeta: ResumeMeta? = null
    private var autoResume = false
    private var processRecoveryPositionMs = 0L
    private var resumeSaveJob: Job? = null
    private var aspectSaveJob: Job? = null
    private var audioPreferenceJob: Job? = null
    private var subtitlePreferenceJob: Job? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var prefetchedResumeId: String? = null

    private var aspect: AspectRatio = AspectRatio.ORIGINAL
    private var userSeeking = false

    private var decoderMode = DecoderMode.AUTO
    private var playerMode = PlayerMode.AUTO
    private var bufferMode = BufferMode.NORMAL
    private var playbackResourceToken: PlaybackResourceToken? = null
    private var allowPassthrough = false

    /** Active hybrid VOD backend. Exactly one of this engine or [nativeOwner] owns output. */
    private var activeVodRoute = VodPlaybackRoutingPolicy.Route.VLC_HARDWARE
    private var media3VodEngine: VodEngine? = null
    private var media3Generation = VodEngine.NO_GENERATION
    private var media3CoordinatorGeneration = 0L
    private var media3PreferencesAppliedGeneration = VodEngine.NO_GENERATION
    private val vodCoordinator = VodPlaybackCoordinator()
    private var coordinatorGeneration = 0L
    private var activeRouteStable = false
    private var activeProfileId = -1L
    private var vodRouteKey: String? = null
    private var rememberedInitialRoute = false
    private var qoeSessionId: PlaybackSessionId? = null
    private var castHandoffStopped = false
    private var castResumePositionMs = 0L
    private var connectedCastReloadRequested = false
    private var castLoadFailureHandled = false
    private var castLocalRestorePending = false

    // Mirrors live TV: when Decoder is SOFTWARE, MediaCodec is disabled.
    private var forceSoftware = false
    // Set once AUTO rebuilds on hardware for a UHD/4K stream, limiting it to one pass.
    private var uhdEscalated = false

    // VOD error recovery: retry the current decode path once, then (AUTO decoder
    // only) rebuild once on VLC software before showing the terminal Retry UI.
    private var playbackErrorAttempts = 0
    private var softwareFallbackAttempted = false
    // Decoder selection is a preference, not a trap. A confirmed green/blank/
    // frozen output may try the opposite VLC decoder once per playback item.
    // Keeping this independent from the stable-playback retry reset prevents a
    // hardware <-> software loop on a stream that fails repeatedly.
    private var crossDecoderRescueAttempted = false
    private var recoveryInProgress = false
    private var resumeAfterBackground = false
    private var pauseAfterBackgroundRestore = false
    private var backgroundResumePosition = 0L
    private var playbackRequested = false
    private var playbackStarted = false
    private var playbackEnded = false
    private var completionHandled = false
    private var completionGeneration = 0L
    private var pendingNextEpisode: Episode? = null
    private val nextEpisodeGate = VodNextEpisodeGate()
    private var nextEpisodeSecondsRemaining = 0
    private var resumeChoicePending = false
    @Volatile private var foreground = false
    private val eventSession = AtomicLong(0L)
    private val playbackOpsSeq = AtomicLong(0L)
    /** Cancels a Media3 start that is still waiting behind an older VLC teardown. */
    private val providerStartSeq = AtomicLong(0L)
    private val nativeCommandsInFlight = AtomicInteger(0)
    private val stablePlaybackRunnable = Runnable {
        playbackErrorAttempts = 0
        softwareFallbackAttempted = false
        activeRouteStable = true
        PlaybackRouteMemory.markStable(vodRouteKey, activeVodRoute.name)
        rememberedInitialRoute = false
    }
    private var startupTimeoutOperation = 0L
    private var lastObservedPositionMs = -1L
    private var lastPositionAdvanceAtMs = 0L
    private var bufferingSinceMs = 0L
    private var decodedVideoSeen = false
    private var videoLivenessGeneration = VodEngine.NO_GENERATION
    private var videoLivenessWatchStartedAtMs = 0L
    private var videoLivenessStartPositionMs = 0L
    private var vlcVideoLivenessOwnerGeneration = -1L
    private var vlcVideoLivenessState = VlcVideoLivenessPolicy.State()
    private val startupTimeoutRunnable = Runnable {
        if (
            foreground &&
            !playbackStarted &&
            playbackOpsSeq.get() == startupTimeoutOperation
        ) {
            PlaybackLog.log(this, "VOD", "startup timeout")
            handleTypedPlaybackFailure(
                PlaybackFailureClassifier.classify(
                    FailureSignal.Timeout(FailureSignal.TimeoutKind.STARTUP),
                    PlaybackFailure.Phase.STARTUP,
                ),
            )
        }
    }

    // Label for the live-device "now playing" panel (Film / Dizi / Geri Sar).
    private var nowKind = "Film"

    // Position to jump to once playback actually starts (resume support). VLC only
    // accepts a seek after the first Playing event, so we defer it until then.
    private var pendingSeekMs = 0L
    // Retained across a decoder rebuild, where mediaPlayer is intentionally null
    // during onStop but the pending position still needs a durable Room snapshot.
    private var lastKnownDurationMs = 0L

    // Show the multi-language / subtitle hint at most once per playback session.
    private var trackHintShown = false
    private val trackHintRunnable = Runnable { showTrackHintIfAvailable() }
    private val hideTrackHintRunnable = Runnable {
        val banner = binding.trackHintBanner
        banner.animate().cancel()
        banner.animate().alpha(0f).setDuration(180L)
            .withEndAction { banner.visibility = View.GONE }
            .start()
    }
    private var preferredAudioTrack: String? = null
    private var preferredSubtitleTrack: String? = null
    private val preferredTrackRunnable = Runnable { applyPreferredTracks() }

    // Controls (top/transport/bottom bars) auto-hide after a few seconds idle and
    // reappear on any key press or touch. Bars start visible (see the layout).
    private var controlsVisible = true
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private var activeDialog: Dialog? = null
    private var playerChromeFocusable = true

    private val sleepTimer = SleepTimer { finish() }
    private val castController by lazy {
        CastController(
            activity = this,
            mediaProvider = {
                val url = streamUrl ?: return@CastController null
                CastController.CastMedia(
                    url = url,
                    title = binding.titleText.text.toString(),
                    imageUrl = resumeMeta?.posterUrl,
                    isLive = false,
                    startPositionMs = bestResumePosition(activePositionMs()),
                )
            },
            onCastLoadStarting = { onQuiesced ->
                castLocalRestorePending = false
                handoffToCast(onQuiesced)
            },
            onCastQuiesceFailed = {
                castLoadFailureHandled = true
                castLocalRestorePending = false
                castHandoffStopped =
                    ProviderConnectionSafety.currentUncertainty() ==
                        ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT
                showTerminalError(R.string.error_playback_ownership_uncertain)
            },
            onCastLoadFailed = {
                castLoadFailureHandled = true
                castLocalRestorePending = true
                resumeAfterCast()
            },
            // Local ownership was already quiesced before loadMedia; success only
            // confirms that the receiver now owns the provider connection.
            onCastStarted = {
                castLocalRestorePending = false
                // attach() can discover a receiver session that predates this
                // Activity. No local backend has opened yet, but the eventual
                // session-end callback must still restore VOD automatically.
                castHandoffStopped = true
                resumeAfterBackground = false
            },
            onCastEnded = {
                castLocalRestorePending = true
                resumeAfterCast()
            },
        )
    }

    private val playbackSession by lazy {
        TvPlaybackSession(
            context = this,
            tag = "TVAsset-VOD",
            controls = TvPlaybackSession.Controls(
                onPlay = { if (foreground && !activeIsPlaying()) togglePlayPause() },
                onPause = { if (foreground && activeIsPlaying()) togglePlayPause() },
                onToggle = { if (foreground) togglePlayPause() },
                onStop = { if (foreground) pauseForExternalPlayback() },
                onSeekBy = { deltaMs -> if (foreground) seekBy(deltaMs) },
            ),
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var surfaceValidationStarted = false
    private var surfaceOutputConfirmed = false
    private val surfaceFrameHealth = SurfaceFrameHealthMonitor(
        handler = handler,
        onSolidGreen = {
            onSurfaceOutputFailure("persistent solid-green video output")
        },
        onPersistentBlank = {
            onSurfaceOutputFailure("persistent blank video output")
        },
        onHealthyFrame = {
            if (!surfaceOutputConfirmed) {
                surfaceOutputConfirmed = true
                PlaybackQoeRuntime.markFirstFrame(qoeSessionId)
                PlaybackLog.log(this, "VOD", "video surface output verified")
            }
        },
        // Keep checking after startup. Vendor decoders can turn green/black after
        // an initially healthy picture while VLC time and audio continue.
        continueAfterHealthy = true,
    )
    private val uhdCheckRunnable = object : Runnable {
        override fun run() {
            if (
                foreground &&
                playbackStarted &&
                !recoveryInProgress &&
                maybeEscalateForUhd()
            ) {
                handler.removeCallbacks(this)
            }
        }
    }
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }
    private val saveRunnable = object : Runnable {
        override fun run() {
            persistResume()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }
    private val nextEpisodeCountdownRunnable = object : Runnable {
        override fun run() {
            if (
                binding.nextEpisodeOverlay.visibility != View.VISIBLE ||
                !nextEpisodeGate.isPending(completionGeneration)
            ) {
                return
            }
            nextEpisodeSecondsRemaining--
            if (nextEpisodeSecondsRemaining <= 0) {
                playPendingNextEpisode()
            } else {
                updateNextEpisodeCountdown()
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    /**
     * Return the current player only while no bounded owner operation can be
     * touching it. libVLC getters/setters are JNI too; progress, controls and
     * debug UI must not race prepare/stop/release on the owner worker.
     */
    private fun currentIdlePlayer(): MediaPlayer? {
        val owner = nativeOwner ?: return null
        if (
            owner.abandoned.get() ||
            owner.activeOperation.get() != null ||
            mediaPlayer !== owner.mediaPlayer
        ) {
            return null
        }
        return owner.mediaPlayer
    }

    private fun isMedia3Route(): Boolean =
        activeVodRoute == VodPlaybackRoutingPolicy.Route.EXO

    private fun currentVlcSnapshot(): VlcPlaybackSnapshot? {
        val owner = nativeOwner ?: return null
        return vlcPlaybackSnapshot?.takeIf { it.ownerGeneration == owner.generation }
    }

    private fun activePositionMs(): Long = if (isMedia3Route()) {
        media3VodEngine?.positionMs()?.coerceAtLeast(0L) ?: 0L
    } else {
        currentVlcSnapshot()?.positionMs
            ?: lastObservedPositionMs.coerceAtLeast(0L)
    }

    private fun activeDurationMs(): Long = if (isMedia3Route()) {
        media3VodEngine?.durationMs()?.coerceAtLeast(0L) ?: 0L
    } else {
        currentVlcSnapshot()?.durationMs ?: lastKnownDurationMs
    }

    private fun activeIsPlaying(): Boolean = if (isMedia3Route()) {
        media3VodEngine?.isPlaying() == true
    } else {
        currentVlcSnapshot()?.playing == true
    }

    private fun activeEngineExists(): Boolean = if (isMedia3Route()) {
        media3VodEngine != null
    } else {
        nativeOwner != null
    }

    private fun pauseForExternalPlayback() {
        if (!activeIsPlaying()) return
        handler.removeCallbacks(stablePlaybackRunnable)
        if (isMedia3Route()) {
            media3VodEngine?.pause()
            binding.playPauseButton.setImageResource(R.drawable.ic_play)
            binding.playPauseButton.contentDescription = getString(R.string.detail_play)
        } else {
            postBoundedOwnerCommand(
                label = "external playback pause",
                recoverAsPaused = true,
            ) { it.pause() }
        }
        playbackSession.setPlaying(false, activePositionMs())
        handler.removeCallbacks(hideControlsRunnable)
        setControlsVisible(true)
    }

    private fun handoffToCast(onQuiesced: (Boolean) -> Unit) {
        castResumePositionMs = bestResumePosition(activePositionMs())
        backgroundResumePosition = castResumePositionMs
        persistResume(durable = true)
        castHandoffStopped = true
        recoveryInProgress = false
        playbackStarted = false
        // Invalidate a delayed source retry or an in-flight prepare before the
        // receiver becomes the playback owner. Otherwise a one-second retry can
        // reopen a hidden local provider socket underneath Cast.
        playbackOpsSeq.incrementAndGet()
        invalidateEventSession()
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(stablePlaybackRunnable)
        var quiescedSynchronously = false
        if (isMedia3Route()) {
            media3VodEngine?.stop()
            media3Generation = VodEngine.NO_GENERATION
            quiescedSynchronously = true
        } else {
            val owner = nativeOwner
            if (owner == null) {
                // There is no local native player (and therefore no provider
                // socket) to drain before the Cast receiver may load.
                quiescedSynchronously = true
            } else if (owner.activeOperation.get() != null) {
                quarantineOwner(owner, "cast handoff during native operation")
                onQuiesced(false)
            } else {
                val posted = postBoundedOwnerCommand(
                    label = "cast handoff stop",
                    recoverOnFailure = false,
                    onCompletedMain = { onQuiesced(true) },
                ) { it.stop() }
                if (!posted) onQuiesced(false)
            }
        }
        playbackSession.setPlaying(false, castResumePositionMs)
        playbackSession.abandonAudioFocus()
        finishQoe(PlaybackEndReason.REPLACED)
        setBuffering(false)
        setControlsVisible(true)
        if (quiescedSynchronously) onQuiesced(true)
    }

    private fun resumeAfterCast() {
        if (!castHandoffStopped || !foreground || isFinishing || isDestroyed) return
        castHandoffStopped = false
        castLocalRestorePending = false
        pauseAfterBackgroundRestore = !playbackSession.requestAudioFocus()
        // The receiver can advance well beyond the local hand-off snapshot. Use
        // its final buffered-media position without ever moving the viewer back
        // if a disconnect callback reports a transient zero/older value.
        val receiverPositionMs = castController.lastKnownPositionMs.coerceAtLeast(0L)
        val unboundedResumeMs = maxOf(castResumePositionMs, receiverPositionMs)
        val resumeAtMs = lastKnownDurationMs.takeIf { it > 0L }
            ?.let(unboundedResumeMs::coerceAtMost)
            ?: unboundedResumeMs
        castResumePositionMs = resumeAtMs
        backgroundResumePosition = resumeAtMs
        rebuildRoute(resumeAtMs, activeVodRoute)
    }

    private fun startQoeIfNeeded() {
        if (qoeSessionId != null) return
        qoeSessionId = PlaybackQoeRuntime.start(
            kind = when (resumeMeta?.kind) {
                ResumeKind.EPISODE -> PlaybackContentKind.VOD_EPISODE
                ResumeKind.CATCHUP -> PlaybackContentKind.CATCH_UP
                else -> PlaybackContentKind.VOD_MOVIE
            },
            engine = qoeEngine(activeVodRoute),
            transport = qoeTransport(streamUrl),
        )
    }

    private fun finishQoe(reason: PlaybackEndReason) {
        val session = qoeSessionId ?: return
        qoeSessionId = null
        PlaybackQoeRuntime.finish(session, reason)
    }

    private fun qoeEngine(route: VodPlaybackRoutingPolicy.Route): PlaybackEngineKind =
        if (route == VodPlaybackRoutingPolicy.Route.EXO) {
            PlaybackEngineKind.EXO_PLAYER
        } else {
            PlaybackEngineKind.VLC
        }

    private fun qoeTransport(url: String?): PlaybackTransportKind {
        val path = url.orEmpty().substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") -> PlaybackTransportKind.HLS
            path.endsWith(".mpd") -> PlaybackTransportKind.DASH
            path.endsWith(".ts") -> PlaybackTransportKind.MPEG_TS
            path.isNotBlank() -> PlaybackTransportKind.PROGRESSIVE
            else -> PlaybackTransportKind.UNKNOWN
        }
    }

    /**
     * Run every mutating libVLC command off the UI thread with the same owner
     * reservation/quarantine contract used by prepare and teardown. Vendor JNI can
     * block in seemingly small calls such as setTime(), pause() or setSpuTrack().
     */
    private fun postBoundedOwnerCommand(
        label: String,
        recoverAsPaused: Boolean = false,
        recoverOnFailure: Boolean = true,
        onCompletedMain: (() -> Unit)? = null,
        command: (MediaPlayer) -> Unit,
    ): Boolean {
        val owner = nativeOwner ?: return false
        val ownerOperation = beginOwnerOperation(owner) ?: return false
        VlcOps.postBounded(
            timeoutMs = NATIVE_OPERATION_TIMEOUT_MS,
            onTimeout = {
                if (nativeOwner === owner && !owner.abandoned.get()) {
                    if (recoverAsPaused) pauseAfterBackgroundRestore = true
                    requireOwnerProcessRecovery(
                        owner,
                        markOwnerConnectionUncertain(owner),
                    )
                    quarantineOwner(owner, "$label timed out")
                    if (
                        recoverOnFailure &&
                        foreground &&
                        !isFinishing &&
                        !isDestroyed
                    ) {
                        handlePlaybackError()
                    }
                }
            },
        ) {
            var failure: Throwable? = null
            try {
                if (!owner.abandoned.get()) command(owner.mediaPlayer)
            } catch (error: Throwable) {
                failure = error
                // Close the tiny worker->main hand-off window immediately: no UI
                // command may reserve a native owner whose prior JNI call failed.
                owner.abandoned.set(true)
            } finally {
                finishOwnerOperationOnWorker(ownerOperation)
            }
            handler.post {
                if (
                    nativeOwner !== owner ||
                    isFinishing ||
                    isDestroyed
                ) {
                    return@post
                }
                if (failure == null) {
                    if (owner.abandoned.get()) return@post
                    onCompletedMain?.invoke()
                } else {
                    PlaybackLog.log(
                        this,
                        "VOD",
                        "$label failed: ${failure?.javaClass?.simpleName}",
                    )
                    if (recoverAsPaused) pauseAfterBackgroundRestore = true
                    quarantineOwner(owner, "$label failed")
                    if (recoverOnFailure && foreground) handlePlaybackError()
                }
            }
        }
        return true
    }

    /**
     * Read the frequently-polled libVLC state on the bounded native owner rather
     * than blocking Android's main thread in time/length/isPlaying JNI getters.
     * The snapshot is generation-tagged, so a retired owner can never publish
     * state into its replacement. Track/dialog getters remain user-driven and
     * retain currentIdlePlayer's mutual-exclusion guard.
     */
    private fun refreshVlcPlaybackSnapshot(
        force: Boolean = false,
        onUpdatedMain: (() -> Unit)? = null,
    ) {
        if (isMedia3Route()) return
        val owner = nativeOwner ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastVlcSnapshotRequestAtMs < VLC_SNAPSHOT_INTERVAL_MS) return
        lastVlcSnapshotRequestAtMs = now
        postBoundedOwnerCommand(
            label = "playback snapshot",
            onCompletedMain = onUpdatedMain,
        ) { player ->
            val track = player.currentVideoTrack
            val info = track?.let {
                val den = it.frameRateDen
                val fps = if (den > 0) it.frameRateNum.toFloat() / den else 0f
                StreamInfo(
                    width = it.width,
                    height = it.height,
                    fps = fps,
                    codec = vodCodecLabel(it.codec),
                    bitrateKbps = it.bitrate.takeIf { bitrate -> bitrate > 0 }
                        ?.let { bitrate -> bitrate / 1000 },
                    engine = "VLC",
                )
            }
            val stats = runCatching {
                val activeMedia = player.media
                try {
                    activeMedia?.stats
                } finally {
                    activeMedia?.release()
                }
            }.getOrNull()
            if (!owner.abandoned.get()) {
                vlcPlaybackSnapshot = VlcPlaybackSnapshot(
                    ownerGeneration = owner.generation,
                    positionMs = player.time.coerceAtLeast(0L),
                    durationMs = player.length.coerceAtLeast(0L),
                    playing = player.isPlaying,
                    streamInfo = info,
                    decodedVideo = stats?.decodedVideo,
                    displayedPictures = stats?.displayedPictures,
                    playedAudioBuffers = stats?.playedAbuffers,
                    readBytes = stats?.readBytes,
                )
            }
        }
    }

    private fun setVlcPlayingSnapshot(mp: MediaPlayer, playing: Boolean) {
        val owner = nativeOwner ?: return
        if (mediaPlayer !== mp || owner.mediaPlayer !== mp) return
        val current = currentVlcSnapshot()
        vlcPlaybackSnapshot = VlcPlaybackSnapshot(
            ownerGeneration = owner.generation,
            positionMs = current?.positionMs
                ?: bestResumePosition(null),
            durationMs = current?.durationMs ?: lastKnownDurationMs,
            playing = playing,
            streamInfo = current?.streamInfo,
            decodedVideo = current?.decodedVideo,
            displayedPictures = current?.displayedPictures,
            playedAudioBuffers = current?.playedAudioBuffers,
            readBytes = current?.readBytes,
        )
    }

    /**
     * Native VLC reports time=0 while a deferred seek is still waiting for
     * Playing, and can do the same after an error. Never let that transient
     * native value overwrite the user's resume/recovery target.
     */
    private fun bestResumePosition(playerPositionMs: Long? = activePositionMs()): Long =
        maxOf(
            playerPositionMs ?: 0L,
            pendingSeekMs,
            backgroundResumePosition,
            // JNI getters are deliberately unavailable while a bounded VLC
            // command owns the player. Keep the latest main-thread progress
            // sample so Back/background during a track/seek/stop operation does
            // not overwrite a long-running movie's resume point with zero.
            lastObservedPositionMs.coerceAtLeast(0L),
        ).coerceAtLeast(0L)

    private fun startSurfaceValidation() {
        if (surfaceValidationStarted || !foreground || !playbackStarted) return
        if (PlaybackRemotePolicy.snapshot().disablePixelCopyValidation) return
        surfaceValidationStarted = true
        surfaceFrameHealth.start { findVideoSurface(videoLayout) }
    }

    private fun resetSurfaceValidation() {
        surfaceFrameHealth.reset()
        surfaceValidationStarted = false
        surfaceOutputConfirmed = false
    }

    private fun onSurfaceOutputFailure(detail: String) {
        if (
            !foreground ||
            !playbackStarted ||
            recoveryInProgress ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }
        val snapshot = currentVlcSnapshot()
        val nativeVideoAndInputEvidence =
            decodedVideoSeen &&
                (
                    (snapshot?.decodedVideo ?: 0) > 0 ||
                        (snapshot?.readBytes ?: 0) > 0
                    )
        if (!surfaceOutputConfirmed && nativeVideoAndInputEvidence) {
            // Startup PixelCopy cannot see protected hardware underlays on many
            // TV SoCs. Native decoded/input counters win until PixelCopy has first
            // demonstrated that this surface is actually sampleable and healthy.
            surfaceOutputConfirmed = true
            PlaybackQoeRuntime.markFirstFrame(qoeSessionId)
            PlaybackLog.log(this, "VOD", "$detail ignored before first healthy pixel sample")
            surfaceFrameHealth.reset()
            return
        }
        if (!nativeVideoAndInputEvidence) {
            // PixelCopy can capture the empty SurfaceView layer while a protected
            // hardware underlay is still warming up. Without libVLC vout plus
            // decoded/input counters this is inconclusive and must never fail VOD.
            PlaybackLog.log(this, "VOD", "$detail ignored without native evidence")
            resetSurfaceValidation()
            return
        }
        PlaybackLog.log(this, "VOD", "$detail -> bounded decoder recovery")
        resetSurfaceValidation()
        handler.removeCallbacks(uhdCheckRunnable)
        // A sampled solid colour/blank surface is decoder evidence rather than an
        // ordinary CDN error. Treat the configured decoder as the preferred first
        // path, then allow one bounded opposite-decoder rescue.
        val decision = VodPlaybackHealthPolicy.classify(
            VodPlaybackHealthPolicy.Evidence(
                stalledForMs = 0L,
                inputBuffering = bufferingSinceMs > 0L,
                decodedVideoSeen = decodedVideoSeen,
                display = VodPlaybackHealthPolicy.DisplayEvidence.FAILED,
            ),
            timeoutMs = STALL_TIMEOUT_MS,
        )
        handlePlaybackError(
            if (decision == VodPlaybackHealthPolicy.Decision.DECODER_STALL) {
                RecoveryEvidence.CONFIRMED_VIDEO_FAILURE
            } else {
                RecoveryEvidence.GENERIC
            }
        )
    }

    private fun scheduleUhdRechecks() {
        handler.removeCallbacks(uhdCheckRunnable)
        if (
            !foreground ||
            !playbackStarted ||
            recoveryInProgress ||
            decoderMode != DecoderMode.AUTO ||
            !forceSoftware ||
            uhdEscalated
        ) {
            return
        }
        handler.postDelayed(uhdCheckRunnable, UHD_RECHECK_1_MS)
        handler.postDelayed(uhdCheckRunnable, UHD_RECHECK_2_MS)
        handler.postDelayed(uhdCheckRunnable, UHD_RECHECK_3_MS)
        handler.postDelayed(uhdCheckRunnable, UHD_RECHECK_4_MS)
    }

    private fun beginOwnerOperation(owner: NativePlayerOwner): OwnerOperation? {
        if (owner.abandoned.get()) return null
        val operation = OwnerOperation(owner)
        if (!owner.activeOperation.compareAndSet(null, operation)) return null
        if (owner.abandoned.get()) {
            owner.activeOperation.compareAndSet(operation, null)
            return null
        }
        return operation
    }

    /**
     * Final handshake for the worker that owns a native operation. If main
     * quarantined the owner while JNI was blocked, this same retired worker—not
     * the replacement worker—performs the one-and-only native cleanup.
     */
    private fun finishOwnerOperationOnWorker(operation: OwnerOperation) {
        var cleanOnThisWorker = false
        while (true) {
            when (operation.state.get()) {
                OwnerOperation.STATE_ACTIVE -> {
                    if (
                        operation.state.compareAndSet(
                            OwnerOperation.STATE_ACTIVE,
                            OwnerOperation.STATE_BODY_FINISHED,
                        )
                    ) {
                        break
                    }
                }
                OwnerOperation.STATE_ABANDONED -> {
                    cleanOnThisWorker = true
                    break
                }
                OwnerOperation.STATE_BODY_FINISHED,
                OwnerOperation.STATE_CLEANED -> break
                else -> break
            }
        }
        if (cleanOnThisWorker) {
            cleanupOwnerOnCurrentWorker(operation.owner)
            operation.state.set(OwnerOperation.STATE_CLEANED)
        }
        operation.owner.activeOperation.compareAndSet(operation, null)
    }

    /** Release a reservation that failed before any VlcOps action was dispatched. */
    private fun cancelOwnerOperationBeforeDispatch(operation: OwnerOperation) {
        operation.onAbandoned.set(null)
        operation.state.compareAndSet(
            OwnerOperation.STATE_ACTIVE,
            OwnerOperation.STATE_BODY_FINISHED,
        )
        operation.owner.activeOperation.compareAndSet(operation, null)
    }

    /** Native cleanup after ownership has been exclusively claimed by VlcOps. */
    private fun cleanupOwnerOnCurrentWorker(owner: NativePlayerOwner): VlcNativeCleanupResult {
        if (!owner.cleanupClaimed.compareAndSet(false, true)) {
            return VlcNativeCleanupResult.notClaimed()
        }
        val cleanupUncertaintyToken = markOwnerConnectionUncertain(owner)
        val result = VlcNativeCleanup.runFull(
            detachListener = { owner.mediaPlayer.setEventListener(null) },
            stop = { owner.mediaPlayer.stop() },
            releaseMediaPlayer = { owner.mediaPlayer.release() },
            releaseLibVlc = { owner.libVlc.release() },
        )
        result.failures.forEach { failure ->
            PlaybackLog.log(
                this,
                "VOD",
                "owner=${owner.generation} cleanup failed: " +
                    failure.javaClass.simpleName,
            )
        }
        removeOwnerLayoutOnMain(owner)
        if (result.ownershipDefinitivelyReleased) {
            // Publish proof before clearing the exact token. A timeout callback
            // that arrives just after cleanup must not quarantine this retired
            // owner and mint a brand-new token with no remaining cleanup path.
            owner.ownershipDefinitivelyReleased.set(true)
            resolveOwnerConnectionUncertainty(owner)
        } else {
            requireOwnerProcessRecovery(owner, cleanupUncertaintyToken)
        }
        return result
    }

    private fun markOwnerConnectionUncertain(owner: NativePlayerOwner): Long {
        while (true) {
            if (owner.ownershipDefinitivelyReleased.get()) return 0L
            val existing = owner.providerUncertaintyToken.get()
            if (existing != 0L) return existing
            val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
            if (owner.ownershipDefinitivelyReleased.get()) {
                ProviderConnectionSafety.resolveDefinitiveLocalStop(token)
                return 0L
            }
            if (owner.providerUncertaintyToken.compareAndSet(0L, token)) {
                // Cleanup may publish proof immediately after the pre-CAS check.
                if (
                    owner.ownershipDefinitivelyReleased.get() &&
                    owner.providerUncertaintyToken.compareAndSet(token, 0L)
                ) {
                    ProviderConnectionSafety.resolveDefinitiveLocalStop(token)
                    return 0L
                }
                return token
            }
            ProviderConnectionSafety.resolveDefinitiveLocalStop(token)
        }
    }

    private fun resolveOwnerConnectionUncertainty(owner: NativePlayerOwner) {
        val token = owner.providerUncertaintyToken.getAndSet(0L)
        if (token != 0L) ProviderConnectionSafety.resolveDefinitiveLocalStop(token)
    }

    private fun requireOwnerProcessRecovery(
        owner: NativePlayerOwner,
        token: Long = owner.providerUncertaintyToken.get(),
    ) {
        if (token != 0L) {
            ProviderConnectionSafety.requireLocalProcessRecovery(token)
        } else if (!owner.ownershipDefinitivelyReleased.get()) {
            ProviderConnectionSafety.block(
                ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP,
            )
        }
    }

    /**
     * Removing an Android View is main-thread-only. Native listener/stop/release
     * teardown remains exclusively on the owner worker.
     */
    private fun removeOwnerLayoutOnMain(owner: NativePlayerOwner) {
        val remove = {
            (owner.videoLayout.parent as? ViewGroup)?.removeView(owner.videoLayout)
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove()
        } else {
            handler.post { remove() }
        }
    }

    private fun clearCurrentOwner(owner: NativePlayerOwner) {
        if (nativeOwner !== owner) return
        nativeOwner = null
        if (mediaPlayer === owner.mediaPlayer) mediaPlayer = null
        if (libVlc === owner.libVlc) libVlc = null
        if (videoLayout === owner.videoLayout) videoLayout = null
        if (vlcPlaybackSnapshot?.ownerGeneration == owner.generation) {
            vlcPlaybackSnapshot = null
        }
    }

    /**
     * Quarantine never calls a native method. It only invalidates callbacks,
     * removes the owner from all current fields, and hands cleanup ownership to
     * the already-running retired worker. A replacement can then be built without
     * ever touching the timed-out MediaPlayer/LibVLC handles.
     */
    private fun quarantineOwner(owner: NativePlayerOwner, reason: String) {
        // Quarantine means native ownership is no longer synchronously provable.
        // Register the exact owner before clearing current fields so every caller
        // (including future recovery paths) preserves the one-connection gate.
        var quarantineToken = 0L
        if (!owner.ownershipDefinitivelyReleased.get()) {
            quarantineToken = markOwnerConnectionUncertain(owner)
        }
        // Close the false->mark race against cleanup publishing definitive proof.
        if (
            quarantineToken != 0L &&
            owner.ownershipDefinitivelyReleased.get() &&
            owner.providerUncertaintyToken.compareAndSet(quarantineToken, 0L)
        ) {
            ProviderConnectionSafety.resolveDefinitiveLocalStop(quarantineToken)
        }
        val wasCurrent = nativeOwner === owner
        if (wasCurrent) invalidateEventSession()
        owner.abandoned.set(true)
        clearCurrentOwner(owner)
        // Do not let an abandoned SurfaceView cover the replacement owner. This
        // is the only teardown performed here; every native call remains with the
        // old owner worker.
        removeOwnerLayoutOnMain(owner)
        PlaybackLog.log(this, "VOD", "owner=${owner.generation} quarantined: $reason")

        val active = owner.activeOperation.get()
        active?.onAbandoned?.getAndSet(null)?.invoke()
        val needsSafeCleanup = when {
            active == null -> true
            active.state.compareAndSet(
                OwnerOperation.STATE_ACTIVE,
                OwnerOperation.STATE_ABANDONED,
            ) -> false
            active.state.get() == OwnerOperation.STATE_BODY_FINISHED -> true
            else -> false
        }
        if (needsSafeCleanup && !owner.ownershipDefinitivelyReleased.get()) {
            scheduleQuarantinedOwnerCleanup(owner)
        }
    }

    /**
     * Used only when the previous operation has already finished its native body;
     * therefore the cleanup worker cannot overlap native access to this owner.
     */
    private fun scheduleQuarantinedOwnerCleanup(owner: NativePlayerOwner) {
        if (
            owner.cleanupClaimed.get() ||
            !owner.cleanupScheduled.compareAndSet(false, true)
        ) {
            return
        }
        val cleanupUncertaintyToken = markOwnerConnectionUncertain(owner)
        VlcOps.postBounded(
            timeoutMs = NATIVE_RELEASE_TIMEOUT_MS,
            onTimeout = {
                requireOwnerProcessRecovery(owner, cleanupUncertaintyToken)
                PlaybackLog.log(
                    this,
                    "VOD",
                    "owner=${owner.generation} quarantined cleanup timed out",
                )
            },
        ) {
            cleanupOwnerOnCurrentWorker(owner)
        }
    }

    /**
     * Reserve an idle owner for planned rebuild/destroy cleanup. IVLCVout requires
     * detachViews() on main, but the reservation proves no owner-worker operation
     * can touch this handle concurrently. Active/timed-out owners never enter this
     * path: removing their layout destroys the Surface (IVLCVout performs its
     * automatic detach) and their retired worker performs listener/stop/release.
     */
    private fun retireIdleOwnerOnMain(owner: NativePlayerOwner): OwnerOperation? {
        val operation = beginOwnerOperation(owner) ?: return null
        invalidateEventSession()
        owner.abandoned.set(true)
        runCatching { owner.mediaPlayer.detachViews() }.onFailure { failure ->
            PlaybackLog.log(
                this,
                "VOD",
                "owner=${owner.generation} main-thread detach failed: " +
                    failure.javaClass.simpleName,
            )
        }
        removeOwnerLayoutOnMain(owner)
        clearCurrentOwner(owner)
        return operation
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        resumeId = intent.getStringExtra(EXTRA_RESUME_ID)
        processRecoveryPositionMs = intent.getLongExtra(EXTRA_RECOVERY_POSITION_MS, 0L)
            .coerceAtLeast(0L)
        autoResume =
            intent.getBooleanExtra(EXTRA_AUTO_RESUME, false) ||
                intent.getBooleanExtra(
                    PlaybackProcessRecovery.EXTRA_RECOVERED_PLAYBACK,
                    false,
                )
        binding.titleText.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        if (streamUrl.isNullOrBlank()) {
            finish()
            return
        }

        // Report to the live-device ops panel what's playing (cleared in onStop).
        nowKind = when (ResumeKind.fromRaw(intent.getStringExtra(EXTRA_RESUME_TYPE))) {
            ResumeKind.MOVIE -> "Film"
            ResumeKind.EPISODE -> "Dizi"
            ResumeKind.CATCHUP -> "Geri Sar"
        }
        intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let {
            NowPlaying.set(this, it, nowKind)
        }

        // Build the metadata persisted alongside the resume position. Falls back to
        // the player title when no explicit resume title is supplied (movies).
        resumeId?.let { id ->
            resumeMeta = ResumeMeta(
                contentId = id,
                kind = ResumeKind.fromRaw(intent.getStringExtra(EXTRA_RESUME_TYPE)),
                title = intent.getStringExtra(EXTRA_RESUME_TITLE)
                    ?: intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                posterUrl = intent.getStringExtra(EXTRA_POSTER_URL),
                streamUrl = streamUrl.orEmpty(),
                vodId = intent.getStringExtra(EXTRA_VOD_ID),
                seriesId = intent.getStringExtra(EXTRA_SERIES_ID),
                seasonNumber = intent.getIntExtra(EXTRA_SEASON, 0),
                episodeNumber = intent.getIntExtra(EXTRA_EPISODE, 0)
            )
        }

        setupControls()
        initPlayer()
        // Controls start visible; auto-hide them after a few idle seconds.
        scheduleHideControls()

        // On-screen Debug overlay (engine/stage + live PlaybackLog tail), gated by
        // the Debug setting. VOD uses a raw libVLC MediaPlayer, so build StreamInfo
        // from its current video track rather than a PlayerController.
        debugBinder = DebugOverlayBinder(binding.debugOverlay) { vodStreamInfo() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    settings.debugOverlay.collectLatest { debugBinder?.setEnabled(it) }
                } finally {
                    // Leaving STARTED: drop the listener + timer so a backgrounded
                    // VOD player doesn't keep rendering the overlay off-screen.
                    debugBinder?.setEnabled(false)
                }
            }
        }
    }

    /** Build StreamInfo from the raw libVLC track (mirrors VlcPlayerEngine). */
    private fun vodStreamInfo(): StreamInfo? {
        if (isMedia3Route()) {
            val info = media3VodEngine?.streamInfo() ?: return null
            return StreamInfo(
                width = info.width,
                height = info.height,
                fps = info.frameRate,
                codec = info.videoCodecs ?: info.videoMimeType,
                bitrateKbps = info.bitrateKbps,
                engine = info.engine,
            )
        }
        return currentVlcSnapshot()?.streamInfo
    }

    private fun vodCodecLabel(codec: Any?): String? = when (codec) {
        is String -> codec.trim().takeIf { it.isNotBlank() }
        is Int -> {
            if (codec == 0) null
            else String(CharArray(4) { i -> ((codec shr (i * 8)) and 0xFF).toChar() })
                .trim()
                .takeIf { it.isNotBlank() }
                ?.uppercase()
        }
        else -> null
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Any touch keeps the controls up and resets the idle timer.
        showControls()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isSystemKey = event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

        // Consume both DOWN and UP. Passing only the UP event to Activity can
        // still finish the screen on vendor TV key dispatchers.
        if (
            binding.nextEpisodeOverlay.visibility == View.VISIBLE &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                cancelNextEpisodePrompt()
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (binding.nextEpisodeOverlay.visibility == View.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        if (event.repeatCount == 0) playPendingNextEpisode()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> return true
                }
            }
            if (binding.errorOverlay.visibility == View.VISIBLE) {
                // Terminal playback state has exactly two exits: Retry or Back.
                // Map hardware play keys to Retry and swallow transport keys so a
                // broken native player cannot be controlled behind the modal card.
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        if (event.repeatCount == 0) retryFromConfiguredDecoder()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> return true
                }
            }

            // Dedicated media keys must work even while the visual controls are
            // hidden. This matters on full-size TV remotes and Bluetooth remotes.
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    showControls()
                    // Long-press repeats must not alternate play/pause rapidly.
                    if (event.repeatCount == 0) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    showControls()
                    if (!activeIsPlaying()) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    showControls()
                    if (activeIsPlaying()) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    showControls()
                    seekBy(SKIP_MS)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    showControls()
                    seekBy(-SKIP_MS)
                    return true
                }
            }

            if (!isSystemKey && !controlsVisible) {
                // Left/right are natural 10-second seek shortcuts on TV. Other
                // keys reveal the overlay without accidentally activating the
                // previously-focused (currently invisible) control.
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-SKIP_MS)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(SKIP_MS)
                }
                showControls()
                return true
            }
        }
        if (!isSystemKey) showControls()
        return super.dispatchKeyEvent(event)
    }

    private fun setupControls() {
        binding.backButton.setOnClickListener { finish() }
        binding.retryButton.setOnClickListener { retryFromConfiguredDecoder() }
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.skipForwardButton.setOnClickListener { seekBy(SKIP_MS) }
        binding.skipBackButton.setOnClickListener { seekBy(-SKIP_MS) }
        binding.audioButton.setOnClickListener { showTrackMenu(isAudio = true) }
        binding.subtitleButton.setOnClickListener { showTrackMenu(isAudio = false) }
        binding.aspectButton.setOnClickListener { cycleAspect() }
        binding.sleepButton.setOnClickListener { showSleepDialog() }
        binding.castButton.setOnClickListener { castController.onCastButtonClicked() }
        binding.nextEpisodePlayNow.setOnClickListener { playPendingNextEpisode() }
        binding.nextEpisodeCancel.setOnClickListener { cancelNextEpisodePrompt() }
        castController.attach()

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentTime.text = formatTime(progress.toLong())
                    // D-pad changes do not invoke onStart/StopTrackingTouch, so
                    // apply those key-driven positions immediately.
                    if (!userSeeking) {
                        val position = progress.toLong()
                        requestSeek(position)
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val position = sb?.progress?.toLong() ?: 0L
                requestSeek(position)
                scheduleHideControls()
            }
        })
        // Android's default SeekBar D-pad step is duration/20, which can jump
        // several minutes in a movie. Keep remote seeking deterministic.
        binding.seekBar.keyProgressIncrement = SKIP_MS.toInt()
        binding.playPauseButton.post { binding.playPauseButton.requestFocus() }
        updateTrackButtons()
    }

    private fun setBuffering(visible: Boolean) {
        val wasBuffering = bufferingSinceMs > 0L
        binding.bufferingOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        PlaybackQoeRuntime.setRebuffering(qoeSessionId, visible)
        if (visible && playbackStarted) {
            handler.removeCallbacks(stablePlaybackRunnable)
        } else if (!visible && playbackStarted && activeIsPlaying()) {
            handler.removeCallbacks(stablePlaybackRunnable)
            handler.postDelayed(stablePlaybackRunnable, STABLE_PLAYBACK_MS)
        }
        if (visible && playbackStarted && bufferingSinceMs == 0L) {
            bufferingSinceMs = SystemClock.uptimeMillis()
        } else if (!visible) {
            bufferingSinceMs = 0L
            if (wasBuffering && isMedia3Route()) {
                resetVideoLivenessWatch(activePositionMs())
            }
        }
    }

    private fun resetStallWatch(positionMs: Long = activePositionMs()) {
        lastObservedPositionMs = positionMs
        lastPositionAdvanceAtMs = SystemClock.uptimeMillis()
        bufferingSinceMs = 0L
        resetVideoLivenessWatch(positionMs)
    }

    private fun resetVideoLivenessWatch(positionMs: Long) {
        val media3 = isMedia3Route()
        videoLivenessGeneration = if (media3) {
            media3Generation
        } else {
            VodEngine.NO_GENERATION
        }
        videoLivenessWatchStartedAtMs = SystemClock.elapsedRealtime()
        videoLivenessStartPositionMs = positionMs.coerceAtLeast(0L)
        vlcVideoLivenessOwnerGeneration = if (media3) {
            -1L
        } else {
            nativeOwner?.generation ?: -1L
        }
        vlcVideoLivenessState = VlcVideoLivenessPolicy.State()
    }

    private fun detectVideoLiveness(positionMs: Long) {
        if (!isMedia3Route()) {
            detectVlcVideoLiveness(positionMs)
            return
        }
        val engine = media3VodEngine ?: return
        val sample = engine.videoLiveness() ?: return
        if (
            sample.generation != media3Generation ||
            media3CoordinatorGeneration != coordinatorGeneration
        ) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (
            videoLivenessGeneration != sample.generation ||
            videoLivenessWatchStartedAtMs <= 0L
        ) {
            videoLivenessGeneration = sample.generation
            videoLivenessWatchStartedAtMs = now
            videoLivenessStartPositionMs = positionMs.coerceAtLeast(0L)
            return
        }
        val decision = VodVideoLivenessPolicy.classify(
            evidence = VodVideoLivenessPolicy.Evidence(
                playbackActive = activeIsPlaying(),
                inputBuffering = bufferingSinceMs > 0L,
                verifiedVideo = playbackStarted,
                mediaClockAdvanceMs =
                    (positionMs - videoLivenessStartPositionMs).coerceAtLeast(0L),
                lastFrameAgeMs =
                    (now - sample.lastFrameRealtimeMs).coerceAtLeast(0L),
                graceElapsedMs =
                    (now - videoLivenessWatchStartedAtMs).coerceAtLeast(0L),
            ),
            frameTimeoutMs = VIDEO_FRAME_STALL_TIMEOUT_MS,
            minimumClockAdvanceMs = VIDEO_CLOCK_EVIDENCE_MS,
        )
        if (decision == VodVideoLivenessPolicy.Decision.VIDEO_STALL) {
            PlaybackLog.log(
                this,
                "VOD",
                "renderer heartbeat stalled while media clock advanced " +
                    "frames=${sample.frameSequence}",
            )
            // Reset before dispatch so a rejected/stale failure cannot spin every
            // 500 ms; the coordinator still owns the bounded route ladder.
            resetVideoLivenessWatch(positionMs)
            handlePlaybackError(RecoveryEvidence.CONFIRMED_VIDEO_FAILURE)
        }
    }

    private fun detectVlcVideoLiveness(positionMs: Long) {
        val owner = nativeOwner ?: return
        val snapshot = currentVlcSnapshot() ?: return
        if (vlcVideoLivenessOwnerGeneration != owner.generation) {
            vlcVideoLivenessOwnerGeneration = owner.generation
            vlcVideoLivenessState = VlcVideoLivenessPolicy.State()
        }
        val result = VlcVideoLivenessPolicy.reduce(
            previous = vlcVideoLivenessState,
            sample = VlcVideoLivenessPolicy.Sample(
                nowMs = SystemClock.elapsedRealtime(),
                playbackActive = snapshot.playing,
                inputBuffering = bufferingSinceMs > 0L,
                verifiedVideo = playbackStarted &&
                    decodedVideoSeen &&
                    (snapshot.displayedPictures ?: 0) > 0,
                positionMs = positionMs,
                decodedVideo = snapshot.decodedVideo,
                displayedPictures = snapshot.displayedPictures,
                playedAudioBuffers = snapshot.playedAudioBuffers,
                readBytes = snapshot.readBytes,
                expectedVideoFps = snapshot.streamInfo?.fps,
            ),
            frameTimeoutMs = VIDEO_FRAME_STALL_TIMEOUT_MS,
            minimumClockAdvanceMs = VIDEO_CLOCK_EVIDENCE_MS,
        )
        vlcVideoLivenessState = result.state
        if (result.decision == VlcVideoLivenessPolicy.Decision.VIDEO_STALL) {
            PlaybackLog.log(
                this,
                "VOD",
                "VLC video counters stalled while audio/input advanced " +
                    "decoded=${snapshot.decodedVideo} " +
                    "displayed=${snapshot.displayedPictures}",
            )
            vlcVideoLivenessState = VlcVideoLivenessPolicy.State()
            handlePlaybackError(RecoveryEvidence.CONFIRMED_VIDEO_FAILURE)
        }
    }

    private fun detectPlaybackStall(positionMs: Long) {
        if (!foreground || !playbackStarted || userSeeking || recoveryInProgress) return
        detectVideoLiveness(positionMs)
        if (recoveryInProgress) return
        val now = SystemClock.uptimeMillis()
        if (
            lastObservedPositionMs < 0L ||
            kotlin.math.abs(positionMs - lastObservedPositionMs) >= 250L
        ) {
            lastObservedPositionMs = positionMs
            lastPositionAdvanceAtMs = now
            return
        }
        val expectedToAdvance = activeIsPlaying() || bufferingSinceMs > 0L
        if (!expectedToAdvance || lastPositionAdvanceAtMs <= 0L) return
        val stalledForMs = now - lastPositionAdvanceAtMs
        val decision = VodPlaybackHealthPolicy.classify(
            VodPlaybackHealthPolicy.Evidence(
                stalledForMs = stalledForMs,
                inputBuffering = bufferingSinceMs > 0L,
                decodedVideoSeen = decodedVideoSeen,
                display = if (surfaceOutputConfirmed) {
                    VodPlaybackHealthPolicy.DisplayEvidence.HEALTHY
                } else {
                    VodPlaybackHealthPolicy.DisplayEvidence.UNKNOWN
                },
            ),
            timeoutMs = STALL_TIMEOUT_MS,
        )
        when (decision) {
            VodPlaybackHealthPolicy.Decision.WAIT -> Unit
            VodPlaybackHealthPolicy.Decision.SOURCE_STALL -> {
                PlaybackLog.log(this, "VOD", "source/input playback stall")
                handlePlaybackError(RecoveryEvidence.GENERIC)
            }
            VodPlaybackHealthPolicy.Decision.DECODER_STALL -> {
                PlaybackLog.log(this, "VOD", "decoded/display playback stall")
                handlePlaybackError(RecoveryEvidence.CONFIRMED_VIDEO_FAILURE)
            }
        }
    }

    private fun showTerminalError(
        messageRes: Int = R.string.error_cannot_play_content,
        supportCode: String? = null,
    ) {
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(uhdCheckRunnable)
        resetSurfaceValidation()
        setBuffering(false)
        binding.errorMessage.text = if (supportCode == null) {
            getString(messageRes)
        } else {
            getString(
                R.string.vod_error_message_with_support_code,
                getString(messageRes),
                supportCode,
            )
        }
        binding.errorOverlay.visibility = View.VISIBLE
        // The overlay is declared before the chrome so normal playback controls
        // can render efficiently. Terminal state must become the topmost focus
        // barrier; Back remains a system key and still exits normally.
        binding.errorOverlay.bringToFront()
        setPlayerChromeFocusable(false)
        setControlsVisible(true)
        binding.retryButton.requestFocus()
    }

    private fun showTerminalError(failure: PlaybackFailure) {
        val customerError = VodPlaybackRoutingPolicy.customerError(failure)
        val messageRes = when (customerError.message) {
            VodPlaybackRoutingPolicy.CustomerMessage.AUTHORIZATION ->
                R.string.vod_error_authorization
            VodPlaybackRoutingPolicy.CustomerMessage.ACCESS_DENIED ->
                R.string.vod_error_access_denied
            VodPlaybackRoutingPolicy.CustomerMessage.CONTENT_UNAVAILABLE ->
                R.string.vod_error_content_unavailable
            VodPlaybackRoutingPolicy.CustomerMessage.RANGE_REJECTED ->
                R.string.vod_error_range_rejected
            VodPlaybackRoutingPolicy.CustomerMessage.RATE_LIMITED ->
                R.string.vod_error_rate_limited
            VodPlaybackRoutingPolicy.CustomerMessage.SERVER_UNAVAILABLE ->
                R.string.vod_error_server_unavailable
            VodPlaybackRoutingPolicy.CustomerMessage.TIMEOUT ->
                R.string.vod_error_timeout
            VodPlaybackRoutingPolicy.CustomerMessage.TLS ->
                R.string.vod_error_tls
            VodPlaybackRoutingPolicy.CustomerMessage.DNS ->
                R.string.vod_error_dns
            VodPlaybackRoutingPolicy.CustomerMessage.DECODER ->
                R.string.vod_error_decoder
            VodPlaybackRoutingPolicy.CustomerMessage.VIDEO_OUTPUT ->
                R.string.vod_error_video_output
            VodPlaybackRoutingPolicy.CustomerMessage.SOURCE ->
                R.string.vod_error_source
            VodPlaybackRoutingPolicy.CustomerMessage.GENERIC ->
                R.string.error_cannot_play_content
        }
        showTerminalError(messageRes, customerError.supportCode)
    }

    private fun setPlayerChromeFocusable(enabled: Boolean) {
        playerChromeFocusable = enabled
        val alwaysAvailable = arrayOf(
            binding.backButton,
            binding.playPauseButton,
            binding.skipBackButton,
            binding.skipForwardButton,
            binding.aspectButton,
            binding.sleepButton,
            binding.castButton,
            binding.seekBar,
        )
        alwaysAvailable.forEach { it.isFocusable = enabled }
        if (enabled) {
            updateTrackButtons()
        } else {
            binding.audioButton.isFocusable = false
            binding.subtitleButton.isFocusable = false
        }
    }

    private fun requestVodProcessRecovery(reason: String): Boolean {
        if (!playbackEnded) persistResume(durable = true)
        return PlaybackProcessRecovery.requestIfRequired(
            activity = this,
            reason = reason,
            resumeIntent = playbackProcessRecoveryIntent(),
        )
    }

    /** Build from current fields, not the original Intent (auto-next may replace them). */
    override fun playbackProcessRecoveryIntent(): Intent {
        val meta = resumeMeta
        val recoveryPosition = bestResumePosition(null)
        return Intent(this, VodPlayerActivity::class.java).apply {
            putExtra(EXTRA_STREAM_URL, streamUrl)
            putExtra(EXTRA_TITLE, binding.titleText.text?.toString().orEmpty())
            putExtra(EXTRA_RESUME_ID, resumeId)
            putExtra(EXTRA_RESUME_TITLE, meta?.title)
            putExtra(EXTRA_RESUME_TYPE, meta?.kind?.raw)
            putExtra(EXTRA_POSTER_URL, meta?.posterUrl)
            putExtra(EXTRA_VOD_ID, meta?.vodId)
            putExtra(EXTRA_SERIES_ID, meta?.seriesId)
            putExtra(EXTRA_SEASON, meta?.seasonNumber ?: 0)
            putExtra(EXTRA_EPISODE, meta?.episodeNumber ?: 0)
            putExtra(EXTRA_AUTO_RESUME, true)
            putExtra(EXTRA_RECOVERY_POSITION_MS, recoveryPosition)
        }
    }

    /**
     * Wait for an ordinary prior-owner cleanup. If the shared queue drains while
     * its exact token remains, only a controlled process recycle can prove every
     * native socket closed.
     */
    private fun handleBlockedProviderStart(
        reason: String,
        afterDrain: () -> Unit,
    ): Boolean {
        val safety = ProviderConnectionSafety.snapshot()
        if (safety.newConnectionAllowed) return false
        if (safety.canRecoverLocalProcess) {
            if (!requestVodProcessRecovery(reason)) {
                showTerminalError(R.string.error_playback_ownership_uncertain)
            }
            return true
        }
        if (!safety.remoteUncertain && safety.localPendingCount > 0) {
            val request = providerStartSeq.incrementAndGet()
            setBuffering(true)
            VlcOps.post {
                handler.post providerDrained@{
                    if (
                        providerStartSeq.get() != request ||
                        !foreground ||
                        isFinishing ||
                        isDestroyed
                    ) {
                        return@providerDrained
                    }
                    var after = ProviderConnectionSafety.snapshot()
                    if (
                        !after.remoteUncertain &&
                        after.localPendingCount > 0 &&
                        !after.localProcessRecoveryRequired
                    ) {
                        ProviderConnectionSafety.requireProcessRecoveryForPendingLocalStops()
                        after = ProviderConnectionSafety.snapshot()
                    }
                    when {
                        after.newConnectionAllowed -> afterDrain()
                        after.canRecoverLocalProcess -> {
                            if (!requestVodProcessRecovery("${reason}_after_drain")) {
                                setBuffering(false)
                                showTerminalError(R.string.error_playback_ownership_uncertain)
                            }
                        }
                        else -> {
                            setBuffering(false)
                            showTerminalError(R.string.error_playback_ownership_uncertain)
                        }
                    }
                }
            }
            return true
        }
        showTerminalError(R.string.error_playback_ownership_uncertain)
        return true
    }

    /** Retry is an explicit fresh attempt, so return to the configured decoder. */
    private fun retryFromConfiguredDecoder() {
        if (
            handleBlockedProviderStart(
                reason = "vod_retry_unresolved_native_owner",
                afterDrain = ::retryFromConfiguredDecoder,
            )
        ) {
            return
        }
        // HTTP 416 means the provider rejected the requested byte/time range.
        // A user retry should clear the stale resume offset instead of repeating
        // the exact same invalid Range request forever.
        val resumeAt = if (vodCoordinator.state.terminalFailure?.httpStatus == 416) {
            0L
        } else {
            bestResumePosition()
        }
        binding.errorOverlay.visibility = View.GONE
        setPlayerChromeFocusable(true)
        binding.playPauseButton.requestFocus()
        setBuffering(true)
        playbackErrorAttempts = 0
        softwareFallbackAttempted = false
        crossDecoderRescueAttempted = false
        recoveryInProgress = false
        uhdEscalated = false
        val hadEngine = nativeOwner != null || media3VodEngine != null
        resetRoutingForCurrentItem()
        val configuredRoute = activeVodRoute
        if (!hadEngine) {
            try {
                buildActivePlayer()
                preparePlayback(resumeAt)
            } catch (error: Throwable) {
                PlaybackLog.log(this, "VOD", "retry init failed: ${error.javaClass.simpleName}")
                showTerminalError()
            }
        } else {
            // A terminal backend can be poisoned. Rebuilding is slower than bare
            // play(), but restores the configured engine/decoder deterministically.
            rebuildRoute(resumeAt = resumeAt, targetRoute = configuredRoute)
        }
    }

    private fun updateTrackButtons() {
        val engine = media3VodEngine.takeIf { isMedia3Route() }
        val mp = currentIdlePlayer().takeUnless { isMedia3Route() }
        val hasAudio = if (engine != null) {
            engine.audioTracks().any(VodTrack::supported)
        } else {
            mp?.audioTracks?.any { it.id != -1 } == true
        }
        val hasSubtitles = if (engine != null) {
            engine.subtitleTracks().any(VodTrack::supported)
        } else {
            mp?.spuTracks?.any { it.id != -1 } == true
        }
        binding.audioButton.isEnabled = hasAudio
        binding.subtitleButton.isEnabled = hasSubtitles
        binding.audioButton.isFocusable = playerChromeFocusable && hasAudio
        binding.subtitleButton.isFocusable = playerChromeFocusable && hasSubtitles
        binding.audioButton.alpha = if (hasAudio) 1f else 0.42f
        binding.subtitleButton.alpha = if (hasSubtitles) 1f else 0.42f

        // Explicit XML targets do not automatically skip a disabled view on all
        // Android TV builds. Rebuild the horizontal focus chain around whichever
        // track buttons are currently available.
        val actions = buildList<View> {
            if (hasAudio) add(binding.audioButton)
            if (hasSubtitles) add(binding.subtitleButton)
            add(binding.aspectButton)
            add(binding.sleepButton)
            add(binding.castButton)
        }
        val first = actions.first()
        binding.backButton.nextFocusRightId = first.id
        actions.forEachIndexed { index, view ->
            val previous = actions.getOrNull(index - 1) ?: binding.backButton
            val next = actions.getOrNull(index + 1)
            view.nextFocusLeftId = previous.id
            view.nextFocusRightId = next?.id ?: View.NO_ID
        }
        binding.playPauseButton.nextFocusUpId = actions[actions.size / 2].id
    }

    // ---- Controls auto-hide --------------------------------------------

    /** Reveal the controls (if hidden) and (re)start the idle auto-hide timer. */
    private fun showControls() {
        if (binding.nextEpisodeOverlay.visibility == View.VISIBLE) return
        if (!controlsVisible) setControlsVisible(true)
        scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (
            activeIsPlaying() &&
            !userSeeking &&
            activeDialog?.isShowing != true &&
            binding.errorOverlay.visibility != View.VISIBLE
        ) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        if (
            !visible &&
            (!activeIsPlaying() ||
                userSeeking ||
                activeDialog?.isShowing == true ||
                binding.errorOverlay.visibility == View.VISIBLE)
        ) {
            return
        }
        controlsVisible = visible
        val bars = arrayOf(binding.topBar, binding.transportBar, binding.bottomBar)
        for (bar in bars) {
            bar.animate().cancel()
            if (visible) {
                bar.visibility = View.VISIBLE
                bar.animate().alpha(1f).setDuration(200L).start()
            } else {
                bar.animate().alpha(0f).setDuration(200L).withEndAction {
                    // Guard against a re-show that happened mid-animation.
                    if (!controlsVisible) bar.visibility = View.GONE
                }.start()
            }
        }
        // When revealing, move focus back onto a control so the D-pad has a target.
        if (visible && binding.errorOverlay.visibility != View.VISIBLE) {
            if (binding.nextEpisodeOverlay.visibility != View.VISIBLE) {
                binding.playPauseButton.requestFocus()
            }
        }
    }

    private fun initPlayer() {
        // Read the player + decoder pair atomically. VOD now follows the same user
        // selection as Live TV: Media3 first for modern containers, VLC for broad
        // legacy/DTS compatibility, with a bounded evidence-based fallback ladder.
        lifecycleScope.launch {
            try {
                val selection = settings.getPlaybackSelection()
                playerMode = selection.player
                decoderMode = selection.decoder
                bufferMode = settings.getBufferMode()
                allowPassthrough = settings.getAudioPassthrough()
                preferredAudioTrack = settings.getPreferredAudioTrack()
                preferredSubtitleTrack = settings.getPreferredSubtitleTrack()
                activeProfileId = settings.getActiveProfileId()
                aspect = settings.aspectRatio.first()
                resetRoutingForCurrentItem()
                val saved = resumeId?.let {
                    repo.getResumeForProfile(activeProfileId, it)
                } ?: 0L
                val resumeTarget = maxOf(saved, processRecoveryPositionMs)
                when {
                    resumeTarget <= 0L -> preparePlayback(0L)
                    // From the Continue Watching rail the user already chose to resume.
                    autoResume -> preparePlayback(resumeTarget)
                    else -> promptResume(resumeTarget)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                PlaybackLog.log(this@VodPlayerActivity, "VOD", "player init failed: ${error.javaClass.simpleName}")
                showTerminalError()
            }
        }
    }

    private fun resetRoutingForCurrentItem() {
        val url = streamUrl.orEmpty()
        val remotePolicy = PlaybackRemotePolicy.snapshot()
        vodRouteKey = VodRouteKey.create(
            activeProfileId = activeProfileId,
            contentId = resumeId,
            streamUrl = url,
            playerMode = playerMode,
            decoderMode = decoderMode,
        )
        val rememberedRoute = PlaybackRouteMemory.bestStage(vodRouteKey)
            ?.let { name ->
                runCatching { VodPlaybackRoutingPolicy.Route.valueOf(name) }.getOrNull()
            }
        val actions = vodCoordinator.dispatch(
            VodPlaybackCoordinator.Event.Replace(
                VodPlaybackCoordinator.Selection(
                    playerMode = playerMode,
                    decoderMode = decoderMode,
                    contentHint = if (VodPlaybackRoutingPolicy.isMedia3Preferred(url)) {
                        VodPlaybackRoutingPolicy.ContentHint.MEDIA3_PREFERRED
                    } else {
                        VodPlaybackRoutingPolicy.ContentHint.VLC_COMPATIBILITY
                    },
                    rememberedRoute = rememberedRoute,
                    allowSourceEngineFallback = remotePolicy.allowSourceEngineFallback,
                ),
            ),
        )
        val start = actions.filterIsInstance<VodPlaybackCoordinator.Action.Start>().lastOrNull()
            ?: error("VOD coordinator did not start a replacement item")
        coordinatorGeneration = start.generation
        activeVodRoute = start.route
        rememberedInitialRoute = rememberedRoute == start.route
        activeRouteStable = false
        forceSoftware = activeVodRoute == VodPlaybackRoutingPolicy.Route.VLC_SOFTWARE
    }

    private fun buildActivePlayer() {
        when (activeVodRoute) {
            VodPlaybackRoutingPolicy.Route.EXO -> buildMedia3Player()
            VodPlaybackRoutingPolicy.Route.VLC_HARDWARE,
            VodPlaybackRoutingPolicy.Route.VLC_SOFTWARE -> {
                forceSoftware = activeVodRoute == VodPlaybackRoutingPolicy.Route.VLC_SOFTWARE
                buildPlayer()
            }
        }
    }

    private fun buildMedia3Player() {
        check(nativeOwner == null) { "Cannot build Media3 over an active VLC owner" }
        media3VodEngine?.release()
        val remotePolicy = PlaybackRemotePolicy.snapshot()
        val engine = Media3VodEngine(
            context = this,
            config = Media3VodEngineConfig(
                buffer = VodBufferConfig(
                    minBufferMs = bufferMode.exoMinBufferMs,
                    maxBufferMs = bufferMode.exoMaxBufferMs,
                    playbackBufferMs = bufferMode.exoPlaybackMs,
                    rebufferMs = bufferMode.exoRebufferMs,
                ),
                connectTimeoutMs = remotePolicy.vodConnectTimeoutMs,
                readTimeoutMs = remotePolicy.vodReadTimeoutMs,
                enablePixelCopyValidation = !remotePolicy.disablePixelCopyValidation,
                preferredAudioLanguage = TrackLanguage.normalize(preferredAudioTrack),
                preferredSubtitleLanguage = if (preferredSubtitleTrack == TRACK_DISABLED) {
                    null
                } else {
                    TrackLanguage.normalize(preferredSubtitleTrack)
                },
                allowAudioPassthrough = allowPassthrough,
            ),
        )
        engine.setAspectMode(asVodAspectMode(aspect))
        engine.setListener(media3Listener)
        engine.bind(binding.videoContainer)
        media3VodEngine = engine
        media3Generation = VodEngine.NO_GENERATION
        updateTrackButtons()
    }

    private fun asVodAspectMode(value: AspectRatio): VodAspectMode = when (value) {
        AspectRatio.ORIGINAL -> VodAspectMode.FIT
        AspectRatio.RATIO_16_9 -> VodAspectMode.FIXED_WIDTH
        AspectRatio.RATIO_4_3 -> VodAspectMode.FIXED_HEIGHT
        AspectRatio.FILL -> VodAspectMode.FILL
        AspectRatio.ZOOM -> VodAspectMode.ZOOM
    }

    private val media3Listener = object : VodEngine.Listener {
        override fun onSubmitted(generation: Long) {
            if (!isMedia3Route()) return
            media3Generation = generation
            media3CoordinatorGeneration = coordinatorGeneration
            media3PreferencesAppliedGeneration = VodEngine.NO_GENERATION
        }

        override fun onBuffering(generation: Long) {
            if (!isCurrentMedia3Generation(generation)) return
            setBuffering(true)
        }

        override fun onReady(generation: Long) {
            if (!isCurrentMedia3Generation(generation)) return
            PlaybackQoeRuntime.markReady(qoeSessionId)
            if (playbackStarted) setBuffering(false)
            updateDuration()
            updateTrackButtons()
        }

        override fun onFirstFrame(generation: Long) {
            if (!isCurrentMedia3Generation(generation)) return
            if (vodCoordinator.state.phase == VodPlaybackCoordinator.Phase.STARTING) {
                if (
                    vodCoordinator.dispatch(
                        VodPlaybackCoordinator.Event.Ready(media3CoordinatorGeneration),
                    ).none { it is VodPlaybackCoordinator.Action.Ready }
                ) return
            } else if (vodCoordinator.state.phase != VodPlaybackCoordinator.Phase.PLAYING) {
                return
            }
            playbackStarted = true
            playbackEnded = false
            recoveryInProgress = false
            PlaybackQoeRuntime.markFirstFrame(qoeSessionId)
            handler.removeCallbacks(startupTimeoutRunnable)
            setBuffering(false)
            if (binding.errorOverlay.visibility == View.VISIBLE) {
                binding.errorOverlay.visibility = View.GONE
                setPlayerChromeFocusable(true)
                setControlsVisible(true)
            }
            binding.playPauseButton.setImageResource(R.drawable.ic_pause)
            binding.playPauseButton.contentDescription = getString(R.string.player_pause)
            playbackSession.setPlaying(true, activePositionMs())
            pendingSeekMs = 0L
            resetStallWatch(activePositionMs())
            updateDuration()
            updateTrackButtons()
            startTimers()
            prefetchNextEpisodeMetadata()
            scheduleHideControls()
            if (!trackHintShown) {
                handler.removeCallbacks(trackHintRunnable)
                handler.postDelayed(trackHintRunnable, TRACK_HINT_DEBOUNCE_MS)
            }
            val restoreAsPaused = pauseAfterBackgroundRestore
            pauseAfterBackgroundRestore = false
            if (restoreAsPaused) {
                media3VodEngine?.pause()
                playbackSession.setPlaying(false, activePositionMs())
                binding.playPauseButton.setImageResource(R.drawable.ic_play)
                binding.playPauseButton.contentDescription = getString(R.string.detail_play)
                setControlsVisible(true)
            } else {
                handler.removeCallbacks(stablePlaybackRunnable)
                handler.postDelayed(stablePlaybackRunnable, STABLE_PLAYBACK_MS)
            }
        }

        override fun onEnded(generation: Long) {
            if (!isCurrentMedia3Generation(generation)) return
            if (
                vodCoordinator.dispatch(
                    VodPlaybackCoordinator.Event.Completed(media3CoordinatorGeneration),
                ).none { it is VodPlaybackCoordinator.Action.Completed }
            ) return
            handlePlaybackCompleted()
        }

        override fun onTracksChanged(
            generation: Long,
            audio: List<VodTrack>,
            subtitles: List<VodTrack>,
        ) {
            if (!isCurrentMedia3Generation(generation)) return
            updateTrackButtons()
            applyMedia3PreferencesOnce(generation, audio, subtitles)
            if (!trackHintShown) {
                handler.removeCallbacks(trackHintRunnable)
                handler.postDelayed(trackHintRunnable, TRACK_HINT_DEBOUNCE_MS)
            }
        }

        override fun onFailure(generation: Long, failure: PlaybackFailure) {
            if (!isCurrentMedia3Generation(generation)) return
            handleTypedPlaybackFailure(failure, media3CoordinatorGeneration)
        }
    }

    private fun isCurrentMedia3Generation(generation: Long): Boolean =
        isMedia3Route() &&
            media3VodEngine != null &&
            generation == media3Generation &&
            media3CoordinatorGeneration == coordinatorGeneration &&
            !isFinishing &&
            !isDestroyed

    private fun applyMedia3PreferencesOnce(
        generation: Long,
        audio: List<VodTrack>,
        subtitles: List<VodTrack>,
    ) {
        if (media3PreferencesAppliedGeneration == generation) return
        if (!isCurrentMedia3Generation(generation)) return
        val engine = media3VodEngine ?: return
        val plan = VodTrackPreference.applicationPlan(
            savedAudio = preferredAudioTrack,
            savedSubtitle = preferredSubtitleTrack,
            audioCandidates = audio.map(VodTrack::label),
            subtitleCandidates = subtitles.map(VodTrack::label),
            disabledSubtitleToken = TRACK_DISABLED,
        )
        if (!plan.complete) return

        var applied = true
        plan.audioIndex?.let { index ->
            applied = engine.selectAudioTrack(audio[index].id) && applied
        }
        when {
            plan.disableSubtitles -> {
                applied = engine.selectSubtitleTrack(null) && applied
            }
            plan.subtitleIndex != null -> {
                applied = engine.selectSubtitleTrack(
                    subtitles[plan.subtitleIndex].id,
                ) && applied
            }
        }
        if (applied && isCurrentMedia3Generation(generation)) {
            media3PreferencesAppliedGeneration = generation
        }
    }

    private fun buildPlayer() {
        check(nativeOwner == null) { "Cannot build over an active VLC owner" }
        // Mirror live TV (VlcPlayerEngine): keep VLC's proven decode defaults.
        // avcodec-fast and skipped loop filtering caused macroblocking/pixel rain
        // on real H.264/H.265 content, so neither is enabled here.
        val cachingMs = bufferMode.networkCachingMs
        val options = arrayListOf(
            "--network-caching=$cachingMs",
            "--file-caching=$cachingMs",
            // MediaCodec/OMX direct rendering LEFT ON (libVLC default) on the
            // hardware path so the Amlogic decoder renders straight onto the
            // SurfaceView underlay (its native hardware-video path). The DR-off +
            // android_display vout path could not colour-convert NV12 on the Xiaomi
            // compositor and stayed GREEN (RV16/RV32 chroma overrides had zero
            // effect). forceSoftware uses avcodec-hw=none so DR is irrelevant there.
            // Enables cumulative decoded/displayed/audio counters used by the
            // bounded frozen-video watchdog. Without this flag libVLC 3 may return
            // zeroed media statistics on some builds.
            "--stats",
            "--http-reconnect",
            "--http-user-agent=${AppInfo.USER_AGENT}"
        )
        if (forceSoftware) options.add("--avcodec-hw=none")
        // Match the global passthrough setting. Default OFF decodes Dolby/DTS to
        // stereo PCM; explicit opt-in leaves the encoded bitstream available to an
        // AV receiver/soundbar.
        if (!allowPassthrough) {
            options.add("--no-spdif")
            options.add("--stereo-mode=1")
        }
        // Do not force bob deinterlacing globally. It doubles output work on
        // interlaced sources and overloads weaker sticks; VLC may select a
        // suitable deinterlacer from stream/device capabilities when needed.
        val vlc = LibVLC(this, options)
        vlc.setUserAgent(AppInfo.USER_AGENT, AppInfo.USER_AGENT)
        val mp = MediaPlayer(vlc)

        val layout = VLCVideoLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        binding.videoContainer.addView(layout)
        // 3rd true = render embedded subtitles; 4th false = SurfaceView output,
        // REQUIRED to show the Amlogic hardware underlay video plane (TextureView
        // always greens out on real sticks).
        mp.attachViews(layout, null, true, false)

        libVlc = vlc
        mediaPlayer = mp
        videoLayout = layout
        val owner = NativePlayerOwner(
            generation = nativeOwnerGeneration.incrementAndGet(),
            libVlc = vlc,
            mediaPlayer = mp,
            videoLayout = layout,
        )
        nativeOwner = owner
        vlcPlaybackSnapshot = VlcPlaybackSnapshot(
            ownerGeneration = owner.generation,
            positionMs = 0L,
            durationMs = 0L,
            playing = false,
            streamInfo = null,
            decodedVideo = null,
            displayedPictures = null,
            playedAudioBuffers = null,
            readBytes = null,
        )
        lastVlcSnapshotRequestAtMs = 0L
        updateTrackButtons()
    }

    /** Invalidate every callback captured by the previous native media session. */
    private fun invalidateEventSession() {
        eventSession.incrementAndGet()
    }

    /**
     * libVLC may invoke listeners on a native thread and may deliver queued
     * callbacks after stop()/setMedia(). Snapshot the event payload, marshal it
     * to main, then re-check both player identity and the monotonically increasing
     * media session before touching any Activity state or View.
     */
    private fun installEventListener(mp: MediaPlayer, session: Long) {
        mp.setEventListener { event ->
            val eventType = event.type
            val bufferingPercent =
                if (eventType == MediaPlayer.Event.Buffering) event.buffering else 0f
            val voutCount =
                if (eventType == MediaPlayer.Event.Vout) event.voutCount else 0

            // These are read-only fast-path checks. The authoritative checks run
            // again on main after any queued stop/setMedia callback can overtake us.
            if (mediaPlayer !== mp || eventSession.get() != session) {
                return@setEventListener
            }
            if (suppressDuringNativeCommand(eventType)) {
                return@setEventListener
            }

            handler.post {
                dispatchPlayerEventOnMain(
                    mp = mp,
                    session = session,
                    eventType = eventType,
                    bufferingPercent = bufferingPercent,
                    voutCount = voutCount,
                )
            }
        }
    }

    private fun dispatchPlayerEventOnMain(
        mp: MediaPlayer,
        session: Long,
        eventType: Int,
        bufferingPercent: Float,
        voutCount: Int,
    ) {
        if (mediaPlayer !== mp || eventSession.get() != session) return
        if (suppressDuringNativeCommand(eventType)) return
        val owner = nativeOwner ?: return
        if (owner.mediaPlayer !== mp || owner.abandoned.get()) return
        if (owner.activeOperation.get() != null) {
            // Playing/Vout can be emitted synchronously from mp.play(). Defer the
            // event rather than reading/seeking the same handle while its bounded
            // owner command is still unwinding.
            handler.postDelayed(
                {
                    dispatchPlayerEventOnMain(
                        mp = mp,
                        session = session,
                        eventType = eventType,
                        bufferingPercent = bufferingPercent,
                        voutCount = voutCount,
                    )
                },
                NATIVE_EVENT_RETRY_MS,
            )
            return
        }
        handlePlayerEventOnMain(
            mp = mp,
            eventType = eventType,
            bufferingPercent = bufferingPercent,
            voutCount = voutCount,
        )
    }

    private fun suppressDuringNativeCommand(eventType: Int): Boolean =
        nativeCommandsInFlight.get() > 0 &&
            (
                eventType == MediaPlayer.Event.Stopped ||
                    eventType == MediaPlayer.Event.EndReached ||
                    eventType == MediaPlayer.Event.EncounteredError
                )

    /** Called only by [installEventListener]'s validated main-thread dispatch. */
    private fun handlePlayerEventOnMain(
        mp: MediaPlayer,
        eventType: Int,
        bufferingPercent: Float,
        voutCount: Int,
    ) {
        when (eventType) {
            MediaPlayer.Event.Buffering -> {
                setBuffering(isVlcBuffering(bufferingPercent))
            }
            MediaPlayer.Event.Playing -> {
                setVlcPlayingSnapshot(mp, true)
                if (vodCoordinator.state.phase == VodPlaybackCoordinator.Phase.STARTING) {
                    if (
                        vodCoordinator.dispatch(
                            VodPlaybackCoordinator.Event.Ready(coordinatorGeneration),
                        ).none { it is VodPlaybackCoordinator.Action.Ready }
                    ) return
                }
                // AUTO's 4K software fallback stutters; return to hardware
                // before touching this about-to-be-released player again.
                if (maybeEscalateForUhd()) return
                playbackStarted = true
                playbackEnded = false
                PlaybackQoeRuntime.markReady(qoeSessionId)
                startTimers()
                prefetchNextEpisodeMetadata()
                handler.removeCallbacks(startupTimeoutRunnable)
                setBuffering(false)
                if (binding.errorOverlay.visibility == View.VISIBLE) {
                    binding.errorOverlay.visibility = View.GONE
                    setPlayerChromeFocusable(true)
                    setControlsVisible(true)
                }
                binding.playPauseButton.setImageResource(R.drawable.ic_pause)
                binding.playPauseButton.contentDescription = getString(R.string.player_pause)
                // Restart the idle timer once real playback begins.
                scheduleHideControls()
                resetStallWatch(bestResumePosition(null))
                val restoreAsPaused = pauseAfterBackgroundRestore
                updateDuration()
                updateTrackButtons()
                // Apply deferred seek/aspect/pause as one bounded owner command.
                // Starting several independent commands here would contend for the
                // same owner reservation while Playing is still being dispatched.
                val deferredPosition = pendingSeekMs.coerceAtLeast(0L)
                val selectedAspect = aspect
                val containerWidth = binding.videoContainer.width
                val containerHeight = binding.videoContainer.height
                pauseAfterBackgroundRestore = false
                postBoundedOwnerCommand(
                    label = "playback start configuration",
                    recoverAsPaused = restoreAsPaused,
                    onCompletedMain = {
                        if (pendingSeekMs == deferredPosition) pendingSeekMs = 0L
                        playbackSession.setPlaying(!restoreAsPaused, activePositionMs())
                    },
                ) { player ->
                    if (deferredPosition > 0L) player.setTime(deferredPosition)
                    applyAspectNative(
                        player,
                        selectedAspect,
                        containerWidth,
                        containerHeight,
                    )
                    if (restoreAsPaused) player.pause()
                }
                startSurfaceValidation()
                scheduleUhdRechecks()
                // VLC parses audio/subtitle tracks shortly after playback begins;
                // schedule a debounced check to surface the hint.
                if (!trackHintShown) {
                    handler.removeCallbacks(trackHintRunnable)
                    handler.postDelayed(trackHintRunnable, TRACK_HINT_DEBOUNCE_MS)
                }
                handler.removeCallbacks(preferredTrackRunnable)
                handler.postDelayed(preferredTrackRunnable, TRACK_HINT_DEBOUNCE_MS)
                // A single frame is not enough to forgive a decode loop. Only
                // sustained playback resets the retry/fallback counters.
                handler.removeCallbacks(stablePlaybackRunnable)
                if (!restoreAsPaused) {
                    handler.postDelayed(stablePlaybackRunnable, STABLE_PLAYBACK_MS)
                }
            }
            MediaPlayer.Event.ESAdded -> {
                // Each elementary stream (audio/subtitle) detected resets the
                // debounce so we evaluate once the full set is known.
                if (!trackHintShown) {
                    handler.removeCallbacks(trackHintRunnable)
                    handler.postDelayed(trackHintRunnable, TRACK_HINT_DEBOUNCE_MS)
                }
                handler.removeCallbacks(preferredTrackRunnable)
                handler.postDelayed(preferredTrackRunnable, TRACK_HINT_DEBOUNCE_MS)
                updateTrackButtons()
                scheduleUhdRechecks()
            }
            MediaPlayer.Event.Vout -> {
                if (voutCount > 0) {
                    decodedVideoSeen = true
                    startSurfaceValidation()
                }
            }
            MediaPlayer.Event.Paused -> {
                setVlcPlayingSnapshot(mp, false)
                playbackSession.setPlaying(false, bestResumePosition(null))
                handler.removeCallbacks(stablePlaybackRunnable)
                binding.playPauseButton.setImageResource(R.drawable.ic_play)
                binding.playPauseButton.contentDescription = getString(R.string.detail_play)
                handler.removeCallbacks(hideControlsRunnable)
                setBuffering(false)
                resetStallWatch()
                handler.removeCallbacks(uhdCheckRunnable)
                resetSurfaceValidation()
                setControlsVisible(true)
            }
            MediaPlayer.Event.Stopped -> {
                setVlcPlayingSnapshot(mp, false)
                playbackSession.setPlaying(false, bestResumePosition())
                binding.playPauseButton.setImageResource(R.drawable.ic_play)
                binding.playPauseButton.contentDescription = getString(R.string.detail_play)
                setBuffering(false)
                handler.removeCallbacks(uhdCheckRunnable)
                resetSurfaceValidation()
            }
            MediaPlayer.Event.EndReached -> {
                setVlcPlayingSnapshot(mp, false)
                if (
                    vodCoordinator.dispatch(
                        VodPlaybackCoordinator.Event.Completed(coordinatorGeneration),
                    ).none { it is VodPlaybackCoordinator.Action.Completed }
                ) return
                handlePlaybackCompleted()
            }
            MediaPlayer.Event.EncounteredError -> handlePlaybackError()
            MediaPlayer.Event.LengthChanged -> refreshVlcPlaybackSnapshot(
                force = true,
                onUpdatedMain = ::updateDuration,
            )
        }
    }

    private fun handlePlaybackCompleted() {
        if (completionHandled) return
        completionHandled = true
        val completionToken = ++completionGeneration
        playbackStarted = false
        playbackEnded = true
        playbackSession.setPlaying(false, activePositionMs())
        finishQoe(PlaybackEndReason.COMPLETED)
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(uhdCheckRunnable)
        resetSurfaceValidation()
        binding.playPauseButton.setImageResource(R.drawable.ic_restart)
        binding.playPauseButton.contentDescription = getString(R.string.resume_from_start)
        setBuffering(false)
        setControlsVisible(true)
        binding.playPauseButton.requestFocus()
        onPlaybackFinished(completionToken)
    }

    private fun promptResume(positionMs: Long) {
        resumeChoicePending = true
        activeDialog = PlayerDialogs.showResume(
            activity = this,
            positionText = formatTime(positionMs),
            onResume = {
                resumeChoicePending = false
                activeDialog = null
                preparePlayback(positionMs)
            },
            onStartOver = {
                resumeChoicePending = false
                activeDialog = null
                preparePlayback(0L)
            },
        ).apply {
            // PlayerDialogs defaults to non-cancelable. VOD must still let a TV
            // viewer leave with the remote Back key instead of trapping them.
            setCancelable(true)
            setOnCancelListener {
                resumeChoicePending = false
                activeDialog = null
                finish()
            }
        }
    }

    /**
     * Media3 opens its HTTP source directly from the main thread, outside the
     * shared VLC queue. Trampoline its start through that queue so a previous VOD
     * Activity's blocking VLC stop/release has definitely completed before this
     * item consumes a one-connection subscription slot.
     */
    private fun prepareMedia3AfterProviderDrain(positionMs: Long) {
        val request = providerStartSeq.incrementAndGet()
        setBuffering(true)
        VlcOps.post {
            handler.post media3Start@{
                if (
                    providerStartSeq.get() != request ||
                    !foreground ||
                    isFinishing ||
                    isDestroyed ||
                    !isMedia3Route()
                ) {
                    return@media3Start
                }
                if (!ProviderConnectionSafety.newConnectionAllowed) {
                    if (
                        handleBlockedProviderStart(
                            reason = "vod_media3_start_unresolved_native_owner",
                            afterDrain = { prepareMedia3AfterProviderDrain(positionMs) },
                        )
                    ) {
                        return@media3Start
                    }
                    return@media3Start
                }
                prepareMedia3Playback(positionMs)
            }
        }
    }

    private fun preparePlayback(positionMs: Long) {
        if (
            handleBlockedProviderStart(
                reason = "vod_start_unresolved_native_owner",
                afterDrain = { preparePlayback(positionMs) },
            )
        ) {
            return
        }
        // Reusing a native player must still create a distinct media-event
        // generation. Invalidate the old listener before any preparation state
        // changes so queued callbacks from the previous URL cannot mutate this
        // session; the replacement listener is installed after owner reservation.
        invalidateEventSession()
        handler.removeCallbacks(preferredTrackRunnable)
        playbackRequested = true
        playbackStarted = false
        playbackEnded = false
        decodedVideoSeen = false
        completionHandled = false
        completionGeneration++
        recoveryInProgress = false
        startQoeIfNeeded()
        resetStallWatch(positionMs)
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(uhdCheckRunnable)
        resetSurfaceValidation()
        // Keep all deferred-position sources synchronized. This also clears an
        // older, higher background value when the viewer intentionally seeks
        // backwards or starts the next item from zero.
        pendingSeekMs = positionMs.coerceAtLeast(0L)
        backgroundResumePosition = pendingSeekMs
        // attach() deliberately does not auto-load an already active Cast
        // session. Only after profile resume has been resolved and a backend is
        // built do we issue one reload, so mediaProvider submits the saved/pending
        // position instead of rewinding the receiver to zero. The two-phase Cast
        // callbacks close the local socket synchronously before remote load.
        if (!connectedCastReloadRequested && castController.isConnected) {
            connectedCastReloadRequested = true
            castLoadFailureHandled = false
            castController.reloadCurrentMedia()
            if (castHandoffStopped || castLoadFailureHandled) return
            // Connection disappeared between the check and reloadCurrentMedia;
            // no Cast callback owns restoration, so continue with local prepare.
        }
        // initPlayer may finish during onCreate, before onStart marks the screen
        // foreground. Defer opening the subscription socket until STARTED; the
        // same path also safely absorbs a delayed retry that fires while away.
        if (!foreground) {
            resumeAfterBackground = true
            return
        }
        if (playbackResourceToken == null) {
            playbackResourceToken = PlaybackResourceGovernor.begin("vod-player")
        }
        // Resource-safe background teardown may release an engine even while a
        // resume dialog or terminal card was visible. Build a fresh backend only
        // when the user actually chooses playback again.
        if (!activeEngineExists()) {
            try {
                buildActivePlayer()
            } catch (error: Throwable) {
                PlaybackLog.log(
                    this,
                    "VOD",
                    "fresh backend creation failed: ${error.javaClass.simpleName}",
                )
                showTerminalError()
                return
            }
        }
        if (isMedia3Route()) {
            prepareMedia3AfterProviderDrain(positionMs)
            return
        }
        val owner = nativeOwner ?: return
        val ownerOperation = beginOwnerOperation(owner)
        if (ownerOperation == null) {
            // A background stop/start or earlier native command still owns these
            // handles. Quarantine with an exact token, then let the provider-drain
            // gate build a fresh owner only after cleanup is proven or the process
            // is safely recycled.
            quarantineOwner(owner, "prepare overlapped an active native operation")
            preparePlayback(positionMs)
            return
        }
        val vlc = owner.libVlc
        val mp = owner.mediaPlayer
        val url = streamUrl ?: run {
            cancelOwnerOperationBeforeDispatch(ownerOperation)
            return
        }
        binding.errorOverlay.visibility = View.GONE
        setBuffering(true)
        updateTrackButtons()
        val cachingMs = bufferMode.networkCachingMs
        val media = try {
            Media(vlc, android.net.Uri.parse(url)).apply {
                // Hardware decoding unless the global Decoder setting forces software
                // (mirrors VlcPlayerEngine on the live TV path).
                // Prefer hardware while respecting libVLC's device/codec safety list.
                // force=true enabled known-broken Amlogic MediaCodec paths and could
                // leave the SurfaceView permanently green.
                setHWDecoderEnabled(!forceSoftware, false)
                addOption(":network-caching=$cachingMs")
                addOption(":file-caching=$cachingMs")
                if (forceSoftware) addOption(":avcodec-hw=none")
                if (!allowPassthrough) {
                    addOption(":no-spdif")
                    // Force a 5.1 -> 2.0 stereo downmix so boxes that cannot open a
                    // multichannel PCM AudioTrack still get sound.
                    addOption(":stereo-mode=1")
                }
                addOption(":http-reconnect")
                addOption(":http-user-agent=${AppInfo.USER_AGENT}")
            }
        } catch (error: Throwable) {
            cancelOwnerOperationBeforeDispatch(ownerOperation)
            throw error
        }
        // Start on the shared VLC ops thread: FIFO ordering guarantees any queued
        // teardown (the live player released when this screen opened, or the UHD
        // escalation's old player) has fully closed its connection first
        // (single-connection contract), and the main thread never waits on it.
        val operation = playbackOpsSeq.incrementAndGet()
        startupTimeoutOperation = operation
        handler.postDelayed(startupTimeoutRunnable, STARTUP_TIMEOUT_MS)
        val nativeCommandFinished = AtomicBoolean(false)
        fun finishNativeCommand() {
            if (nativeCommandFinished.compareAndSet(false, true)) {
                nativeCommandsInFlight.decrementAndGet()
            }
        }
        nativeCommandsInFlight.incrementAndGet()
        ownerOperation.onAbandoned.set(::finishNativeCommand)
        // Allocate the event generation before dispatch. If lifecycle/recovery
        // invalidates it while setEventListener() is blocked, the eventual native
        // callback is stale even before owner identity is checked.
        val listenerSession = eventSession.incrementAndGet()
        VlcOps.postBounded(
            timeoutMs = NATIVE_OPERATION_TIMEOUT_MS,
            onTimeout = {
                finishNativeCommand()
                if (
                    playbackOpsSeq.compareAndSet(operation, operation + 1L) &&
                    foreground &&
                    !isFinishing &&
                    !isDestroyed
                ) {
                    // Never touch the timed-out handles here. The abandoned
                    // operation owns their eventual cleanup on its retired worker.
                    requireOwnerProcessRecovery(
                        owner,
                        markOwnerConnectionUncertain(owner),
                    )
                    quarantineOwner(owner, "native prepare timed out")
                    handlePlaybackError()
                }
            },
        ) playbackCommand@{
            var startFailure: Throwable? = null
            var providerBlocked = false
            try {
                if (
                    playbackOpsSeq.get() != operation ||
                    !foreground ||
                    owner.abandoned.get()
                ) return@playbackCommand
                installEventListener(mp, listenerSession)
                if (
                    playbackOpsSeq.get() != operation ||
                    !foreground ||
                    owner.abandoned.get()
                ) return@playbackCommand
                mp.stop()
                if (
                    playbackOpsSeq.get() != operation ||
                    !foreground ||
                    owner.abandoned.get()
                ) return@playbackCommand
                if (!ProviderConnectionSafety.newConnectionAllowed) {
                    providerBlocked = true
                } else {
                    mp.media = media
                    if (!ProviderConnectionSafety.newConnectionAllowed) {
                        providerBlocked = true
                    } else {
                        mp.play()
                    }
                }
            } catch (error: Throwable) {
                startFailure = error
            } finally {
                runCatching { media.release() }
                finishNativeCommand()
                ownerOperation.onAbandoned.set(null)
                finishOwnerOperationOnWorker(ownerOperation)
            }
            if (providerBlocked) {
                runOnUiThread {
                    if (
                        nativeOwner === owner &&
                        playbackOpsSeq.get() == operation &&
                        foreground &&
                        !isFinishing &&
                        !isDestroyed
                    ) {
                        preparePlayback(positionMs)
                    }
                }
                return@playbackCommand
            }
            if (startFailure != null) {
                // All native work above has ended before main enters recovery.
                runOnUiThread {
                    if (
                        playbackOpsSeq.get() == operation &&
                        nativeOwner === owner &&
                        !owner.abandoned.get() &&
                        foreground &&
                        !isFinishing &&
                        !isDestroyed
                    ) {
                        PlaybackLog.log(
                            this,
                            "VOD",
                            "native prepare failed: " +
                                startFailure?.javaClass?.simpleName,
                        )
                        handlePlaybackError()
                    }
                }
            }
        }
    }

    private fun prepareMedia3Playback(positionMs: Long) {
        val url = streamUrl ?: return
        val engine = media3VodEngine ?: run {
            buildMedia3Player()
            media3VodEngine ?: return
        }
        binding.errorOverlay.visibility = View.GONE
        setBuffering(true)
        updateTrackButtons()
        val operation = playbackOpsSeq.incrementAndGet()
        startupTimeoutOperation = operation
        handler.postDelayed(startupTimeoutRunnable, STARTUP_TIMEOUT_MS)
        val submittedGeneration = try {
            engine.play(url, positionMs.coerceAtLeast(0L))
        } catch (error: Throwable) {
            handler.removeCallbacks(startupTimeoutRunnable)
            handleTypedPlaybackFailure(
                PlaybackFailureClassifier.classifyThrowable(
                    error,
                    PlaybackFailure.Phase.STARTUP,
                ),
            )
            // Recovery above may synchronously replace this backend. Do not let
            // the retired call overwrite the replacement engine's generation.
            return
        }
        if (
            media3VodEngine === engine &&
            isMedia3Route() &&
            media3CoordinatorGeneration == coordinatorGeneration
        ) {
            media3Generation = submittedGeneration
        }
    }

    /**
     * Recover an on-demand failure without leaving the viewer on a black screen.
     * Ordinary source/network failures retry the configured path before AUTO's
     * normal fallback. A sampled green/blank frame or sustained freeze is stronger
     * decoder evidence, so Hardware/Software are treated as preferences and the
     * opposite decoder gets one bounded rescue attempt. The rescue budget is not
     * reset by a Playing event, preventing hardware/software ping-pong.
     */
    private fun handlePlaybackError(
        evidence: RecoveryEvidence = RecoveryEvidence.GENERIC,
    ) {
        val failure = if (evidence == RecoveryEvidence.CONFIRMED_VIDEO_FAILURE) {
            PlaybackFailureClassifier.classify(
                FailureSignal.Output(PlaybackFailure.Component.VIDEO),
                PlaybackFailure.Phase.PLAYBACK,
            )
        } else {
            val snapshot = currentVlcSnapshot()
            VodPlaybackRoutingPolicy.classifyVlcEncounteredError(
                evidence = VodPlaybackRoutingPolicy.VlcErrorEvidence(
                    playbackStarted = playbackStarted,
                    voutSeen = decodedVideoSeen,
                    decodedVideo = snapshot?.decodedVideo,
                    displayedPictures = snapshot?.displayedPictures,
                    readBytes = snapshot?.readBytes,
                    videoCodec = snapshot?.streamInfo?.codec,
                ),
                phase = if (playbackStarted) {
                    PlaybackFailure.Phase.PLAYBACK
                } else {
                    PlaybackFailure.Phase.STARTUP
                },
            )
        }
        handleTypedPlaybackFailure(failure, coordinatorGeneration)
    }

    private fun handleTypedPlaybackFailure(
        failure: PlaybackFailure,
        failureGeneration: Long = coordinatorGeneration,
    ) {
        if (failureGeneration != coordinatorGeneration) return
        if (!foreground || isFinishing || isDestroyed || recoveryInProgress) return
        PlaybackQoeRuntime.recordFailure(qoeSessionId, failure)
        if (!activeRouteStable && rememberedInitialRoute) {
            PlaybackRouteMemory.markFailed(vodRouteKey, activeVodRoute.name)
            rememberedInitialRoute = false
        }
        val errorOwner = nativeOwner
        val ownerBusy = errorOwner?.activeOperation?.get() != null
        val resumeAt =
            if (ownerBusy) bestResumePosition(null)
            else bestResumePosition(
                if (isMedia3Route()) activePositionMs() else currentVlcSnapshot()?.positionMs,
            )
        invalidateEventSession()
        errorOwner?.let { owner ->
            if (owner.activeOperation.get() != null || owner.abandoned.get()) {
                quarantineOwner(owner, "playback error during active native operation")
            }
        }
        recoveryInProgress = true
        handler.removeCallbacks(stablePlaybackRunnable)
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(uhdCheckRunnable)
        resetSurfaceValidation()
        playbackStarted = false
        setBuffering(false)
        PlaybackLog.log(
            this,
            "VOD",
            "playback failure category=${failure.category} code=${failure.code} " +
                "route=$activeVodRoute phase=${failure.phase}",
        )

        val actions = vodCoordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(failureGeneration, failure),
        )
        val start = actions.filterIsInstance<VodPlaybackCoordinator.Action.Start>().lastOrNull()
        if (start != null) {
            crossDecoderRescueAttempted = true
            playbackErrorAttempts++
            coordinatorGeneration = start.generation
            val previousRoute = activeVodRoute
            PlaybackLog.log(
                this,
                "VOD",
                "bounded recovery ${start.reason} $previousRoute -> ${start.route}",
            )
            val scheduledOperation = playbackOpsSeq.get()
            val runRecovery = Runnable {
                if (
                    foreground &&
                    !castHandoffStopped &&
                    !isFinishing &&
                    !isDestroyed &&
                    playbackOpsSeq.get() == scheduledOperation &&
                    coordinatorGeneration == start.generation &&
                    vodCoordinator.state.generation == start.generation &&
                    vodCoordinator.state.phase == VodPlaybackCoordinator.Phase.STARTING
                ) {
                    rebuildRoute(resumeAt, start.route)
                }
            }
            if (start.reason == VodPlaybackCoordinator.StartReason.SOURCE_RETRY) {
                setBuffering(true)
                handler.postDelayed(runRecovery, VOD_RETRY_DELAY_MS)
            } else {
                runRecovery.run()
            }
            return
        }
        if (actions.any { it is VodPlaybackCoordinator.Action.TerminalFailure }) {
            if (isMedia3Route()) {
                media3VodEngine?.stop()
            } else {
                postBoundedOwnerCommand(label = "terminal stop") { it.stop() }
            }
            playbackSession.setPlaying(false, resumeAt)
            finishQoe(PlaybackEndReason.FATAL_FAILURE)
            showTerminalError(failure)
        } else {
            recoveryInProgress = false
        }
    }

    /**
     * If AUTO has fallen back to software for a UHD/4K stream (which no TV-box CPU
     * can handle at 80–100 Mbps), rebuild on hardware and resume from the current
     * position. Returns true when an escalation was kicked off so the caller stops
     * touching the about-to-be-released player. Explicit SOFTWARE remains software.
     */
    private fun maybeEscalateForUhd(): Boolean {
        // Explicit "Software only" must remain software. AUTO may recover UHD by
        // moving back to hardware, where 4K decoding is realistically possible.
        if (decoderMode != DecoderMode.AUTO || uhdEscalated || !forceSoftware) return false
        val info = currentVlcSnapshot()?.streamInfo ?: return false
        if (info.height < UHD_MIN_HEIGHT && info.width < UHD_MIN_WIDTH) return false
        uhdEscalated = true
        handler.removeCallbacks(uhdCheckRunnable)
        // Playing arrives before the initial resume seek is applied. Preserve that
        // deferred position when the software player is immediately replaced.
        val resumeAt = bestResumePosition()
        backgroundResumePosition = resumeAt
        handler.post {
            handleTypedPlaybackFailure(
                PlaybackFailureClassifier.classify(
                    FailureSignal.Resource(FailureSignal.ResourceKind.EXHAUSTED),
                    PlaybackFailure.Phase.PLAYBACK,
                ),
            )
        }
        return true
    }

    /**
     * Rebuild libVLC sequentially on the shared native-ops thread. This is used by
     * AUTO decoder fallback/escalation and preserves the one-subscription-
     * connection contract: old stop/release completes before the new Media starts.
     */
    private fun rebuildPlayer(resumeAt: Long, useSoftware: Boolean) {
        rebuildRoute(
            resumeAt = resumeAt,
            targetRoute = if (useSoftware) {
                VodPlaybackRoutingPolicy.Route.VLC_SOFTWARE
            } else {
                VodPlaybackRoutingPolicy.Route.VLC_HARDWARE
            },
        )
    }

    /** Sequentially replace either backend without overlapping provider sockets. */
    private fun rebuildRoute(
        resumeAt: Long,
        targetRoute: VodPlaybackRoutingPolicy.Route,
    ) {
        handler.removeCallbacks(stablePlaybackRunnable)
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(uhdCheckRunnable)
        handler.removeCallbacks(preferredTrackRunnable)
        resetSurfaceValidation()
        binding.errorOverlay.visibility = View.GONE
        setBuffering(true)
        playbackRequested = true
        playbackStarted = false
        backgroundResumePosition = resumeAt
        pendingSeekMs = resumeAt
        val operation = playbackOpsSeq.incrementAndGet()
        fun continueWithFreshOwner() {
            if (
                isDestroyed ||
                isFinishing ||
                !foreground ||
                playbackOpsSeq.get() != operation
            ) {
                return
            }
            activeVodRoute = targetRoute
            activeRouteStable = false
            forceSoftware = targetRoute == VodPlaybackRoutingPolicy.Route.VLC_SOFTWARE
            PlaybackQoeRuntime.markEngine(qoeSessionId, qoeEngine(targetRoute))
            // preparePlayback owns the provider gate and builds only after any
            // prior exact owner token has drained.
            preparePlayback(resumeAt)
        }

        val retiringMedia3 = media3VodEngine
        if (retiringMedia3 != null) {
            media3Generation = VodEngine.NO_GENERATION
            media3PreferencesAppliedGeneration = VodEngine.NO_GENERATION
            media3VodEngine = null
            retiringMedia3.setListener(null)
            retiringMedia3.release()
            continueWithFreshOwner()
            return
        }

        val retiringOwner = nativeOwner
        if (retiringOwner == null) {
            continueWithFreshOwner()
            return
        }
        val ownerOperation = retireIdleOwnerOnMain(retiringOwner)
        if (ownerOperation == null) {
            // Another worker still owns these handles. Quarantine now, but put a
            // barrier on the shared VLC queue before opening Media3/VLC again.
            // Without it, a cross-engine fallback could open the same subscription
            // immediately while the prior operation was still within its liveness
            // bound. A true JNI timeout remains governed by VlcOps quarantine.
            markOwnerConnectionUncertain(retiringOwner)
            quarantineOwner(retiringOwner, "rebuild overlapped an active operation")
            VlcOps.post {
                runOnUiThread { continueWithFreshOwner() }
            }
            return
        }

        val nativeCleanupFinished = AtomicBoolean(false)
        fun finishNativeCleanup() {
            if (nativeCleanupFinished.compareAndSet(false, true)) {
                nativeCommandsInFlight.decrementAndGet()
            }
        }
        nativeCommandsInFlight.incrementAndGet()
        ownerOperation.onAbandoned.set(::finishNativeCleanup)
        val rebuildUncertaintyToken = markOwnerConnectionUncertain(retiringOwner)
        VlcOps.postBounded(
            timeoutMs = NATIVE_RELEASE_TIMEOUT_MS,
            onTimeout = {
                finishNativeCleanup()
                if (
                    playbackOpsSeq.compareAndSet(operation, operation + 1L) &&
                    foreground &&
                    !isFinishing &&
                    !isDestroyed
                ) {
                    requireOwnerProcessRecovery(retiringOwner, rebuildUncertaintyToken)
                    quarantineOwner(retiringOwner, "native rebuild cleanup timed out")
                    recoveryInProgress = false
                    handlePlaybackError()
                }
            },
        ) {
            try {
                cleanupOwnerOnCurrentWorker(retiringOwner)
            } finally {
                finishNativeCleanup()
                ownerOperation.onAbandoned.set(null)
                finishOwnerOperationOnWorker(ownerOperation)
            }
            runOnUiThread {
                continueWithFreshOwner()
            }
        }
    }

    /**
     * On playback completion: mark the finished item watched (so grid/episode
     * badges update) and, for series episodes, auto-continue with the next
     * episode — same season first, then the first episode of the next season —
     * so binge-watching is hands-free.
     */
    private fun onPlaybackFinished(completionToken: Long) {
        val meta = resumeMeta ?: return
        // Completion must survive an immediate Back press so watched badges do
        // not randomly disappear when lifecycleScope is cancelled on destroy.
        // Clear the resume row from the explicit EndReached event rather than
        // trusting VLC's terminal time value, which is zero on some devices.
        resumeSaveJob?.cancel()
        val repository = repo
        val playbackProfileId = activeProfileId
        ServiceLocator.appScope.launch {
            try {
                repository.completePlaybackForProfile(
                    profileId = playbackProfileId,
                    contentId = meta.contentId,
                    type = meta.kind.raw,
                    seriesId = meta.seriesId,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
        if (meta.kind != ResumeKind.EPISODE) return
        lifecycleScope.launch {
            val seriesId = meta.seriesId ?: return@launch
            val next = nextEpisode(seriesId, meta.seasonNumber, meta.episodeNumber) ?: return@launch
            if (
                completionToken != completionGeneration ||
                !playbackEnded ||
                !foreground ||
                isFinishing ||
                isDestroyed
            ) return@launch
            showNextEpisodePrompt(next, meta, completionToken)
        }
    }

    private fun showNextEpisodePrompt(
        next: Episode,
        previous: ResumeMeta,
        completionToken: Long,
    ) {
        if (
            completionToken != completionGeneration ||
            !playbackEnded ||
            !foreground ||
            binding.nextEpisodeOverlay.visibility == View.VISIBLE
        ) {
            return
        }
        if (!nextEpisodeGate.arm(completionToken)) return
        pendingNextEpisode = next
        nextEpisodeSecondsRemaining = NEXT_EPISODE_COUNTDOWN_SECONDS
        // Preserve the series-level resume metadata until the prompt is consumed.
        resumeMeta = previous
        binding.nextEpisodeName.text = next.title
        updateNextEpisodeCountdown()
        handler.removeCallbacks(nextEpisodeCountdownRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        binding.nextEpisodeOverlay.visibility = View.VISIBLE
        binding.nextEpisodeOverlay.bringToFront()
        setPlayerChromeFocusable(false)
        binding.nextEpisodePlayNow.requestFocus()
        handler.postDelayed(nextEpisodeCountdownRunnable, 1_000L)
    }

    private fun updateNextEpisodeCountdown() {
        binding.nextEpisodeCountdown.text = getString(
            R.string.next_episode_countdown,
            nextEpisodeSecondsRemaining,
        )
    }

    private fun playPendingNextEpisode() {
        val next = pendingNextEpisode ?: return
        val previous = resumeMeta ?: return
        if (!nextEpisodeGate.consume(completionGeneration)) return
        dismissNextEpisodePrompt(restoreFocus = false)
        startEpisode(next, previous)
    }

    private fun cancelNextEpisodePrompt() {
        if (binding.nextEpisodeOverlay.visibility != View.VISIBLE) return
        dismissNextEpisodePrompt(restoreFocus = true)
    }

    private fun dismissNextEpisodePrompt(restoreFocus: Boolean) {
        handler.removeCallbacks(nextEpisodeCountdownRunnable)
        nextEpisodeGate.cancel()
        binding.nextEpisodeOverlay.visibility = View.GONE
        pendingNextEpisode = null
        nextEpisodeSecondsRemaining = 0
        setPlayerChromeFocusable(true)
        if (restoreFocus && !isFinishing && !isDestroyed) {
            showControls()
            binding.playPauseButton.requestFocus()
        }
    }

    private fun prefetchNextEpisodeMetadata() {
        val meta = resumeMeta?.takeIf { it.kind == ResumeKind.EPISODE } ?: return
        val seriesId = meta.seriesId ?: return
        if (prefetchedResumeId == meta.contentId && nextEpisodePrefetchJob != null) return
        nextEpisodePrefetchJob?.cancel()
        prefetchedResumeId = meta.contentId
        nextEpisodePrefetchJob = lifecycleScope.launch {
            try {
                val config = settings.getSourceConfig() ?: return@launch
                repo.getSeasons(config, seriesId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Completion still has the local cache and performs one final
                // best-effort refresh; prefetch must never interrupt playback.
            }
        }
    }

    /** Next episode after (season, episode), rolling into the next season's first. */
    private suspend fun nextEpisode(seriesId: String, season: Int, episode: Int): Episode? {
        nextEpisodePrefetchJob?.join()
        val cached = try {
            repo.getCachedSeasons(seriesId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
        findNextEpisode(cached, season, episode)?.let { return it }
        val refreshed = try {
            val config = settings.getSourceConfig() ?: return null
            repo.getSeasons(config, seriesId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
        return findNextEpisode(refreshed, season, episode)
    }

    private fun findNextEpisode(
        seasons: List<Season>,
        season: Int,
        episode: Int,
    ): Episode? {
        // Providers sometimes include placeholder episodes with no stream URL.
        // Walk forward in episode order and skip those rows instead of launching
        // an empty Uri into the retry/fallback ladder.
        for (seasonEntry in seasons.sortedBy { it.seasonNumber }) {
            if (seasonEntry.seasonNumber < season) continue
            val next = seasonEntry.episodes
                .asSequence()
                .filter { candidate ->
                    candidate.streamUrl.isNotBlank() &&
                        (seasonEntry.seasonNumber > season ||
                            candidate.episodeNumber > episode)
                }
                .minByOrNull { it.episodeNumber }
            if (next != null) return next
        }
        return null
    }

    /** Swaps the player onto [next], reusing the live engine, and plays from start. */
    private fun startEpisode(next: Episode, prev: ResumeMeta) {
        dismissNextEpisodePrompt(restoreFocus = false)
        playbackEnded = false
        completionHandled = false
        completionGeneration++
        streamUrl = next.streamUrl
        resumeId = "ep_" + next.id
        resumeMeta = prev.copy(
            contentId = "ep_" + next.id,
            streamUrl = next.streamUrl,
            posterUrl = next.posterUrl ?: prev.posterUrl,
            seasonNumber = next.seasonNumber,
            episodeNumber = next.episodeNumber
        )
        binding.titleText.text = next.title
        nowKind = "Dizi"
        NowPlaying.set(this, next.title, nowKind)
        // Fresh playback session: re-evaluate the track hint and never resume.
        trackHintShown = false
        autoResume = false
        castHandoffStopped = false
        prefetchedResumeId = null
        lastKnownDurationMs = 0L
        uhdEscalated = false
        playbackErrorAttempts = 0
        softwareFallbackAttempted = false
        crossDecoderRescueAttempted = false
        recoveryInProgress = false
        resetRoutingForCurrentItem()
        val initialRoute = activeVodRoute
        // Give each auto-next episode a fresh MediaPlayer/listener. Besides
        // restoring the configured decoder after a prior software fallback, this
        // prevents a late duplicate EndReached from the old Media from advancing
        // two episodes at once.
        rebuildRoute(resumeAt = 0L, targetRoute = initialRoute)
        showControls()
    }

    private fun startTimers() {
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.post(progressRunnable)
        handler.postDelayed(saveRunnable, SAVE_INTERVAL_MS)
    }

    // ---- Controls -------------------------------------------------------

    private fun togglePlayPause() {
        if (!foreground) return
        when {
            castHandoffStopped -> {
                // The Cast receiver owns playback and the provider connection.
                // A transiently suspended Cast control channel reports
                // isConnected=false even while the receiver keeps fetching media.
                // Only CastController's definitive end/failure callbacks may
                // return ownership to this Activity.
                Unit
            }
            playbackEnded -> {
                playbackEnded = false
                completionHandled = false
                completionGeneration++
                playbackErrorAttempts = 0
                softwareFallbackAttempted = false
                crossDecoderRescueAttempted = false
                recoveryInProgress = false
                resetRoutingForCurrentItem()
                rebuildRoute(resumeAt = 0L, targetRoute = activeVodRoute)
            }
            activeIsPlaying() -> {
                if (isMedia3Route()) {
                    media3VodEngine?.pause()
                    handler.removeCallbacks(stablePlaybackRunnable)
                    playbackSession.setPlaying(false, activePositionMs())
                    binding.playPauseButton.setImageResource(R.drawable.ic_play)
                    binding.playPauseButton.contentDescription = getString(R.string.detail_play)
                    setControlsVisible(true)
                } else {
                    postBoundedOwnerCommand(
                        label = "pause",
                        recoverAsPaused = true,
                    ) { player -> player.pause() }
                }
            }
            else -> {
                pauseAfterBackgroundRestore = false
                if (isMedia3Route()) {
                    media3VodEngine?.resume()
                    val resumedAtMs = activePositionMs()
                    resetVideoLivenessWatch(resumedAtMs)
                    playbackSession.setPlaying(true, resumedAtMs)
                    binding.playPauseButton.setImageResource(R.drawable.ic_pause)
                    binding.playPauseButton.contentDescription = getString(R.string.player_pause)
                    scheduleHideControls()
                } else {
                    postBoundedOwnerCommand(label = "resume") { player -> player.play() }
                }
            }
        }
    }

    private fun seekBy(deltaMs: Long) {
        if (!foreground) return
        if (castHandoffStopped) return
        val length = activeDurationMs()
        if (length <= 0L) return
        val target = (activePositionMs() + deltaMs).coerceIn(0L, length)
        requestSeek(target)
        binding.seekBar.progress = target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        binding.currentTime.text = formatTime(target)
    }

    private fun requestSeek(targetMs: Long) {
        if (castHandoffStopped) return
        val target = targetMs.coerceAtLeast(0L)
        playbackEnded = false
        completionHandled = false
        completionGeneration++
        pendingSeekMs = target
        backgroundResumePosition = target
        resetStallWatch(target)
        dispatchSeekCommand(target)
    }

    private fun dispatchSeekCommand(target: Long, attempt: Int = 0) {
        if (isMedia3Route()) {
            media3VodEngine?.seekTo(target)
            if (pendingSeekMs == target) pendingSeekMs = 0L
            return
        }
        val posted = postBoundedOwnerCommand(
            label = "seek",
            onCompletedMain = {
                if (pendingSeekMs == target) pendingSeekMs = 0L
            },
        ) { player ->
            player.setTime(target)
        }
        // A lifecycle/start command may briefly own the handle. Coalesce repeated
        // seeks around the latest target and retry only while this target is current.
        if (!posted && playbackStarted && pendingSeekMs == target && attempt < 25) {
            handler.postDelayed(
                { dispatchSeekCommand(target, attempt + 1) },
                NATIVE_EVENT_RETRY_MS,
            )
        }
    }

    private fun cycleAspect() {
        if (castHandoffStopped) return
        aspect = aspect.next()
        applyAspect()
        val selected = aspect
        val store = settings
        aspectSaveJob?.cancel()
        aspectSaveJob = ServiceLocator.appScope.launch { store.setAspectRatio(selected) }
    }

    /**
     * Maps the app's [AspectRatio] onto libVLC's aspect-ratio / scale model.
     * FILL stretches to the container by using its own w:h as the display ratio;
     * ZOOM crops to fill by scaling up using the current video dimensions.
     */
    private fun applyAspect() {
        if (isMedia3Route()) {
            media3VodEngine?.setAspectMode(asVodAspectMode(aspect))
            return
        }
        val selected = aspect
        val containerWidth = binding.videoContainer.width
        val containerHeight = binding.videoContainer.height
        postBoundedOwnerCommand(label = "aspect ratio") { player ->
            applyAspectNative(player, selected, containerWidth, containerHeight)
        }
    }

    private fun applyAspectNative(
        mp: MediaPlayer,
        selected: AspectRatio,
        containerWidth: Int,
        containerHeight: Int,
    ) {
        when (selected) {
            AspectRatio.ORIGINAL -> {
                mp.setAspectRatio(null)
                mp.setScale(0f)
            }
            AspectRatio.RATIO_16_9 -> {
                mp.setAspectRatio("16:9")
                mp.setScale(0f)
            }
            AspectRatio.RATIO_4_3 -> {
                mp.setAspectRatio("4:3")
                mp.setScale(0f)
            }
            AspectRatio.FILL -> {
                if (containerWidth > 0 && containerHeight > 0) {
                    mp.setAspectRatio("$containerWidth:$containerHeight")
                } else {
                    mp.setAspectRatio(null)
                }
                mp.setScale(0f)
            }
            AspectRatio.ZOOM -> {
                mp.setAspectRatio(null)
                mp.setScale(zoomScale(mp, containerWidth, containerHeight))
            }
        }
    }

    /** Scale factor that crops the video to fill the container, keeping its AR. */
    private fun zoomScale(mp: MediaPlayer, containerWidth: Int, containerHeight: Int): Float {
        val track = mp.currentVideoTrack ?: return 0f
        val vw = track.width
        val vh = track.height
        if (vw <= 0 || vh <= 0 || containerWidth <= 0 || containerHeight <= 0) return 0f
        // Absolute source-to-container scale on the larger axis crops only the
        // excess edge while preserving the source aspect ratio.
        val fill = maxOf(
            containerWidth.toFloat() / vw,
            containerHeight.toFloat() / vh,
        )
        return fill.takeIf { it > 0f } ?: 0f
    }

    // ---- Track selection ------------------------------------------------

    private fun showTrackMenu(isAudio: Boolean) {
        if (castHandoffStopped) return
        if (isMedia3Route()) {
            showMedia3TrackMenu(isAudio)
            return
        }
        val mp = currentIdlePlayer() ?: return
        val tracks = if (isAudio) mp.audioTracks else mp.spuTracks

        val labels = mutableListOf<String>()
        val ids = mutableListOf<Int>()
        // Always offer an explicit "Off" for subtitles so it can be disabled even
        // when the media doesn't expose a disable track of its own.
        if (!isAudio) {
            labels.add(getString(R.string.subtitles_off))
            ids.add(-1)
        }
        tracks?.forEach { desc ->
            // VLC may expose a synthetic "Disable" row for both track types. Do
            // not let an audio-language menu accidentally become a mute switch;
            // subtitles get our explicit, localized Off row above.
            if (desc.id != -1) {
                labels.add(desc.name)
                ids.add(desc.id)
            }
        }
        if (labels.isEmpty()) return

        val current = if (isAudio) mp.audioTrack else mp.spuTrack
        val titleRes = if (isAudio) R.string.audio_track else R.string.subtitle_track
        val options = labels.indices.map { i ->
            PlayerDialogs.Option(labels[i], ids[i] == current)
        }
        val sourceButton = if (isAudio) binding.audioButton else binding.subtitleButton
        activeDialog = PlayerDialogs.showOptions(this, getString(titleRes), options) { which ->
            activeDialog = null
            if (mediaPlayer !== mp || which !in ids.indices) return@showOptions
            val id = ids[which]
            val token = if (!isAudio && id == -1) {
                TRACK_DISABLED
            } else {
                VodTrackPreference.encode(labels[which])
            }
            if (isAudio) {
                postBoundedOwnerCommand(label = "audio track") { player ->
                    player.setAudioTrack(id)
                }
                preferredAudioTrack = token
                val store = settings
                audioPreferenceJob?.cancel()
                audioPreferenceJob =
                    ServiceLocator.appScope.launch { store.setPreferredAudioTrack(token) }
            } else {
                postBoundedOwnerCommand(label = "subtitle track") { player ->
                    player.setSpuTrack(id)
                }
                preferredSubtitleTrack = token
                val store = settings
                subtitlePreferenceJob?.cancel()
                subtitlePreferenceJob =
                    ServiceLocator.appScope.launch { store.setPreferredSubtitleTrack(token) }
            }
        }.apply {
            setOnDismissListener {
                activeDialog = null
                showControls()
                sourceButton.requestFocus()
            }
        }
    }

    private fun showMedia3TrackMenu(isAudio: Boolean) {
        val engine = media3VodEngine ?: return
        val tracks = (if (isAudio) engine.audioTracks() else engine.subtitleTracks())
            .filter(VodTrack::supported)
        if (tracks.isEmpty()) return
        val labels = buildList {
            if (!isAudio) add(getString(R.string.subtitles_off))
            addAll(tracks.map(VodTrack::label))
        }
        val selectedIndex = if (isAudio) {
            tracks.indexOfFirst(VodTrack::selected).coerceAtLeast(0)
        } else {
            val selected = tracks.indexOfFirst(VodTrack::selected)
            if (selected >= 0) selected + 1 else 0
        }
        val titleRes = if (isAudio) R.string.audio_track else R.string.subtitle_track
        val options = labels.mapIndexed { index, label ->
            PlayerDialogs.Option(label, index == selectedIndex)
        }
        val sourceButton = if (isAudio) binding.audioButton else binding.subtitleButton
        activeDialog = PlayerDialogs.showOptions(this, getString(titleRes), options) { which ->
            activeDialog = null
            if (media3VodEngine !== engine || which !in labels.indices) return@showOptions
            if (isAudio) {
                val track = tracks.getOrNull(which) ?: return@showOptions
                engine.selectAudioTrack(track.id)
                val token = VodTrackPreference.encode(track.label)
                preferredAudioTrack = token
                audioPreferenceJob?.cancel()
                audioPreferenceJob = ServiceLocator.appScope.launch {
                    settings.setPreferredAudioTrack(token)
                }
            } else if (which == 0) {
                engine.selectSubtitleTrack(null)
                preferredSubtitleTrack = TRACK_DISABLED
                subtitlePreferenceJob?.cancel()
                subtitlePreferenceJob = ServiceLocator.appScope.launch {
                    settings.setPreferredSubtitleTrack(TRACK_DISABLED)
                }
            } else {
                val track = tracks.getOrNull(which - 1) ?: return@showOptions
                engine.selectSubtitleTrack(track.id)
                val token = VodTrackPreference.encode(track.label)
                preferredSubtitleTrack = token
                subtitlePreferenceJob?.cancel()
                subtitlePreferenceJob = ServiceLocator.appScope.launch {
                    settings.setPreferredSubtitleTrack(token)
                }
            }
        }.apply {
            setOnDismissListener {
                activeDialog = null
                showControls()
                sourceButton.requestFocus()
            }
        }
    }

    /**
     * Re-apply the viewer's last language/subtitle choice when a matching VLC
     * track exists. Tokens are human-readable track names because numeric VLC ids
     * are stream-local and change between files.
     */
    private fun applyPreferredTracks() {
        if (isMedia3Route()) {
            val engine = media3VodEngine ?: return
            applyMedia3PreferencesOnce(
                media3Generation,
                engine.audioTracks(),
                engine.subtitleTracks(),
            )
            return
        }
        val savedAudio = preferredAudioTrack
        val savedSubtitle = preferredSubtitleTrack
        val posted = postBoundedOwnerCommand(label = "preferred tracks") { player ->
            val audioTracks = player.audioTracks?.filter { it.id != -1 }.orEmpty()
            val audioTarget = VodTrackPreference.bestMatchIndex(
                savedAudio,
                audioTracks.map { it.name },
            )?.let(audioTracks::get)?.id
            val subtitleTracks = player.spuTracks?.filter { it.id != -1 }.orEmpty()
            val subtitleTarget = when (savedSubtitle) {
                null, "" -> null
                TRACK_DISABLED -> -1
                else -> VodTrackPreference.bestMatchIndex(
                    savedSubtitle,
                    subtitleTracks.map { it.name },
                )?.let(subtitleTracks::get)?.id
            }
            val currentAudio = player.audioTrack
            val currentSubtitle = player.spuTrack
            if (audioTarget != null && currentAudio != audioTarget) {
                player.setAudioTrack(audioTarget)
            }
            if (subtitleTarget != null && currentSubtitle != subtitleTarget) {
                player.setSpuTrack(subtitleTarget)
            }
        }
        if (!posted && playbackStarted && !isMedia3Route()) {
            handler.removeCallbacks(preferredTrackRunnable)
            handler.postDelayed(preferredTrackRunnable, NATIVE_EVENT_RETRY_MS)
        }
    }

    // ---- Track hint banner ---------------------------------------------

    /**
     * If the content offers more than one audio language and/or any subtitle
     * track, surface a short informational banner so the viewer knows they can
     * switch languages or enable subtitles from the player controls.
     */
    private fun showTrackHintIfAvailable() {
        if (trackHintShown) return
        val audioCount: Int
        val subtitleCount: Int
        if (isMedia3Route()) {
            val engine = media3VodEngine ?: return
            audioCount = engine.audioTracks().count(VodTrack::supported)
            subtitleCount = engine.subtitleTracks().count(VodTrack::supported)
        } else {
            val mp = currentIdlePlayer() ?: return
            // Count selectable tracks, ignoring VLC's own "Disable" entry (id -1).
            audioCount = mp.audioTracks?.count { it.id != -1 } ?: 0
            subtitleCount = mp.spuTracks?.count { it.id != -1 } ?: 0
        }
        val multiAudio = audioCount >= 2
        val hasSubtitles = subtitleCount >= 1
        if (!multiAudio && !hasSubtitles) return

        trackHintShown = true
        val (iconRes, messageRes) = when {
            multiAudio && hasSubtitles ->
                R.drawable.ic_subtitles to R.string.track_hint_audio_subtitle
            multiAudio -> R.drawable.ic_audiotrack to R.string.track_hint_audio
            else -> R.drawable.ic_subtitles to R.string.track_hint_subtitle
        }
        binding.trackHintIcon.setImageResource(iconRes)
        binding.trackHintMessage.setText(messageRes)
        showTrackHintBanner()
    }

    private fun showTrackHintBanner() {
        val banner = binding.trackHintBanner
        handler.removeCallbacks(hideTrackHintRunnable)
        banner.animate().cancel()
        banner.alpha = 0f
        banner.visibility = View.VISIBLE
        banner.animate().alpha(1f).setDuration(250L).start()
        handler.postDelayed(hideTrackHintRunnable, TRACK_HINT_MS)
    }

    // ---- Sleep timer ----------------------------------------------------

    private fun showSleepDialog() {
        val options = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = options.map { min ->
            if (min == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_minutes, min)
        }
        activeDialog = PlayerDialogs.showOptions(
            this,
            getString(R.string.sleep_timer),
            labels.mapIndexed { index, label ->
                PlayerDialogs.Option(label, options[index] == sleepTimer.minutes)
            },
        ) { which ->
            activeDialog = null
            val min = options[which]
            sleepTimer.set(min)
            val msg = if (min == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_set, min)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }.apply {
            setOnDismissListener {
                activeDialog = null
                showControls()
                binding.sleepButton.requestFocus()
            }
        }
    }

    // ---- Progress / resume ---------------------------------------------

    private fun updateDuration() {
        val duration = activeDurationMs()
        if (duration <= 0L) return
        lastKnownDurationMs = duration
        val seekMax = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (binding.seekBar.max != seekMax) binding.seekBar.max = seekMax
        binding.totalTime.text = formatTime(duration)
    }

    private fun updateProgress() {
        if (!activeEngineExists()) return
        if (userSeeking) return
        refreshVlcPlaybackSnapshot()
        if (binding.seekBar.max <= 0) updateDuration()
        val position = activePositionMs()
        binding.seekBar.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        binding.currentTime.text = formatTime(position)
        detectPlaybackStall(position)
    }

    private fun persistResume(durable: Boolean = false) {
        val meta = resumeMeta ?: return
        val position = bestResumePosition(activePositionMs())
        val duration = maxOf(
            activeDurationMs(),
            lastKnownDurationMs,
        ).coerceAtLeast(0L)
        if (duration <= 0L) return
        lastKnownDurationMs = duration
        // Exiting can destroy lifecycleScope before Room commits. A final
        // lifecycle save therefore uses the app scope; periodic saves remain
        // tied to this screen.
        val scope = if (durable) ServiceLocator.appScope else lifecycleScope
        // Keep writes ordered: a slower older periodic write must never land
        // after the final onStop snapshot and move Continue Watching backwards.
        resumeSaveJob?.cancel()
        val repository = repo
        val playbackProfileId = activeProfileId
        resumeSaveJob = scope.launch {
            repository.saveResumeForProfile(
                playbackProfileId,
                meta,
                position,
                duration,
            )
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val locale = Locale.getDefault()
        return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s)
        else String.format(locale, "%02d:%02d", m, s)
    }

    // ---- Lifecycle ------------------------------------------------------

    override fun onStart() {
        super.onStart()
        foreground = true
        playbackSession.setActive(true)
        if (
            !castHandoffStopped &&
            ProviderConnectionSafety.newConnectionAllowed &&
            !playbackSession.requestAudioFocus()
        ) {
            pauseAfterBackgroundRestore = true
        }
        if (
            castHandoffStopped &&
            castLocalRestorePending
        ) {
            resumeAfterBackground = false
            resumeAfterCast()
        }
        if (resumeAfterBackground && !resumeChoicePending) {
            resumeAfterBackground = false
            try {
                preparePlayback(backgroundResumePosition)
            } catch (error: Throwable) {
                PlaybackLog.log(this, "VOD", "foreground restore failed: ${error.javaClass.simpleName}")
                showTerminalError()
            }
        }
        binding.titleText.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            NowPlaying.set(this, it, nowKind)
        }
    }

    override fun onStop() {
        val resourceToken = playbackResourceToken
        playbackResourceToken = null
        var resourceReleaseDeferred = false
        playbackSession.setActive(false)
        if (binding.nextEpisodeOverlay.visibility == View.VISIBLE) {
            dismissNextEpisodePrompt(restoreFocus = false)
        }
        val stoppingOwner = nativeOwner
        val ownerBusy = stoppingOwner?.activeOperation?.get() != null
        val connectionMayBeOpen =
            playbackRequested && !resumeChoicePending && !playbackEnded
        val media3Teardown = VodConnectionLifecyclePolicy.onStop(
            isFinishing = isFinishing,
            engineExists = isMedia3Route() && media3VodEngine != null,
            connectionMayBeOpen = connectionMayBeOpen,
            releaseOnBackground = true,
        )
        val vlcTeardown = VodConnectionLifecyclePolicy.onStop(
            isFinishing = isFinishing,
            engineExists = stoppingOwner != null,
            connectionMayBeOpen = connectionMayBeOpen,
            releaseOnBackground = true,
        )
        backgroundResumePosition =
            if (ownerBusy) bestResumePosition(null)
            else bestResumePosition(activePositionMs())
        val wasPlaying =
            if (ownerBusy) {
                playbackStarted || binding.bufferingOverlay.visibility == View.VISIBLE
            } else {
                activeIsPlaying() ||
                    binding.bufferingOverlay.visibility == View.VISIBLE
            }
        if (ownerBusy && stoppingOwner != null) {
            // The active worker may be inside vendor JNI when this screen leaves.
            // Register its exact owner before clearing Activity fields; that same
            // retired worker resolves the token after full native release.
            markOwnerConnectionUncertain(stoppingOwner)
            quarantineOwner(stoppingOwner, "backgrounded during native operation")
        } else {
            invalidateEventSession()
        }
        if (!playbackEnded) persistResume(durable = true)
        playbackSession.setPlaying(false, backgroundResumePosition)
        playbackSession.abandonAudioFocus()
        finishQoe(PlaybackEndReason.BACKGROUND)
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(stablePlaybackRunnable)
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(trackHintRunnable)
        handler.removeCallbacks(hideTrackHintRunnable)
        handler.removeCallbacks(preferredTrackRunnable)
        handler.removeCallbacks(uhdCheckRunnable)
        resetSurfaceValidation()
        if (binding.trackHintBanner.visibility == View.VISIBLE) {
            trackHintShown = false
            binding.trackHintBanner.animate().cancel()
            binding.trackHintBanner.visibility = View.GONE
        }
        foreground = false
        // A built MediaPlayer does not imply playback was requested: while the
        // resume prompt is open there is deliberately no socket to restore.
        val terminalErrorVisible = binding.errorOverlay.visibility == View.VISIBLE
        resumeAfterBackground =
            playbackRequested &&
                !resumeChoicePending &&
                !playbackEnded &&
                !castHandoffStopped &&
                !terminalErrorVisible
        pauseAfterBackgroundRestore = resumeAfterBackground && !wasPlaying
        // Close the subscription connection in the background. Incrementing the
        // sequence also cancels a queued prepare that has not opened yet.
        playbackOpsSeq.incrementAndGet()
        providerStartSeq.incrementAndGet()
        when (media3Teardown) {
            VodConnectionLifecyclePolicy.Teardown.RELEASE -> {
                media3VodEngine?.setListener(null)
                media3VodEngine?.release()
                media3VodEngine = null
                media3Generation = VodEngine.NO_GENERATION
            }
            VodConnectionLifecyclePolicy.Teardown.STOP -> {
                media3VodEngine?.stop()
                media3Generation = VodEngine.NO_GENERATION
            }
            VodConnectionLifecyclePolicy.Teardown.NONE -> Unit
        }
        if (
            !ownerBusy &&
            stoppingOwner != null &&
            vlcTeardown != VodConnectionLifecyclePolicy.Teardown.NONE
        ) {
            val ownerOperation =
                if (vlcTeardown == VodConnectionLifecyclePolicy.Teardown.RELEASE) {
                    retireIdleOwnerOnMain(stoppingOwner)
                } else {
                    beginOwnerOperation(stoppingOwner)
                }
            if (ownerOperation == null) {
                markOwnerConnectionUncertain(stoppingOwner)
                quarantineOwner(stoppingOwner, "background stop could not claim owner")
                NowPlaying.clear(this)
                PlaybackResourceGovernor.end(resourceToken)
                super.onStop()
                return
            }
            resourceReleaseDeferred = true
            val nativeStopFinished = AtomicBoolean(false)
            fun finishNativeStop() {
                if (nativeStopFinished.compareAndSet(false, true)) {
                    nativeCommandsInFlight.decrementAndGet()
                }
            }
            nativeCommandsInFlight.incrementAndGet()
            ownerOperation.onAbandoned.set(::finishNativeStop)
            val stopUncertaintyToken = markOwnerConnectionUncertain(stoppingOwner)
            VlcOps.postBounded(
                timeoutMs = if (
                    vlcTeardown == VodConnectionLifecyclePolicy.Teardown.RELEASE
                ) {
                    NATIVE_RELEASE_TIMEOUT_MS
                } else {
                    NATIVE_OPERATION_TIMEOUT_MS
                },
                onTimeout = {
                    finishNativeStop()
                    requireOwnerProcessRecovery(stoppingOwner, stopUncertaintyToken)
                    quarantineOwner(stoppingOwner, "background native stop timed out")
                    // A vendor JNI call may never return. Exact-token end keeps
                    // non-playback work from being blocked for the process lifetime;
                    // the eventual worker finally is an idempotent no-op here.
                    PlaybackResourceGovernor.end(resourceToken)
                },
            ) {
                var stopEscalatedToRelease = false
                try {
                    when (vlcTeardown) {
                        VodConnectionLifecyclePolicy.Teardown.RELEASE -> {
                            cleanupOwnerOnCurrentWorker(stoppingOwner)
                        }
                        VodConnectionLifecyclePolicy.Teardown.STOP -> {
                            val stopFailures = VlcOps.runAllBestEffort(
                                { stoppingOwner.mediaPlayer.setEventListener(null) },
                                { stoppingOwner.mediaPlayer.stop() },
                            )
                            stopFailures.forEach { failure ->
                                PlaybackLog.log(
                                    this,
                                    "VOD",
                                    "background stop failed: " +
                                        failure.javaClass.simpleName,
                                )
                            }
                            if (stopFailures.isEmpty()) {
                                resolveOwnerConnectionUncertainty(stoppingOwner)
                            } else {
                                // A failed stop is not reusable. Retire the same
                                // owner on this worker and prove both release
                                // boundaries before allowing another connection.
                                stopEscalatedToRelease = true
                                stoppingOwner.abandoned.set(true)
                                cleanupOwnerOnCurrentWorker(stoppingOwner)
                            }
                        }
                        VodConnectionLifecyclePolicy.Teardown.NONE -> Unit
                    }
                } finally {
                    finishNativeStop()
                    ownerOperation.onAbandoned.set(null)
                    finishOwnerOperationOnWorker(ownerOperation)
                    PlaybackResourceGovernor.end(resourceToken)
                }
                if (stopEscalatedToRelease) {
                    handler.post {
                        if (nativeOwner === stoppingOwner) {
                            quarantineOwner(stoppingOwner, "background native stop failed")
                            if (foreground && !isFinishing && !isDestroyed) {
                                // preparePlayback owns the provider gate and only
                                // constructs a new native backend after closure is
                                // proven or controlled process recovery completes.
                                preparePlayback(backgroundResumePosition)
                            }
                        }
                    }
                }
            }
        }
        NowPlaying.clear(this)
        if (!resourceReleaseDeferred) {
            PlaybackResourceGovernor.end(resourceToken)
        }
        super.onStop()
    }

    override fun onDestroy() {
        PlaybackResourceGovernor.end(playbackResourceToken)
        playbackResourceToken = null
        if (binding.nextEpisodeOverlay.visibility == View.VISIBLE) {
            dismissNextEpisodePrompt(restoreFocus = false)
        }
        activeDialog?.setOnDismissListener(null)
        activeDialog?.dismiss()
        activeDialog = null
        debugBinder?.release()
        sleepTimer.release()
        castController.detach()
        finishQoe(PlaybackEndReason.USER_STOP)
        playbackSession.release()
        media3VodEngine?.setListener(null)
        media3VodEngine?.release()
        media3VodEngine = null
        media3Generation = VodEngine.NO_GENERATION
        resetSurfaceValidation()
        invalidateEventSession()
        handler.removeCallbacksAndMessages(null)
        foreground = false
        playbackOpsSeq.incrementAndGet()
        providerStartSeq.incrementAndGet()
        val destroyingOwner = nativeOwner
        if (destroyingOwner != null) {
            val ownerOperation = retireIdleOwnerOnMain(destroyingOwner)
            if (ownerOperation == null) {
                // An earlier timed/stopping operation owns these handles. Never
                // enqueue a release for them on the replacement worker.
                markOwnerConnectionUncertain(destroyingOwner)
                quarantineOwner(destroyingOwner, "destroyed during native operation")
                super.onDestroy()
                return
            }
            val destroyUncertaintyToken = markOwnerConnectionUncertain(destroyingOwner)
            VlcOps.postBounded(
                timeoutMs = NATIVE_RELEASE_TIMEOUT_MS,
                onTimeout = {
                    requireOwnerProcessRecovery(destroyingOwner, destroyUncertaintyToken)
                    quarantineOwner(destroyingOwner, "destroy native release timed out")
                },
            ) {
                try {
                    cleanupOwnerOnCurrentWorker(destroyingOwner)
                } finally {
                    finishOwnerOperationOnWorker(ownerOperation)
                }
            }
        }
        super.onDestroy()
    }
}
