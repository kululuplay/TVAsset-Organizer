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

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.SleepTimer
import com.iptv.player.player.StreamInfo
import com.iptv.player.player.VlcOps
import com.iptv.player.util.AppInfo
import com.iptv.player.util.DebugOverlayBinder
import com.iptv.player.util.NowPlaying
import com.iptv.player.util.PlaybackLog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VodPlayerActivity : BaseActivity() {

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
        // UHD/4K threshold (QHD 1440p+). Software decode can't keep up with 4K at
        // 80–100 Mbps, so above this we rebuild on the hardware decoder.
        private const val UHD_MIN_HEIGHT = 1440
        private const val UHD_MIN_WIDTH = 2560
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
        private const val TRACK_DISABLED = "__off__"
    }

    private lateinit var binding: ActivityVodPlayerBinding
    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var debugBinder: DebugOverlayBinder? = null

    private var streamUrl: String? = null
    private var resumeId: String? = null
    private var resumeMeta: ResumeMeta? = null
    private var autoResume = false

    private var aspect: AspectRatio = AspectRatio.ORIGINAL
    private var userSeeking = false

    private var decoderMode = DecoderMode.AUTO
    private var bufferMode = BufferMode.NORMAL
    private var allowPassthrough = false

    // Mirrors live TV: when Decoder is SOFTWARE, MediaCodec is disabled.
    private var forceSoftware = false
    // Set once we rebuild on hardware for a UHD/4K stream, so it happens at most once.
    private var uhdEscalated = false

    // VOD error recovery: retry the current decode path once, then (AUTO decoder
    // only) rebuild once on VLC software before showing the terminal Retry UI.
    private var playbackErrorAttempts = 0
    private var softwareFallbackAttempted = false
    private var resumeAfterBackground = false
    private var pauseAfterBackgroundRestore = false
    private var backgroundResumePosition = 0L
    @Volatile private var foreground = false
    private val playbackOpsSeq = AtomicLong(0L)
    private val stablePlaybackRunnable = Runnable {
        playbackErrorAttempts = 0
        softwareFallbackAttempted = false
    }

    // Label for the live-device "now playing" panel (Film / Dizi / Geri Sar).
    private var nowKind = "Film"

    // Position to jump to once playback actually starts (resume support). VLC only
    // accepts a seek after the first Playing event, so we defer it until then.
    private var pendingSeekMs = 0L

    // Show the multi-language / subtitle hint at most once per playback session.
    private var trackHintShown = false
    private val trackHintRunnable = Runnable { showTrackHintIfAvailable() }
    private var preferredAudioTrack: String? = null
    private var preferredSubtitleTrack: String? = null
    private val preferredTrackRunnable = Runnable { applyPreferredTracks() }

    // Controls (top/transport/bottom bars) auto-hide after a few seconds idle and
    // reappear on any key press or touch. Bars start visible (see the layout).
    private var controlsVisible = true
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }

    private val sleepTimer = SleepTimer { finish() }
    private val castController by lazy {
        CastController(this) {
            val url = streamUrl ?: return@CastController null
            CastController.CastMedia(url, binding.titleText.text.toString(), null, isLive = false)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
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
        val track = mediaPlayer?.currentVideoTrack ?: return null
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
        if (!isSystemKey && event.action == KeyEvent.ACTION_DOWN && !controlsVisible) {
            // First press only reveals the controls; don't also trigger the
            // underlying button so the user gets a chance to see/aim.
            showControls()
            return true
        }
        if (!isSystemKey) showControls()
        return super.dispatchKeyEvent(event)
    }

    private fun setupControls() {
        binding.backButton.setOnClickListener { finish() }
        binding.retryButton.setOnClickListener {
            binding.errorOverlay.visibility = View.GONE
            binding.bufferingIndicator.visibility = View.VISIBLE
            playbackErrorAttempts = 0
            softwareFallbackAttempted = false
            preparePlayback((mediaPlayer?.time ?: pendingSeekMs).coerceAtLeast(0L))
        }
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
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                mediaPlayer?.setTime(sb?.progress?.toLong() ?: 0L)
            }
        })
    }

    // ---- Controls auto-hide --------------------------------------------

    /** Reveal the controls (if hidden) and (re)start the idle auto-hide timer. */
    private fun showControls() {
        if (!controlsVisible) setControlsVisible(true)
        scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
    }

    private fun setControlsVisible(visible: Boolean) {
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
        if (visible) binding.playPauseButton.requestFocus()
    }

    private fun initPlayer() {
        // Read every shared playback setting BEFORE building libVLC. Player Engine
        // itself is a Live-TV routing choice; on-demand content intentionally keeps
        // VLC for broad MKV/AVI/DTS/subtitle support, while decoder, buffer, audio
        // passthrough and aspect apply consistently to both paths.
        lifecycleScope.launch {
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
        }
    }

    private fun buildPlayer() {
        // Mirror live TV (VlcPlayerEngine): the same anti-stutter decode tuning so
        // movies/series play as smoothly as channels. clock-jitter/synchro off stops
        // VLC stalling to "catch up" on irregular timestamps; skiploopfilter + fast
        // cut decode load on weak boxes; HW unless the Decoder setting forces SW.
        val cachingMs = bufferMode.networkCachingMs
        val options = arrayListOf(
            "--network-caching=$cachingMs",
            "--file-caching=$cachingMs",
            // Irregular PCR/timestamps make VLC stall to resync; disabling the
            // jitter/synchro guards is the main fix for stutter.
            "--clock-jitter=0",
            "--clock-synchro=0",
            // Hardware decode unless the user's Decoder setting forces software.
            if (forceSoftware) "--avcodec-hw=none" else "--avcodec-hw=any",
            // MediaCodec/OMX direct rendering LEFT ON (libVLC default) on the
            // hardware path so the Amlogic decoder renders straight onto the
            // SurfaceView underlay (its native hardware-video path). The DR-off +
            // android_display vout path could not colour-convert NV12 on the Xiaomi
            // compositor and stayed GREEN (RV16/RV32 chroma overrides had zero
            // effect). forceSoftware uses avcodec-hw=none so DR is irrelevant there.
            // Cut decode load on weak boxes WITHOUT wrecking quality: "all" dropped
            // the H.264/H.265 deblocking filter on every frame, so block errors
            // propagated -> macroblocking/"rain" breakup with audio still fine.
            // "nonref" keeps deblocking on reference frames (no error build-up) and
            // only skips disposable non-reference frames. Mirrors VlcPlayerEngine.
            "--avcodec-skiploopfilter=nonref",
            "--avcodec-fast",
            "--http-reconnect",
            "--http-user-agent=${AppInfo.USER_AGENT}"
        )
        // Match the global passthrough setting. Default OFF decodes Dolby/DTS to
        // stereo PCM; explicit opt-in leaves the encoded bitstream available to an
        // AV receiver/soundbar.
        if (!allowPassthrough) {
            options.add("--no-spdif")
            options.add("--stereo-mode=1")
        }
        // Software-path deinterlace only: opaque HW buffers can't be filtered and
        // the Amlogic HW decoder deinterlaces natively; software decode emits raw
        // frames that bob CAN deinterlace.
        if (forceSoftware) {
            options.add("--deinterlace=1")
            options.add("--deinterlace-mode=bob")
        }
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

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    binding.bufferingIndicator.visibility =
                        if (event.buffering < 100f) View.VISIBLE else View.GONE
                }
                MediaPlayer.Event.Playing -> {
                    // 4K on software stutters; rebuild on hardware before doing
                    // anything else with this (about-to-be-released) player.
                    if (maybeEscalateForUhd()) return@setEventListener
                    binding.bufferingIndicator.visibility = View.GONE
                    binding.errorOverlay.visibility = View.GONE
                    binding.playPauseButton.setImageResource(R.drawable.ic_pause)
                    binding.playPauseButton.contentDescription = getString(R.string.player_pause)
                    // Restart the idle timer once real playback begins.
                    scheduleHideControls()
                    // Apply the deferred resume seek once, now that VLC is ready.
                    if (pendingSeekMs > 0L) {
                        mp.setTime(pendingSeekMs)
                        pendingSeekMs = 0L
                    }
                    val restoreAsPaused = pauseAfterBackgroundRestore
                    if (restoreAsPaused) {
                        pauseAfterBackgroundRestore = false
                        mp.pause()
                    }
                    applyAspect()
                    updateDuration()
                    // VLC parses audio/subtitle tracks shortly after playback
                    // begins; schedule a debounced check to surface the hint.
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
                }
                MediaPlayer.Event.Paused -> {
                    binding.playPauseButton.setImageResource(R.drawable.ic_play)
                    binding.playPauseButton.contentDescription = getString(R.string.detail_play)
                }
                MediaPlayer.Event.Stopped -> {
                    binding.playPauseButton.setImageResource(R.drawable.ic_play)
                    binding.playPauseButton.contentDescription = getString(R.string.detail_play)
                }
                MediaPlayer.Event.EndReached -> {
                    binding.playPauseButton.setImageResource(R.drawable.ic_play)
                    binding.playPauseButton.contentDescription = getString(R.string.detail_play)
                    persistResume()
                    onPlaybackFinished()
                }
                MediaPlayer.Event.EncounteredError -> {
                    handler.post { handlePlaybackError() }
                }
                MediaPlayer.Event.LengthChanged -> updateDuration()
            }
        }

        libVlc = vlc
        mediaPlayer = mp
        videoLayout = layout
    }

    private fun promptResume(positionMs: Long) {
        PlayerDialogs.showResume(
            activity = this,
            positionText = formatTime(positionMs),
            onResume = { preparePlayback(positionMs) },
            onStartOver = { preparePlayback(0L) },
        )
    }

    private fun preparePlayback(positionMs: Long) {
        // initPlayer may finish during onCreate, before onStart marks the screen
        // foreground. Defer opening the subscription socket until STARTED; the
        // same path also safely absorbs a delayed retry that fires while away.
        if (!foreground) {
            backgroundResumePosition = positionMs
            resumeAfterBackground = true
            return
        }
        val vlc = libVlc ?: return
        val mp = mediaPlayer ?: return
        val url = streamUrl ?: return
        pendingSeekMs = positionMs
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingIndicator.visibility = View.VISIBLE
        val cachingMs = bufferMode.networkCachingMs
        val media = Media(vlc, android.net.Uri.parse(url)).apply {
            // Hardware decoding unless the global Decoder setting forces software
            // (mirrors VlcPlayerEngine on the live TV path).
            setHWDecoderEnabled(!forceSoftware, !forceSoftware)
            addOption(":network-caching=$cachingMs")
            addOption(":file-caching=$cachingMs")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            if (forceSoftware) addOption(":avcodec-hw=none")
            if (!allowPassthrough) {
                addOption(":no-spdif")
                // Force a 5.1 -> 2.0 stereo downmix so boxes that cannot open a
                // multichannel PCM AudioTrack still get sound.
                addOption(":stereo-mode=1")
            }
            if (forceSoftware) {
                addOption(":deinterlace=1")
                addOption(":deinterlace-mode=bob")
            }
            addOption(":http-reconnect")
            addOption(":http-user-agent=${AppInfo.USER_AGENT}")
        }
        // Start on the shared VLC ops thread: FIFO ordering guarantees any queued
        // teardown (the live player released when this screen opened, or the UHD
        // escalation's old player) has fully closed its connection first
        // (single-connection contract), and the main thread never waits on it.
        val operation = playbackOpsSeq.incrementAndGet()
        VlcOps.post {
            try {
                if (playbackOpsSeq.get() != operation || !foreground) return@post
                mp.stop()
                if (playbackOpsSeq.get() != operation || !foreground) return@post
                mp.media = media
                mp.play()
            } catch (_: Throwable) {
                // VlcOps also protects its worker thread, but this local catch is
                // needed to leave the spinner and enter the normal retry/fallback
                // ladder when a native stop/setMedia/play call itself throws.
                runOnUiThread {
                    if (
                        playbackOpsSeq.get() == operation &&
                        foreground &&
                        !isFinishing &&
                        !isDestroyed
                    ) {
                        handlePlaybackError()
                    }
                }
            } finally {
                media.release()
            }
        }
        startTimers()
    }

    /**
     * Recover an on-demand failure without leaving the viewer on a black screen.
     * One same-path retry handles a transient CDN/network drop. If the decoder is
     * AUTO and VLC hardware still fails, one software rebuild handles a genuine
     * codec/MediaCodec problem. Explicit Hardware/Software choices are respected.
     */
    private fun handlePlaybackError() {
        if (!foreground || isFinishing || isDestroyed) return
        handler.removeCallbacks(stablePlaybackRunnable)
        binding.bufferingIndicator.visibility = View.GONE
        val resumeAt = (mediaPlayer?.time ?: pendingSeekMs).coerceAtLeast(0L)
        PlaybackLog.log(
            this,
            "VOD",
            "playback error attempt=$playbackErrorAttempts " +
                "decoder=$decoderMode software=$forceSoftware",
        )

        when {
            playbackErrorAttempts == 0 -> {
                playbackErrorAttempts++
                binding.bufferingIndicator.visibility = View.VISIBLE
                handler.postDelayed(
                    { if (!isFinishing && !isDestroyed) preparePlayback(resumeAt) },
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
                binding.errorMessage.setText(R.string.error_cannot_play_content)
                binding.errorOverlay.visibility = View.VISIBLE
                binding.retryButton.requestFocus()
            }
        }
    }

    /**
     * If we're software-decoding a UHD/4K stream (which no TV-box CPU can handle at
     * 80–100 Mbps), rebuild the player on the hardware decoder and resume from the
     * current position. Returns true when an escalation was kicked off so the caller
     * stops touching the about-to-be-released player. Mirrors VlcPlayerEngine's
     * software→hardware UHD escalation on the live TV path.
     */
    private fun maybeEscalateForUhd(): Boolean {
        // Explicit "Software only" must remain software. AUTO may recover UHD by
        // moving back to hardware, where 4K decoding is realistically possible.
        if (decoderMode != DecoderMode.AUTO || uhdEscalated || !forceSoftware) return false
        val track = mediaPlayer?.currentVideoTrack ?: return false
        if (track.height < UHD_MIN_HEIGHT && track.width < UHD_MIN_WIDTH) return false
        uhdEscalated = true
        val resumeAt = (mediaPlayer?.time ?: 0L).coerceAtLeast(0L)
        handler.post { escalateToHardware(resumeAt) }
        return true
    }

    /** Tears down the software player and rebuilds it on hardware, resuming at [resumeAt]. */
    private fun escalateToHardware(resumeAt: Long) {
        rebuildPlayer(resumeAt = resumeAt, useSoftware = false)
    }

    /**
     * Rebuild libVLC sequentially on the shared native-ops thread. This is used by
     * decoder fallback/escalation and preserves the one-subscription-connection
     * contract: old stop/release completes before the new Media starts.
     */
    private fun rebuildPlayer(resumeAt: Long, useSoftware: Boolean) {
        handler.removeCallbacks(stablePlaybackRunnable)
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingIndicator.visibility = View.VISIBLE
        val mp = mediaPlayer
        val vlc = libVlc
        mp?.setEventListener(null)
        mp?.detachViews()
        mediaPlayer = null
        libVlc = null
        videoLayout?.let { binding.videoContainer.removeView(it) }
        videoLayout = null
        forceSoftware = useSoftware
        val operation = playbackOpsSeq.incrementAndGet()
        VlcOps.post {
            // Native teardown must be best-effort per call. A provider/socket can
            // make stop() throw; release still has to run and the replacement UI
            // must still be scheduled or the viewer is stranded on a spinner.
            runCatching { mp?.stop() }
            runCatching { mp?.release() }
            runCatching { vlc?.release() }
            runOnUiThread {
                if (
                    isDestroyed ||
                    isFinishing ||
                    !foreground ||
                    playbackOpsSeq.get() != operation
                ) return@runOnUiThread
                buildPlayer()
                preparePlayback(resumeAt)
            }
        }
    }

    /**
     * On playback completion: mark the finished item watched (so grid/episode
     * badges update) and, for series episodes, auto-continue with the next
     * episode — same season first, then the first episode of the next season —
     * so binge-watching is hands-free.
     */
    private fun onPlaybackFinished() {
        val meta = resumeMeta ?: return
        lifecycleScope.launch {
            try {
                repo.markWatched(meta.contentId, meta.kind.raw, meta.seriesId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
            if (meta.kind != ResumeKind.EPISODE) return@launch
            val seriesId = meta.seriesId ?: return@launch
            val next = nextEpisode(seriesId, meta.seasonNumber, meta.episodeNumber) ?: return@launch
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
        val ordered = seasons.sortedBy { it.seasonNumber }
        val current = ordered.firstOrNull { it.seasonNumber == season }
        val eps = current?.episodes?.sortedBy { it.episodeNumber }.orEmpty()
        val idx = eps.indexOfFirst { it.episodeNumber == episode }
        if (idx >= 0 && idx + 1 < eps.size) return eps[idx + 1]
        // Roll over to the first episode of the next season.
        return ordered.firstOrNull { it.seasonNumber > season }
            ?.episodes?.minByOrNull { it.episodeNumber }
    }

    /** Swaps the player onto [next], reusing the live engine, and plays from start. */
    private fun startEpisode(next: Episode, prev: ResumeMeta) {
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
        uhdEscalated = false
        playbackErrorAttempts = 0
        softwareFallbackAttempted = false
        forceSoftware = decoderMode == DecoderMode.SOFTWARE
        preparePlayback(0L)
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
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) mp.pause() else mp.play()
    }

    private fun seekBy(deltaMs: Long) {
        val mp = mediaPlayer ?: return
        val length = mp.length.coerceAtLeast(0L)
        mp.setTime((mp.time + deltaMs).coerceIn(0L, length))
    }

    private fun cycleAspect() {
        aspect = aspect.next()
        applyAspect()
        lifecycleScope.launch { settings.setAspectRatio(aspect) }
    }

    /**
     * Maps the app's [AspectRatio] onto libVLC's aspect-ratio / scale model.
     * FILL stretches to the container by using its own w:h as the display ratio;
     * ZOOM crops to fill by scaling up using the current video dimensions.
     */
    private fun applyAspect() {
        val mp = mediaPlayer ?: return
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
        val mp = mediaPlayer ?: return 0f
        val track = mp.currentVideoTrack ?: return 0f
        val vw = track.width
        val vh = track.height
        val cw = binding.videoContainer.width
        val ch = binding.videoContainer.height
        if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return 0f
        // Best-fit scale that VLC would use, then take the larger axis to crop.
        val fit = minOf(cw.toFloat() / vw, ch.toFloat() / vh)
        val fill = maxOf(cw.toFloat() / vw, ch.toFloat() / vh)
        if (fit <= 0f) return 0f
        return fill / fit
    }

    // ---- Track selection ------------------------------------------------

    private fun showTrackMenu(isAudio: Boolean) {
        val mp = mediaPlayer ?: return
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
            // VLC's own "Disable" entry (id -1) is already covered by our Off item.
            if (isAudio || desc.id != -1) {
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
        PlayerDialogs.showOptions(this, getString(titleRes), options) { which ->
            val id = ids[which]
            val token = if (!isAudio && id == -1) TRACK_DISABLED else labels[which]
            if (isAudio) {
                mp.setAudioTrack(id)
                preferredAudioTrack = token
                lifecycleScope.launch { settings.setPreferredAudioTrack(token) }
            } else {
                mp.setSpuTrack(id)
                preferredSubtitleTrack = token
                lifecycleScope.launch { settings.setPreferredSubtitleTrack(token) }
            }
        }
    }

    /**
     * Re-apply the viewer's last language/subtitle choice when a matching VLC
     * track exists. Tokens are human-readable track names because numeric VLC ids
     * are stream-local and change between files.
     */
    private fun applyPreferredTracks() {
        val mp = mediaPlayer ?: return
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
        val mp = mediaPlayer ?: return
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
        banner.animate().cancel()
        banner.alpha = 0f
        banner.visibility = View.VISIBLE
        banner.animate().alpha(1f).setDuration(250L).start()
        handler.postDelayed({
            banner.animate().alpha(0f).setDuration(250L)
                .withEndAction { banner.visibility = View.GONE }
                .start()
        }, TRACK_HINT_MS)
    }

    // ---- Sleep timer ----------------------------------------------------

    private fun showSleepDialog() {
        val options = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = options.map { min ->
            if (min == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_minutes, min)
        }
        PlayerDialogs.showOptions(
            this,
            getString(R.string.sleep_timer),
            labels.map { PlayerDialogs.Option(it) },
        ) { which ->
            val min = options[which]
            sleepTimer.set(min)
            val msg = if (min == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_set, min)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Progress / resume ---------------------------------------------

    private fun updateDuration() {
        val mp = mediaPlayer ?: return
        val duration = mp.length.coerceAtLeast(0L)
        if (duration <= 0L) return
        if (binding.seekBar.max != duration.toInt()) binding.seekBar.max = duration.toInt()
        binding.totalTime.text = formatTime(duration)
    }

    private fun updateProgress() {
        val mp = mediaPlayer ?: return
        if (userSeeking) return
        if (binding.seekBar.max <= 0) updateDuration()
        val position = mp.time.coerceAtLeast(0L)
        binding.seekBar.progress = position.toInt()
        binding.currentTime.text = formatTime(position)
    }

    private fun persistResume() {
        val mp = mediaPlayer ?: return
        val meta = resumeMeta ?: return
        val position = mp.time
        val duration = mp.length.coerceAtLeast(0L)
        if (duration <= 0L) return
        lifecycleScope.launch { repo.saveResume(meta, position, duration) }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    // ---- Lifecycle ------------------------------------------------------

    override fun onStart() {
        super.onStart()
        foreground = true
        if (resumeAfterBackground) {
            resumeAfterBackground = false
            if (mediaPlayer == null) buildPlayer()
            preparePlayback(backgroundResumePosition)
        }
        binding.titleText.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            NowPlaying.set(this, it, nowKind)
        }
    }

    override fun onStop() {
        super.onStop()
        persistResume()
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(stablePlaybackRunnable)
        foreground = false
        val mp = mediaPlayer
        backgroundResumePosition = (mp?.time ?: pendingSeekMs).coerceAtLeast(0L)
        val wasPlaying = mp?.isPlaying == true ||
            binding.bufferingIndicator.visibility == View.VISIBLE
        resumeAfterBackground = mp != null
        pauseAfterBackgroundRestore = mp != null && !wasPlaying
        // Close the subscription connection in the background. Incrementing the
        // sequence also cancels a queued prepare that has not opened yet.
        playbackOpsSeq.incrementAndGet()
        if (mp != null) VlcOps.post { runCatching { mp.stop() } }
        NowPlaying.clear(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        debugBinder?.release()
        sleepTimer.release()
        castController.detach()
        handler.removeCallbacksAndMessages(null)
        foreground = false
        playbackOpsSeq.incrementAndGet()
        // ANR fix: stop()/release() block on a stalled network — backing out of a
        // stuck VOD must not freeze the UI. Events + views are cut on main, the
        // blocking native teardown runs on the shared VLC ops thread.
        val mp = mediaPlayer
        val vlc = libVlc
        mp?.setEventListener(null)
        mp?.detachViews()
        mediaPlayer = null
        libVlc = null
        videoLayout = null
        if (mp != null || vlc != null) {
            VlcOps.post {
                runCatching { mp?.stop() }
                runCatching { mp?.release() }
                runCatching { vlc?.release() }
            }
        }
    }
}
