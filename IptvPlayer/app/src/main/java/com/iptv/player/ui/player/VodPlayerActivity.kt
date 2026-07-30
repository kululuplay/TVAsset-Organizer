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
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.AspectRatio
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.Episode
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.ResumeMeta
import com.iptv.player.databinding.ActivityVodPlayerBinding
import com.iptv.player.player.StreamInfo
import com.iptv.player.player.SurfaceFrameHealthMonitor
import com.iptv.player.player.VlcOps
import com.iptv.player.player.findVideoSurface
import com.iptv.player.player.isVlcBuffering
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.SleepTimer
import com.iptv.player.util.AppInfo
import com.iptv.player.util.DebugOverlayBinder
import com.iptv.player.util.NowPlaying
import com.iptv.player.util.PlaybackLog
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

class VodPlayerActivity : BaseActivity() {

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
        val activeOperation = AtomicReference<OwnerOperation?>(null)
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
        // After playback has started, a half-open HTTP connection can freeze
        // without EOF/error. Position must advance inside this window.
        private const val STALL_TIMEOUT_MS = 30_000L
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
    private var debugBinder: DebugOverlayBinder? = null

    private var streamUrl: String? = null
    private var resumeId: String? = null
    private var resumeMeta: ResumeMeta? = null
    private var autoResume = false
    private var resumeSaveJob: Job? = null
    private var aspectSaveJob: Job? = null
    private var audioPreferenceJob: Job? = null
    private var subtitlePreferenceJob: Job? = null

    private var aspect: AspectRatio = AspectRatio.ORIGINAL
    private var userSeeking = false

    private var decoderMode = DecoderMode.AUTO
    private var bufferMode = BufferMode.NORMAL
    private var allowPassthrough = false

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
    private var resumeChoicePending = false
    @Volatile private var foreground = false
    private val eventSession = AtomicLong(0L)
    private val playbackOpsSeq = AtomicLong(0L)
    private val nativeCommandsInFlight = AtomicInteger(0)
    private val stablePlaybackRunnable = Runnable {
        playbackErrorAttempts = 0
        softwareFallbackAttempted = false
    }
    private var startupTimeoutOperation = 0L
    private var lastObservedPositionMs = -1L
    private var lastPositionAdvanceAtMs = 0L
    private var bufferingSinceMs = 0L
    private val startupTimeoutRunnable = Runnable {
        if (
            foreground &&
            !playbackStarted &&
            playbackOpsSeq.get() == startupTimeoutOperation
        ) {
            PlaybackLog.log(this, "VOD", "startup timeout")
            handlePlaybackError()
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
        CastController(this) {
            val url = streamUrl ?: return@CastController null
            CastController.CastMedia(url, binding.titleText.text.toString(), null, isLive = false)
        }
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

    /**
     * Native VLC reports time=0 while a deferred seek is still waiting for
     * Playing, and can do the same after an error. Never let that transient
     * native value overwrite the user's resume/recovery target.
     */
    private fun bestResumePosition(playerPositionMs: Long? = currentIdlePlayer()?.time): Long =
        maxOf(
            playerPositionMs ?: 0L,
            pendingSeekMs,
            backgroundResumePosition,
        ).coerceAtLeast(0L)

    private fun startSurfaceValidation() {
        if (surfaceValidationStarted || !foreground || !playbackStarted) return
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
        PlaybackLog.log(this, "VOD", "$detail -> bounded decoder recovery")
        resetSurfaceValidation()
        handler.removeCallbacks(uhdCheckRunnable)
        // A sampled solid colour/blank surface is decoder evidence rather than an
        // ordinary CDN error. Treat the configured decoder as the preferred first
        // path, then allow one bounded opposite-decoder rescue.
        handlePlaybackError(RecoveryEvidence.CONFIRMED_VIDEO_FAILURE)
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
    private fun cleanupOwnerOnCurrentWorker(owner: NativePlayerOwner) {
        if (!owner.cleanupClaimed.compareAndSet(false, true)) return
        val failures = VlcOps.runAllBestEffort(
            { owner.mediaPlayer.setEventListener(null) },
            { owner.mediaPlayer.stop() },
            { owner.mediaPlayer.release() },
            { owner.libVlc.release() },
        )
        failures.forEach { failure ->
            PlaybackLog.log(
                this,
                "VOD",
                "owner=${owner.generation} cleanup failed: " +
                    failure.javaClass.simpleName,
            )
        }
        removeOwnerLayoutOnMain(owner)
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
    }

    /**
     * Quarantine never calls a native method. It only invalidates callbacks,
     * removes the owner from all current fields, and hands cleanup ownership to
     * the already-running retired worker. A replacement can then be built without
     * ever touching the timed-out MediaPlayer/LibVLC handles.
     */
    private fun quarantineOwner(owner: NativePlayerOwner, reason: String) {
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
        if (needsSafeCleanup) scheduleQuarantinedOwnerCleanup(owner)
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
        VlcOps.postBounded(
            timeoutMs = NATIVE_RELEASE_TIMEOUT_MS,
            onTimeout = {
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
        autoResume = intent.getBooleanExtra(EXTRA_AUTO_RESUME, false)
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
        val track = currentIdlePlayer()?.currentVideoTrack ?: return null
        val den = track.frameRateDen
        val fps = if (den > 0) track.frameRateNum.toFloat() / den else 0f
        return StreamInfo(
            width = track.width,
            height = track.height,
            fps = fps,
            codec = vodCodecLabel(track.codec),
            bitrateKbps = track.bitrate.takeIf { it > 0 }?.let { it / 1000 },
            engine = "VLC"
        )
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

        if (event.action == KeyEvent.ACTION_DOWN) {
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
                    if (currentIdlePlayer()?.isPlaying != true) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    showControls()
                    if (currentIdlePlayer()?.isPlaying == true) togglePlayPause()
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
        castController.attach()

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentTime.text = formatTime(progress.toLong())
                    // D-pad changes do not invoke onStart/StopTrackingTouch, so
                    // apply those key-driven positions immediately.
                    if (!userSeeking) {
                        val position = progress.toLong()
                        currentIdlePlayer()?.setTime(position)
                        pendingSeekMs = position
                        backgroundResumePosition = position
                        resetStallWatch(position)
                        playbackEnded = false
                        completionHandled = false
                        completionGeneration++
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
                currentIdlePlayer()?.setTime(position)
                pendingSeekMs = position
                backgroundResumePosition = position
                resetStallWatch(position)
                playbackEnded = false
                completionHandled = false
                completionGeneration++
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
        binding.bufferingOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && playbackStarted && bufferingSinceMs == 0L) {
            bufferingSinceMs = SystemClock.uptimeMillis()
        } else if (!visible) {
            bufferingSinceMs = 0L
        }
    }

    private fun resetStallWatch(positionMs: Long = currentIdlePlayer()?.time ?: -1L) {
        lastObservedPositionMs = positionMs
        lastPositionAdvanceAtMs = SystemClock.uptimeMillis()
        bufferingSinceMs = 0L
    }

    private fun detectPlaybackStall(mp: MediaPlayer, positionMs: Long) {
        if (!foreground || !playbackStarted || userSeeking || recoveryInProgress) return
        val now = SystemClock.uptimeMillis()
        if (
            lastObservedPositionMs < 0L ||
            kotlin.math.abs(positionMs - lastObservedPositionMs) >= 250L
        ) {
            lastObservedPositionMs = positionMs
            lastPositionAdvanceAtMs = now
            return
        }
        val expectedToAdvance = mp.isPlaying || bufferingSinceMs > 0L
        if (
            expectedToAdvance &&
            lastPositionAdvanceAtMs > 0L &&
            now - lastPositionAdvanceAtMs >= STALL_TIMEOUT_MS
        ) {
            PlaybackLog.log(this, "VOD", "playback position stalled")
            handlePlaybackError(RecoveryEvidence.CONFIRMED_VIDEO_FAILURE)
        }
    }

    private fun showTerminalError() {
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(uhdCheckRunnable)
        resetSurfaceValidation()
        setBuffering(false)
        binding.errorMessage.setText(R.string.error_cannot_play_content)
        binding.errorOverlay.visibility = View.VISIBLE
        // The overlay is declared before the chrome so normal playback controls
        // can render efficiently. Terminal state must become the topmost focus
        // barrier; Back remains a system key and still exits normally.
        binding.errorOverlay.bringToFront()
        setPlayerChromeFocusable(false)
        setControlsVisible(true)
        binding.retryButton.requestFocus()
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

    /** Retry is an explicit fresh attempt, so return to the configured decoder. */
    private fun retryFromConfiguredDecoder() {
        val resumeAt = bestResumePosition()
        binding.errorOverlay.visibility = View.GONE
        setPlayerChromeFocusable(true)
        binding.playPauseButton.requestFocus()
        setBuffering(true)
        playbackErrorAttempts = 0
        softwareFallbackAttempted = false
        crossDecoderRescueAttempted = false
        recoveryInProgress = false
        uhdEscalated = false
        val useSoftware = decoderMode == DecoderMode.SOFTWARE
        forceSoftware = useSoftware
        if (nativeOwner == null) {
            try {
                buildPlayer()
                preparePlayback(resumeAt)
            } catch (error: Throwable) {
                PlaybackLog.log(this, "VOD", "retry init failed: ${error.javaClass.simpleName}")
                showTerminalError()
            }
        } else {
            // A terminal native error can leave a MediaPlayer in an unusable
            // state. Rebuilding is slower than bare play(), but deterministic.
            rebuildPlayer(resumeAt = resumeAt, useSoftware = useSoftware)
        }
    }

    private fun updateTrackButtons() {
        val mp = currentIdlePlayer()
        val hasAudio = mp?.audioTracks?.any { it.id != -1 } == true
        val hasSubtitles = mp?.spuTracks?.any { it.id != -1 } == true
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
        if (!controlsVisible) setControlsVisible(true)
        scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (
            currentIdlePlayer()?.isPlaying == true &&
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
            (currentIdlePlayer()?.isPlaying != true ||
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
            binding.playPauseButton.requestFocus()
        }
    }

    private fun initPlayer() {
        // Read every shared playback setting BEFORE building libVLC. Player Engine
        // itself is a Live-TV routing choice; on-demand content intentionally keeps
        // VLC for broad MKV/AVI/DTS/subtitle support, while decoder, buffer, audio
        // passthrough and aspect apply consistently to both paths.
        lifecycleScope.launch {
            try {
                decoderMode = settings.getDecoderMode()
                bufferMode = settings.getBufferMode()
                allowPassthrough = settings.getAudioPassthrough()
                preferredAudioTrack = settings.getPreferredAudioTrack()
                preferredSubtitleTrack = settings.getPreferredSubtitleTrack()
                forceSoftware = decoderMode == DecoderMode.SOFTWARE
                aspect = settings.aspectRatio.first()
                buildPlayer()
                val saved = resumeId?.let { repo.getResume(it) } ?: 0L
                when {
                    saved <= 0L -> preparePlayback(0L)
                    // From the Continue Watching rail the user already chose to resume.
                    autoResume -> preparePlayback(saved)
                    else -> promptResume(saved)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                PlaybackLog.log(this@VodPlayerActivity, "VOD", "player init failed: ${error.javaClass.simpleName}")
                showTerminalError()
            }
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
        nativeOwner = NativePlayerOwner(
            generation = nativeOwnerGeneration.incrementAndGet(),
            libVlc = vlc,
            mediaPlayer = mp,
            videoLayout = layout,
        )
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
                )
            }
        }
    }

    private fun dispatchPlayerEventOnMain(
        mp: MediaPlayer,
        session: Long,
        eventType: Int,
        bufferingPercent: Float,
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
    ) {
        when (eventType) {
            MediaPlayer.Event.Buffering -> {
                setBuffering(isVlcBuffering(bufferingPercent))
            }
            MediaPlayer.Event.Playing -> {
                // AUTO's 4K software fallback stutters; return to hardware
                // before touching this about-to-be-released player again.
                if (maybeEscalateForUhd()) return
                playbackStarted = true
                playbackEnded = false
                startTimers()
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
                // Apply the deferred resume seek once, now that VLC is ready.
                if (pendingSeekMs > 0L) {
                    val deferredPosition = pendingSeekMs
                    mp.setTime(deferredPosition)
                    backgroundResumePosition = deferredPosition
                    pendingSeekMs = 0L
                }
                resetStallWatch(mp.time)
                val restoreAsPaused = pauseAfterBackgroundRestore
                if (restoreAsPaused) {
                    pauseAfterBackgroundRestore = false
                    mp.pause()
                }
                applyAspect()
                updateDuration()
                updateTrackButtons()
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
            MediaPlayer.Event.Paused -> {
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
                binding.playPauseButton.setImageResource(R.drawable.ic_play)
                binding.playPauseButton.contentDescription = getString(R.string.detail_play)
                setBuffering(false)
                handler.removeCallbacks(uhdCheckRunnable)
                resetSurfaceValidation()
            }
            MediaPlayer.Event.EndReached -> {
                if (completionHandled) return
                completionHandled = true
                val completionToken = ++completionGeneration
                playbackStarted = false
                playbackEnded = true
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
            MediaPlayer.Event.EncounteredError -> handlePlaybackError()
            MediaPlayer.Event.LengthChanged -> updateDuration()
        }
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

    private fun preparePlayback(positionMs: Long) {
        // Reusing a native player must still create a distinct media-event
        // generation. Invalidate the old listener before any preparation state
        // changes so queued callbacks from the previous URL cannot mutate this
        // session; the replacement listener is installed after owner reservation.
        invalidateEventSession()
        playbackRequested = true
        playbackStarted = false
        playbackEnded = false
        completionHandled = false
        completionGeneration++
        recoveryInProgress = false
        resetStallWatch(positionMs)
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(uhdCheckRunnable)
        resetSurfaceValidation()
        // Keep all deferred-position sources synchronized. This also clears an
        // older, higher background value when the viewer intentionally seeks
        // backwards or starts the next item from zero.
        pendingSeekMs = positionMs.coerceAtLeast(0L)
        backgroundResumePosition = pendingSeekMs
        // initPlayer may finish during onCreate, before onStart marks the screen
        // foreground. Defer opening the subscription socket until STARTED; the
        // same path also safely absorbs a delayed retry that fires while away.
        if (!foreground) {
            resumeAfterBackground = true
            return
        }
        val owner = nativeOwner ?: return
        val ownerOperation = beginOwnerOperation(owner)
        if (ownerOperation == null) {
            // A background stop/start or earlier native command still owns these
            // handles. Quarantine them and continue with a completely fresh owner.
            quarantineOwner(owner, "prepare overlapped an active native operation")
            try {
                buildPlayer()
                preparePlayback(positionMs)
            } catch (error: Throwable) {
                PlaybackLog.log(
                    this,
                    "VOD",
                    "fresh owner prepare failed: ${error.javaClass.simpleName}",
                )
                showTerminalError()
            }
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
                    quarantineOwner(owner, "native prepare timed out")
                    handlePlaybackError()
                }
            },
        ) playbackCommand@{
            var startFailure: Throwable? = null
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
                mp.media = media
                mp.play()
            } catch (error: Throwable) {
                startFailure = error
            } finally {
                runCatching { media.release() }
                finishNativeCommand()
                ownerOperation.onAbandoned.set(null)
                finishOwnerOperationOnWorker(ownerOperation)
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
        if (!foreground || isFinishing || isDestroyed || recoveryInProgress) return
        val errorOwner = nativeOwner
        val ownerBusy = errorOwner?.activeOperation?.get() != null
        val resumeAt =
            if (ownerBusy) bestResumePosition(null)
            else bestResumePosition(errorOwner?.mediaPlayer?.time)
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
            "playback error attempt=$playbackErrorAttempts " +
                "decoder=$decoderMode software=$forceSoftware evidence=$evidence",
        )

        when {
            evidence == RecoveryEvidence.CONFIRMED_VIDEO_FAILURE &&
                !crossDecoderRescueAttempted -> {
                crossDecoderRescueAttempted = true
                playbackErrorAttempts = 0
                val rescueWithSoftware = !forceSoftware
                if (rescueWithSoftware) softwareFallbackAttempted = true
                PlaybackLog.log(
                    this,
                    "VOD",
                    "confirmed video failure -> one-shot " +
                        if (rescueWithSoftware) {
                            "software decoder rescue"
                        } else {
                            "hardware decoder rescue"
                        },
                )
                rebuildPlayer(resumeAt = resumeAt, useSoftware = rescueWithSoftware)
            }
            playbackErrorAttempts == 0 -> {
                playbackErrorAttempts++
                setBuffering(true)
                handler.postDelayed(
                    {
                        if (foreground && !isFinishing && !isDestroyed) {
                            // A native playback error can leave the MediaPlayer in
                            // a poisoned state. Keep the same decoder path, but use
                            // a fresh player so late events cannot affect recovery.
                            rebuildPlayer(resumeAt, useSoftware = forceSoftware)
                        }
                    },
                    VOD_RETRY_DELAY_MS,
                )
            }
            decoderMode == DecoderMode.AUTO &&
                !forceSoftware &&
                !softwareFallbackAttempted -> {
                softwareFallbackAttempted = true
                rebuildPlayer(resumeAt = resumeAt, useSoftware = true)
            }
            else -> {
                showTerminalError()
            }
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
        val track = currentIdlePlayer()?.currentVideoTrack ?: return false
        if (track.height < UHD_MIN_HEIGHT && track.width < UHD_MIN_WIDTH) return false
        uhdEscalated = true
        handler.removeCallbacks(uhdCheckRunnable)
        // Playing arrives before the initial resume seek is applied. Preserve that
        // deferred position when the software player is immediately replaced.
        val resumeAt = bestResumePosition()
        handler.post { escalateToHardware(resumeAt) }
        return true
    }

    /** Returns AUTO's software fallback to hardware, resuming at [resumeAt]. */
    private fun escalateToHardware(resumeAt: Long) {
        if (!foreground || isFinishing || isDestroyed) {
            uhdEscalated = false
            return
        }
        rebuildPlayer(resumeAt = resumeAt, useSoftware = false)
    }

    /**
     * Rebuild libVLC sequentially on the shared native-ops thread. This is used by
     * AUTO decoder fallback/escalation and preserves the one-subscription-
     * connection contract: old stop/release completes before the new Media starts.
     */
    private fun rebuildPlayer(resumeAt: Long, useSoftware: Boolean) {
        handler.removeCallbacks(stablePlaybackRunnable)
        handler.removeCallbacks(startupTimeoutRunnable)
        handler.removeCallbacks(uhdCheckRunnable)
        resetSurfaceValidation()
        binding.errorOverlay.visibility = View.GONE
        setBuffering(true)
        playbackRequested = true
        playbackStarted = false
        backgroundResumePosition = resumeAt
        pendingSeekMs = resumeAt
        forceSoftware = useSoftware
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
            try {
                buildPlayer()
                preparePlayback(resumeAt)
            } catch (error: Throwable) {
                PlaybackLog.log(
                    this,
                    "VOD",
                    "player rebuild failed: ${error.javaClass.simpleName}",
                )
                showTerminalError()
            }
        }

        val retiringOwner = nativeOwner
        if (retiringOwner == null) {
            continueWithFreshOwner()
            return
        }
        val ownerOperation = retireIdleOwnerOnMain(retiringOwner)
        if (ownerOperation == null) {
            // Another worker still owns these handles. It will clean them when it
            // returns; recovery proceeds with a new owner immediately.
            quarantineOwner(retiringOwner, "rebuild overlapped an active operation")
            continueWithFreshOwner()
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
        ServiceLocator.appScope.launch {
            try {
                repository.clearResume(meta.contentId)
                repository.markWatched(meta.contentId, meta.kind.raw, meta.seriesId)
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
            startEpisode(next, meta)
        }
    }

    /** Next episode after (season, episode), rolling into the next season's first. */
    private suspend fun nextEpisode(seriesId: String, season: Int, episode: Int): Episode? {
        val seasons = try {
            repo.getCachedSeasons(seriesId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return null
        }
        if (seasons.isEmpty()) return null
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
        lastKnownDurationMs = 0L
        uhdEscalated = false
        playbackErrorAttempts = 0
        softwareFallbackAttempted = false
        crossDecoderRescueAttempted = false
        recoveryInProgress = false
        val configuredSoftware = decoderMode == DecoderMode.SOFTWARE
        forceSoftware = configuredSoftware
        // Give each auto-next episode a fresh MediaPlayer/listener. Besides
        // restoring the configured decoder after a prior software fallback, this
        // prevents a late duplicate EndReached from the old Media from advancing
        // two episodes at once.
        rebuildPlayer(resumeAt = 0L, useSoftware = configuredSoftware)
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
        val mp = currentIdlePlayer() ?: return
        when {
            playbackEnded -> {
                playbackEnded = false
                completionHandled = false
                completionGeneration++
                playbackErrorAttempts = 0
                softwareFallbackAttempted = false
                crossDecoderRescueAttempted = false
                recoveryInProgress = false
                rebuildPlayer(
                    resumeAt = 0L,
                    useSoftware = decoderMode == DecoderMode.SOFTWARE,
                )
            }
            mp.isPlaying -> mp.pause()
            else -> mp.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val mp = currentIdlePlayer() ?: return
        val length = mp.length.coerceAtLeast(0L)
        if (length <= 0L) return
        val target = (mp.time.coerceAtLeast(0L) + deltaMs).coerceIn(0L, length)
        playbackEnded = false
        completionHandled = false
        completionGeneration++
        pendingSeekMs = target
        backgroundResumePosition = target
        mp.setTime(target)
        resetStallWatch(target)
        binding.seekBar.progress = target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        binding.currentTime.text = formatTime(target)
    }

    private fun cycleAspect() {
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
        val mp = currentIdlePlayer() ?: return
        when (aspect) {
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
                val w = binding.videoContainer.width
                val h = binding.videoContainer.height
                if (w > 0 && h > 0) mp.setAspectRatio("$w:$h") else mp.setAspectRatio(null)
                mp.setScale(0f)
            }
            AspectRatio.ZOOM -> {
                mp.setAspectRatio(null)
                mp.setScale(zoomScale())
            }
        }
    }

    /** Scale factor that crops the video to fill the container, keeping its AR. */
    private fun zoomScale(): Float {
        val mp = currentIdlePlayer() ?: return 0f
        val track = mp.currentVideoTrack ?: return 0f
        val vw = track.width
        val vh = track.height
        val cw = binding.videoContainer.width
        val ch = binding.videoContainer.height
        if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return 0f
        // Absolute source-to-container scale on the larger axis crops only the
        // excess edge while preserving the source aspect ratio.
        val fill = maxOf(cw.toFloat() / vw, ch.toFloat() / vh)
        return fill.takeIf { it > 0f } ?: 0f
    }

    // ---- Track selection ------------------------------------------------

    private fun showTrackMenu(isAudio: Boolean) {
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
            val token = if (!isAudio && id == -1) TRACK_DISABLED else labels[which]
            if (isAudio) {
                mp.setAudioTrack(id)
                preferredAudioTrack = token
                val store = settings
                audioPreferenceJob?.cancel()
                audioPreferenceJob =
                    ServiceLocator.appScope.launch { store.setPreferredAudioTrack(token) }
            } else {
                mp.setSpuTrack(id)
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

    /**
     * Re-apply the viewer's last language/subtitle choice when a matching VLC
     * track exists. Tokens are human-readable track names because numeric VLC ids
     * are stream-local and change between files.
     */
    private fun applyPreferredTracks() {
        val mp = currentIdlePlayer() ?: return
        preferredAudioTrack
            ?.takeIf { it.isNotBlank() && it != TRACK_DISABLED }
            ?.let { token ->
                mp.audioTracks
                    ?.firstOrNull { it.id != -1 && it.name.equals(token, ignoreCase = true) }
                    ?.let { if (mp.audioTrack != it.id) mp.setAudioTrack(it.id) }
            }

        when (val token = preferredSubtitleTrack) {
            null, "" -> Unit
            TRACK_DISABLED -> if (mp.spuTrack != -1) mp.setSpuTrack(-1)
            else -> mp.spuTracks
                ?.firstOrNull { it.id != -1 && it.name.equals(token, ignoreCase = true) }
                ?.let { if (mp.spuTrack != it.id) mp.setSpuTrack(it.id) }
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
        val mp = currentIdlePlayer() ?: return
        // Count selectable tracks, ignoring VLC's own "Disable" entry (id -1).
        val audioCount = mp.audioTracks?.count { it.id != -1 } ?: 0
        val subtitleCount = mp.spuTracks?.count { it.id != -1 } ?: 0
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
        val mp = currentIdlePlayer() ?: return
        val duration = mp.length.coerceAtLeast(0L)
        if (duration <= 0L) return
        lastKnownDurationMs = duration
        val seekMax = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (binding.seekBar.max != seekMax) binding.seekBar.max = seekMax
        binding.totalTime.text = formatTime(duration)
    }

    private fun updateProgress() {
        val mp = currentIdlePlayer() ?: return
        if (userSeeking) return
        if (binding.seekBar.max <= 0) updateDuration()
        val position = mp.time.coerceAtLeast(0L)
        binding.seekBar.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        binding.currentTime.text = formatTime(position)
        detectPlaybackStall(mp, position)
    }

    private fun persistResume(durable: Boolean = false) {
        val meta = resumeMeta ?: return
        val mp = currentIdlePlayer()
        val position = bestResumePosition(mp?.time)
        val duration = maxOf(
            mp?.length ?: 0L,
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
        resumeSaveJob = scope.launch { repository.saveResume(meta, position, duration) }
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
        if (resumeAfterBackground && !resumeChoicePending) {
            resumeAfterBackground = false
            try {
                if (nativeOwner == null) buildPlayer()
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
        val stoppingOwner = nativeOwner
        val ownerBusy = stoppingOwner?.activeOperation?.get() != null
        backgroundResumePosition =
            if (ownerBusy) bestResumePosition(null)
            else bestResumePosition(stoppingOwner?.mediaPlayer?.time)
        val wasPlaying =
            if (ownerBusy) {
                playbackStarted || binding.bufferingOverlay.visibility == View.VISIBLE
            } else {
                stoppingOwner?.mediaPlayer?.isPlaying == true ||
                    binding.bufferingOverlay.visibility == View.VISIBLE
            }
        if (ownerBusy && stoppingOwner != null) {
            quarantineOwner(stoppingOwner, "backgrounded during native operation")
        } else {
            invalidateEventSession()
        }
        if (!playbackEnded) persistResume(durable = true)
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
                !terminalErrorVisible
        pauseAfterBackgroundRestore = resumeAfterBackground && !wasPlaying
        // Close the subscription connection in the background. Incrementing the
        // sequence also cancels a queued prepare that has not opened yet.
        playbackOpsSeq.incrementAndGet()
        if (
            !ownerBusy &&
            stoppingOwner != null &&
            playbackRequested &&
            !resumeChoicePending &&
            !playbackEnded
        ) {
            val ownerOperation = beginOwnerOperation(stoppingOwner)
            if (ownerOperation == null) {
                quarantineOwner(stoppingOwner, "background stop could not claim owner")
                NowPlaying.clear(this)
                super.onStop()
                return
            }
            val nativeStopFinished = AtomicBoolean(false)
            fun finishNativeStop() {
                if (nativeStopFinished.compareAndSet(false, true)) {
                    nativeCommandsInFlight.decrementAndGet()
                }
            }
            nativeCommandsInFlight.incrementAndGet()
            ownerOperation.onAbandoned.set(::finishNativeStop)
            VlcOps.postBounded(
                timeoutMs = NATIVE_OPERATION_TIMEOUT_MS,
                onTimeout = {
                    finishNativeStop()
                    quarantineOwner(stoppingOwner, "background native stop timed out")
                },
            ) {
                var stopFailure: Throwable? = null
                try {
                    val failures = VlcOps.runAllBestEffort(
                        { stoppingOwner.mediaPlayer.setEventListener(null) },
                        { stoppingOwner.mediaPlayer.stop() },
                    )
                    stopFailure = failures.firstOrNull()
                    failures.forEach { failure ->
                        PlaybackLog.log(
                            this,
                            "VOD",
                            "background stop cleanup failed: " +
                                failure.javaClass.simpleName,
                        )
                    }
                } finally {
                    finishNativeStop()
                    ownerOperation.onAbandoned.set(null)
                    finishOwnerOperationOnWorker(ownerOperation)
                }
                if (stopFailure != null) {
                    handler.post {
                        if (nativeOwner === stoppingOwner) {
                            quarantineOwner(stoppingOwner, "background native stop failed")
                            if (foreground && !isFinishing && !isDestroyed) {
                                try {
                                    buildPlayer()
                                    preparePlayback(backgroundResumePosition)
                                } catch (error: Throwable) {
                                    PlaybackLog.log(
                                        this,
                                        "VOD",
                                        "stop-failure restore failed: " +
                                            error.javaClass.simpleName,
                                    )
                                    showTerminalError()
                                }
                            }
                        }
                    }
                }
            }
        }
        NowPlaying.clear(this)
        super.onStop()
    }

    override fun onDestroy() {
        activeDialog?.setOnDismissListener(null)
        activeDialog?.dismiss()
        activeDialog = null
        debugBinder?.release()
        sleepTimer.release()
        castController.detach()
        resetSurfaceValidation()
        invalidateEventSession()
        handler.removeCallbacksAndMessages(null)
        foreground = false
        playbackOpsSeq.incrementAndGet()
        val destroyingOwner = nativeOwner
        if (destroyingOwner != null) {
            val ownerOperation = retireIdleOwnerOnMain(destroyingOwner)
            if (ownerOperation == null) {
                // An earlier timed/stopping operation owns these handles. Never
                // enqueue a release for them on the replacement worker.
                quarantineOwner(destroyingOwner, "destroyed during native operation")
                super.onDestroy()
                return
            }
            VlcOps.postBounded(
                timeoutMs = NATIVE_RELEASE_TIMEOUT_MS,
                onTimeout = {
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
