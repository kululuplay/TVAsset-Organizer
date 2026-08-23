/*
 * PlayerActivity.kt
 * Full-screen playback with D-pad controls:
 *   UP / DOWN   -> next / previous channel (zapping)
 *   OK (center) -> show channel info overlay briefly
 *   LONG-press OK -> toggle favorite
 *   BACK        -> close overlay first, then exit
 * Uses PlayerController which runs ExoPlayer first and falls back to libVLC.
 */
package com.iptv.player.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import coil.load
import com.iptv.player.R
import com.iptv.player.cast.CastController
import com.iptv.player.cast.ProviderConnectionSafety
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.NowNext
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.databinding.ActivityPlayerBinding
import com.iptv.player.playback.android.PlaybackQoeRuntime
import com.iptv.player.playback.core.PlaybackEndReason
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackResourceGovernor
import com.iptv.player.playback.core.PlaybackResourceToken
import com.iptv.player.playback.core.PlaybackSessionId
import com.iptv.player.player.LiveLoadingOverlayPolicy
import com.iptv.player.player.LivePlaybackQoePolicy
import com.iptv.player.player.LiveStreamUrl
import com.iptv.player.player.LiveSubtitlePreference
import com.iptv.player.player.PlayerController
import com.iptv.player.player.StreamInfo
import com.iptv.player.player.TvPlaybackSession
import com.iptv.player.util.AutoRetryPolicy
import com.iptv.player.util.DebugOverlayBinder
import com.iptv.player.util.NowPlaying
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.SleepTimer
import com.iptv.player.ui.common.isAdult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class PlayerActivity : BaseActivity(), PlayerController.Callback {

    /** Never cover an active live stream with the app idle screensaver. */
    protected override val idleScreensaverEnabledForScreen: Boolean
        get() = false

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        // Scopes up/down zapping to a real category, Favorites or Recent. Null
        // uses all visible live/radio channels.
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        /** When true, up/down zapping stays within the radio list, not Live TV. */
        const val EXTRA_RADIO_MODE = "extra_radio_mode"
        /** Initial channel was already approved by the launching parental gate. */
        const val EXTRA_PARENTAL_AUTHORIZED = "extra_parental_authorized"
        /** Adult category approved on Home; avoids prompting again for each CH+/-. */
        const val EXTRA_AUTHORIZED_CATEGORY_ID = "extra_authorized_category_id"
        private const val OVERLAY_TIMEOUT = 4000L
        private const val LONG_PRESS_MS = 600L
        /**
         * Settle window for CH+/CH- zapping. Each press advances the channel and
         * updates the overlay instantly, but the actual stream only starts after the
         * user pauses this long — so rapid taps don't churn mp.stop()/mp.play() on
         * the engine (the libVLC/Amlogic stop-restart overlap that froze the picture).
         */
        private const val ZAP_DEBOUNCE_MS = 280L
    }

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(this)[PlayerViewModel::class.java]
    }

    private lateinit var controller: PlayerController
    private var debugBinder: DebugOverlayBinder? = null
    private var currentChannel: Channel? = null
    // Debounced CH+/CH- zapping: the target channel the user is scrolling toward,
    // started only once they settle (see requestZap / ZAP_DEBOUNCE_MS).
    private val zapHandler = Handler(Looper.getMainLooper())
    private var pendingZapChannel: Channel? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    // Post-fatal automatic retry (see onFatalError / AutoRetryPolicy): countdown
    // ticks + the pending retry live on this handler; the attempt index picks the
    // next backoff delay and only resets on success or an explicit user action.
    private val autoRetryHandler = Handler(Looper.getMainLooper())
    private var autoRetryAttempt = 0
    private val confirmPress = RemoteConfirmPress()
    private var epgJob: Job? = null
    private val unlockedAdultChannels = mutableSetOf<String>()
    private var unlockedAdultCategoryId: String? = null
    private var pinPromptInFlight = false

    /** User's preferred live container; applied to the URL at play time. */
    private var streamFormat: StreamFormat = StreamFormat.TS
    private var resolvedStreamFormat: StreamFormat? = null

    /** Platform media buttons, audio focus and noisy-output ownership. */
    private lateinit var playbackSession: TvPlaybackSession
    private var localPlaybackRequested = false
    private var localStreamSubmitted = false
    private var castOwnsPlayback = false
    private var castLoadPending = false
    private var suppressConnectedCastReloadOnce = false
    private var pendingLocalRestartAfterCast = false

    /** Opaque, URL/title-free QoE session for the current fullscreen channel. */
    private var qoeSessionId: PlaybackSessionId? = null
    private val loadingOverlayPolicy = LiveLoadingOverlayPolicy()
    private var playbackResourceToken: PlaybackResourceToken? = null

    /** Diagnostics overlay state (toggled via the menu / INFO key). */
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsVisible = false

    private val sleepTimer = SleepTimer { finish() }
    private val castController by lazy {
        CastController(
            activity = this,
            onCastLoadStarting = { onQuiesced -> onCastLoadStarting(onQuiesced) },
            onCastQuiesceFailed = { onCastQuiesceFailed() },
            onCastLoadFailed = { onCastLoadFailed() },
            mediaProvider = mediaProvider@{
                val channel = currentChannel ?: return@mediaProvider null
                CastController.CastMedia(
                    url = LiveStreamUrl.applyFormat(
                        channel.streamUrl,
                        resolvedStreamFormat ?: streamFormat,
                    ),
                    title = channel.name,
                    imageUrl = channel.logoUrl,
                    isLive = true,
                )
            },
            onCastStarted = { onCastPlaybackStarted() },
            onCastEnded = { onCastPlaybackEnded() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playbackSession = TvPlaybackSession(
            context = this,
            tag = "KULULUPLAY-Live",
            controls = TvPlaybackSession.Controls(
                onPlay = { resumeLocalPlayback() },
                onPause = { pauseLocalPlayback(abandonFocus = false) },
                onToggle = {
                    if (localPlaybackRequested) {
                        pauseLocalPlayback(abandonFocus = false)
                    } else {
                        resumeLocalPlayback()
                    }
                },
                onStop = { pauseLocalPlayback(abandonFocus = true) },
                onNext = { requestZap(+1) },
                onPrevious = { requestZap(-1) },
            ),
        )

        castController.attach()

        // Manual retry after the reconnect window expired: re-open the same
        // channel and reset the reconnect state. An explicit user action also
        // restarts the automatic-retry schedule from scratch.
        binding.retryButton.setOnClickListener {
            if (castOwnsPlayback) {
                castController.reloadCurrentMedia()
                return@setOnClickListener
            }
            cancelAutoRetry(resetAttempts = true)
            binding.errorOverlay.visibility = View.GONE
            binding.bufferingLabel.setText(R.string.buffering)
            binding.bufferingIndicator.visibility = View.VISIBLE
            if (::controller.isInitialized && !castOwnsPlayback) {
                if (prepareLocalPlaybackRequest()) {
                    if (localStreamSubmitted) {
                        beginQoeSessionIfNeeded()
                        controller.retry()
                    } else {
                        currentChannel?.let(::startChannel)
                    }
                }
            }
        }

        // On-screen Debug overlay (engine/stage + live PlaybackLog tail), gated by
        // the Debug setting; separate from the MENU-toggled stats overlay.
        debugBinder = DebugOverlayBinder(binding.debugOverlay) {
            if (::controller.isInitialized) controller.streamInfo() else null
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    ServiceLocator.settings.debugOverlay.collectLatest { debugBinder?.setEnabled(it) }
                } finally {
                    // Leaving STARTED: drop the listener + timer so a backgrounded
                    // player doesn't keep rendering the overlay off-screen.
                    debugBinder?.setEnabled(false)
                }
            }
        }

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        if (channelId == null) {
            finish()
            return
        }
        viewModel.radioMode = intent.getBooleanExtra(EXTRA_RADIO_MODE, false)
        viewModel.categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID)
        unlockedAdultCategoryId = intent.getStringExtra(EXTRA_AUTHORIZED_CATEGORY_ID)

        lifecycleScope.launch {
            streamFormat = viewModel.streamFormat()
            val channel = viewModel.resolveChannel(channelId)
            // Playlist/settings resolution may finish after HOME was pressed. Do
            // not open a PIN dialog or construct a player behind another screen;
            // resume this one-time result only when this Activity is visible again.
            lifecycle.withStarted {
                if (channel == null) {
                    Toast.makeText(
                        this@PlayerActivity,
                        R.string.error_unknown,
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                    return@withStarted
                }
                if (intent.getBooleanExtra(EXTRA_PARENTAL_AUTHORIZED, false)) {
                    unlockedAdultChannels.add(channel.id)
                }
                authorizeInitialChannel(channel)
            }
        }
    }

    /**
     * Defense-in-depth for direct PlayerActivity launches. Normal entry points
     * pass an authorization token after their PIN gate, while any unguarded or
     * future entry point is stopped here before a surface or metadata is shown.
     */
    private fun authorizeInitialChannel(channel: Channel) {
        if (!requiresParentalAuthorization(channel)) {
            initializeChannel(channel)
            return
        }
        pinPromptInFlight = true
        PinLockHelper.guard(
            activity = this,
            isAdult = true,
            onDenied = {
                pinPromptInFlight = false
                finish()
            },
        ) {
            pinPromptInFlight = false
            unlockedAdultChannels.add(channel.id)
            initializeChannel(channel)
        }
    }

    private fun initializeChannel(channel: Channel) {
        lifecycleScope.launch {
            val playback = viewModel.playbackSelection()
            val allowPassthrough = viewModel.audioPassthrough()
            val bufferMode = viewModel.bufferMode()
            val preferredAudioLanguage = viewModel.liveAudioLanguage()
            val preferredSubtitlePreference = viewModel.liveSubtitlePreference()
            // Each settings read can suspend. Gate the actual player creation
            // again so onStop cannot be followed by hidden playback/ghost audio.
            lifecycle.withStarted {
                if (isFinishing || isDestroyed) return@withStarted
                controller = PlayerController(
                    context = this@PlayerActivity,
                    container = binding.videoContainer,
                    mode = playback.player,
                    decoderMode = playback.decoder,
                    allowPassthrough = allowPassthrough,
                    bufferMode = bufferMode,
                    expectsVideo = !viewModel.radioMode,
                    preferredAudioLanguage = preferredAudioLanguage,
                    preferredSubtitlePreference = preferredSubtitlePreference,
                    callback = this@PlayerActivity
                )
                startChannel(channel)
            }
        }
    }

    /**
     * Handle one CH+/CH- press: advance the channel pointer and update the overlay
     * instantly so scrolling feels responsive, but defer the actual stream start
     * until the user settles for [ZAP_DEBOUNCE_MS]. This collapses a burst of taps
     * into a single [startChannel], avoiding the rapid stop/restart that intermittently
     * froze the libVLC decoder on Amlogic boxes.
     */
    private fun requestZap(delta: Int) {
        if (pinPromptInFlight) return
        val target = (if (delta > 0) viewModel.next() else viewModel.previous()) ?: return
        if (requiresParentalAuthorization(target)) {
            zapHandler.removeCallbacksAndMessages(null)
            pendingZapChannel = null
            pinPromptInFlight = true
            PinLockHelper.guard(
                activity = this,
                isAdult = true,
                onDenied = {
                    pinPromptInFlight = false
                    currentChannel?.let { viewModel.selectChannel(it.id) }
                },
            ) {
                pinPromptInFlight = false
                unlockedAdultChannels.add(target.id)
                queueZap(target)
            }
            return
        }
        queueZap(target)
    }

    /** Preview a permitted zap target, then start it after the settle window. */
    private fun queueZap(target: Channel) {
        pendingZapChannel = target
        previewZapOverlay(target)
        zapHandler.removeCallbacksAndMessages(null)
        zapHandler.postDelayed({
            // Guard the lifecycle: don't start a stream if we've left the foreground
            // or are tearing down between the press and this settle.
            if (isFinishing || isDestroyed) return@postDelayed
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@postDelayed
            val ch = pendingZapChannel ?: return@postDelayed
            pendingZapChannel = null
            if (ch.id != currentChannel?.id) startChannel(ch)
        }, ZAP_DEBOUNCE_MS)
    }

    private fun requiresParentalAuthorization(channel: Channel): Boolean =
        channel.isAdult() &&
            channel.id !in unlockedAdultChannels &&
            channel.categoryId != unlockedAdultCategoryId

    /**
     * Lightweight overlay update shown while the user is still scrolling channels:
     * the name/number/category and a placeholder logo, with EPG cleared so no stale
     * programme text lingers. The real logo + EPG load only on settle via [showOverlay].
     */
    private fun previewZapOverlay(channel: Channel) {
        binding.overlayName.text = channel.name
        binding.overlayNumber.text = channel.number?.toString() ?: ""
        binding.overlayCategory.text = channel.categoryName ?: ""
        binding.overlayLogo.setImageDrawable(LogoPlaceholder.forName(this, channel.name))
        epgJob?.cancel()
        clearEpg()
        binding.infoOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({ hideOverlay() }, OVERLAY_TIMEOUT)
    }

    private fun startChannel(channel: Channel) {
        if (playbackResourceToken == null) {
            playbackResourceToken = PlaybackResourceGovernor.begin("live-player")
        }
        finishQoeSession(PlaybackEndReason.REPLACED)
        loadingOverlayPolicy.requireFreshFrame()
        currentChannel = channel
        resolvedStreamFormat = null
        localStreamSubmitted = false
        pendingLocalRestartAfterCast = false
        viewModel.markWatched(channel.id)
        // A new channel cancels any reconnect-failed state from the previous one,
        // including a pending automatic retry aimed at the old channel.
        cancelAutoRetry(resetAttempts = true)
        binding.errorOverlay.visibility = View.GONE
        if (castOwnsPlayback) {
            // The receiver owns the only stream connection. Update it directly;
            // the paused local controller is restarted on this latest channel
            // only after the Cast session really ends.
            castController.reloadCurrentMedia()
            PlaybackResourceGovernor.end(playbackResourceToken)
            playbackResourceToken = null
            showOverlay(channel)
            return
        }
        if (castLoadPending) {
            castController.reloadCurrentMedia()
            PlaybackResourceGovernor.end(playbackResourceToken)
            playbackResourceToken = null
            showOverlay(channel)
            return
        }
        val offerConnectedCast = castController.isConnected && !suppressConnectedCastReloadOnce
        suppressConnectedCastReloadOnce = false
        if (offerConnectedCast) {
            // Activity recreation can outlive a Cast session. Submit only after
            // channel/format state is ready; the two-phase callbacks quiesce local
            // playback before the receiver opens the provider URL.
            castController.reloadCurrentMedia()
            PlaybackResourceGovernor.end(playbackResourceToken)
            playbackResourceToken = null
            showOverlay(channel)
            return
        }
        if (!prepareLocalPlaybackRequest()) {
            PlaybackResourceGovernor.end(playbackResourceToken)
            playbackResourceToken = null
            showOverlay(channel)
            return
        }
        // Per-channel route-memory key (Tier 2 self-healing): the channel id plus
        // the chosen container format, which together determine the stream the
        // decoder sees. No URL/token so it survives credential rotation.
        controller.play(
            LiveStreamUrl.applyFormat(channel.streamUrl, streamFormat),
            routeKey = LiveStreamUrl.routeKey(channel.id, streamFormat, channel.streamUrl),
            transportKey = LiveStreamUrl.transportKey(
                channel.id,
                channel.streamUrl,
                streamFormat,
            ),
        )
        localStreamSubmitted = true
        showOverlay(channel)
        if (statsVisible) refreshStats()
    }

    // ---- D-pad handling -------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isGlobalConfirmKey(keyCode)) {
            // A visible error gives focus to the Retry button. Let the focused
            // Android view process its normal confirm DOWN/UP pair so OK retries
            // playback instead of toggling the player overlay behind it.
            if (binding.retryButton.hasFocus()) {
                // Gamepad A is a player-level confirm but is not guaranteed to
                // synthesize Button.performClick() on every TV framework.
                if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                    confirmPress.onDown(keyCode, event.eventTime, event.repeatCount)
                    return true
                }
                confirmPress.clear()
                return super.onKeyDown(keyCode, event)
            }
            confirmPress.onDown(keyCode, event.eventTime, event.repeatCount)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                // CH+ / UP -> next channel. Ignore auto-repeat (held key) so a long
                // press doesn't runaway-zap; discrete taps are collapsed by requestZap.
                if (event.repeatCount == 0) requestZap(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                // CH- / DOWN -> previous channel (same auto-repeat/debounce handling).
                if (event.repeatCount == 0) requestZap(-1)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                // Held MENU/INFO keys emit repeated DOWN events on many remotes.
                // Open/toggle once per physical press instead of flickering dialogs.
                if (event.repeatCount == 0) showPlayerMenu()
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                if (event.repeatCount == 0) toggleStats()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isGlobalConfirmKey(keyCode)) {
            if (binding.retryButton.hasFocus()) {
                if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                    val release = confirmPress.onUp(
                        keyCode,
                        event.eventTime,
                        canceled = event.isCanceled,
                    ) ?: return true
                    if (!release.canceled) binding.retryButton.performClick()
                    return true
                }
                // If focus moved to Retry while a player-level confirm was held,
                // discard that old press before delegating the release.
                confirmPress.onUp(keyCode, event.eventTime, canceled = true)
                return super.onKeyUp(keyCode, event)
            }
            // Ignore/delegate orphaned or different-key UP events. Previously an
            // UP with no DOWN subtracted from zero and looked like a long press,
            // unexpectedly toggling the current channel's favorite state.
            val release = confirmPress.onUp(keyCode, event.eventTime, event.isCanceled)
                ?: return super.onKeyUp(keyCode, event)
            if (release.canceled) return true
            if (release.heldMs >= LONG_PRESS_MS) {
                // Long-press OK -> toggle favorite.
                currentChannel?.let {
                    viewModel.toggleFavorite(it.id)
                    val newState = !it.isFavorite
                    currentChannel = it.copy(isFavorite = newState)
                    Toast.makeText(
                        this,
                        if (newState) R.string.added_to_favorites else R.string.removed_from_favorites,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                // Short OK -> toggle info overlay.
                if (binding.infoOverlay.visibility == View.VISIBLE) hideOverlay()
                else currentChannel?.let { showOverlay(it) }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Android TV remotes normally emit one of the framework confirm keys; USB
     * numpads and game controllers can instead emit NUMPAD_ENTER / BUTTON_A.
     */
    private fun isGlobalConfirmKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A -> true
        else -> false
    }

    override fun onBackPressed() {
        // Back peels overlays one at a time: stats first, then info, then exit.
        when {
            statsVisible -> hideStats()
            binding.infoOverlay.visibility == View.VISIBLE -> hideOverlay()
            else -> {
                finishQoeSession(PlaybackEndReason.USER_STOP)
                super.onBackPressed()
            }
        }
    }

    // ---- Player menu (MENU key) ----------------------------------------

    private fun showPlayerMenu() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add(getString(R.string.sleep_timer))
        actions.add { showSleepDialog() }

        if (::controller.isInitialized && controller.supportsDelay) {
            labels.add(getString(R.string.audio_delay))
            actions.add { showDelayDialog(audio = true) }
            labels.add(getString(R.string.subtitle_delay))
            actions.add { showDelayDialog(audio = false) }
        }

        if (::controller.isInitialized && controller.audioTracks().isNotEmpty()) {
            labels.add(getString(R.string.audio_track))
            actions.add { showLiveTrackDialog(audio = true) }
        }

        if (::controller.isInitialized && controller.subtitleTracks().isNotEmpty()) {
            labels.add(getString(R.string.subtitle_track))
            actions.add { showLiveTrackDialog(audio = false) }
        }

        if (castController.isAvailable) {
            labels.add(getString(R.string.cast))
            actions.add { castController.onCastButtonClicked() }
        }

        labels.add(getString(R.string.stream_info))
        actions.add { toggleStats() }

        PlayerDialogs.showOptions(
            this,
            getString(R.string.player_menu),
            labels.map { PlayerDialogs.Option(it) },
        ) { which -> actions[which].invoke() }
    }

    /** Engine-agnostic, D-pad friendly live audio/subtitle selector. */
    private fun showLiveTrackDialog(audio: Boolean) {
        if (!::controller.isInitialized) return
        val tracks = if (audio) controller.audioTracks() else controller.subtitleTracks()
        if (audio && tracks.isEmpty()) return
        val options = buildList {
            if (!audio) {
                add(
                    PlayerDialogs.Option(
                        getString(R.string.subtitles_auto),
                        selected = controller.subtitlePreference is LiveSubtitlePreference.Auto,
                    ),
                )
                add(
                    PlayerDialogs.Option(
                        getString(R.string.subtitles_off),
                        selected = controller.subtitlePreference is LiveSubtitlePreference.Off,
                    ),
                )
            }
            tracks.forEach { add(PlayerDialogs.Option(it.label, it.selected)) }
        }
        PlayerDialogs.showOptions(
            this,
            getString(if (audio) R.string.audio_track else R.string.subtitle_track),
            options,
        ) { index ->
            if (!audio && index == 0) {
                if (controller.selectSubtitleAuto()) {
                    lifecycleScope.launch {
                        viewModel.setLiveSubtitlePreference(LiveSubtitlePreference.Auto)
                    }
                }
                return@showOptions
            }
            if (!audio && index == 1) {
                if (controller.selectSubtitleTrack(null)) {
                    lifecycleScope.launch {
                        viewModel.setLiveSubtitlePreference(LiveSubtitlePreference.Off)
                    }
                }
                return@showOptions
            }
            val trackIndex = if (audio) index else index - 2
            val track = tracks.getOrNull(trackIndex) ?: return@showOptions
            val selected = if (audio) {
                controller.selectAudioTrack(track.id)
            } else {
                controller.selectSubtitleTrack(track.id)
            }
            if (selected && track.language != null) {
                lifecycleScope.launch {
                    if (audio) viewModel.setLiveAudioLanguage(track.language)
                    else {
                        LiveSubtitlePreference.Language.from(track.language)?.let {
                            viewModel.setLiveSubtitlePreference(it)
                        }
                    }
                }
            }
        }
    }

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

    private fun showDelayDialog(audio: Boolean) {
        if (!::controller.isInitialized) return
        val steps = (-2000..2000 step 250).toList()
        val current = if (audio) controller.currentAudioDelayMs else controller.currentSubtitleDelayMs
        val checked = steps.indexOf(current.toInt()).coerceAtLeast(0)
        val titleRes = if (audio) R.string.audio_delay else R.string.subtitle_delay
        val options = steps.mapIndexed { i, step ->
            PlayerDialogs.Option(getString(R.string.delay_ms, step), i == checked)
        }
        PlayerDialogs.showOptions(this, getString(titleRes), options) { which ->
            val ms = steps[which].toLong()
            if (audio) controller.setAudioDelay(ms) else controller.setSubtitleDelay(ms)
        }
    }

    // ---- Overlay --------------------------------------------------------

    private fun showOverlay(channel: Channel) {
        binding.overlayName.text = channel.name
        binding.overlayNumber.text = channel.number?.toString() ?: ""
        binding.overlayCategory.text = channel.categoryName ?: ""
        // Report to the live-device ops panel what's playing (cleared in onStop).
        NowPlaying.set(this, channel.name, "Canlı")
        loadEpg(channel)
        val placeholder = LogoPlaceholder.forName(this, channel.name)
        if (channel.logoUrl.isNullOrBlank()) {
            binding.overlayLogo.setImageDrawable(placeholder)
        } else {
            binding.overlayLogo.load(channel.logoUrl) {
                placeholder(placeholder); error(placeholder)
            }
        }
        binding.infoOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({ hideOverlay() }, OVERLAY_TIMEOUT)
    }

    /**
     * Loads and renders a professional EPG block for the current channel: the now
     * playing title with a progress bar (how far the programme has aired), its
     * start / end times and minutes left, plus the up-next title with its start
     * time. Cancels any in-flight load first so rapid zapping can't leave a
     * previous channel's EPG on screen.
     */
    private fun loadEpg(channel: Channel) {
        epgJob?.cancel()
        // Clear + hide immediately so a late callback can never flash stale text.
        clearEpg()
        epgJob = lifecycleScope.launch {
            val nn = ServiceLocator.repository.getNowNext(channel)
            // Ignore a result that arrived after the user already zapped away.
            if (currentChannel?.id != channel.id) return@launch
            bindEpg(nn)
        }
    }

    /** Renders the now/next pair into the overlay. Runs on the main thread. */
    private fun bindEpg(nn: NowNext) {
        val now = nn.now
        if (now != null) {
            val clock = now.startMs..now.stopMs
            val current = System.currentTimeMillis().coerceIn(clock)
            binding.overlayNow.text =
                getString(R.string.now_playing) + ": " + now.title
            binding.overlayStart.text = formatClock(now.startMs)
            binding.overlayEnd.text = formatClock(now.stopMs)
            binding.epgProgress.progress = (now.progressAt(current) * 100).toInt()

            val minsLeft = ((now.stopMs - current) / 60_000L).toInt()
            binding.overlayRemain.text =
                if (minsLeft <= 0) getString(R.string.epg_ending)
                else getString(R.string.epg_min_left, minsLeft)

            binding.overlayNow.visibility = View.VISIBLE
            binding.epgProgress.visibility = View.VISIBLE
            binding.epgTimes.visibility = View.VISIBLE
        }

        val next = nn.next
        if (next != null) {
            binding.overlayNext.text = getString(R.string.up_next) +
                ": " + next.title + "  (" + formatClock(next.startMs) + ")"
            binding.overlayNext.visibility = View.VISIBLE
        }
    }

    /** Hides + clears every EPG view so no stale program text can show. */
    private fun clearEpg() {
        binding.overlayNow.text = ""
        binding.overlayNext.text = ""
        binding.overlayNow.visibility = View.GONE
        binding.overlayNext.visibility = View.GONE
        binding.epgProgress.visibility = View.GONE
        binding.epgTimes.visibility = View.GONE
    }

    /** Locale-aware, 12/24h-respecting clock time (e.g. 20:00 / 8:00 PM). */
    private fun formatClock(ms: Long): String =
        android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(ms))

    private fun hideOverlay() {
        binding.infoOverlay.visibility = View.GONE
        overlayHandler.removeCallbacksAndMessages(null)
    }

    // ---- Controller callbacks ------------------------------------------

    override fun onBuffering() {
        if (castOwnsPlayback || castLoadPending) return
        loadingOverlayPolicy.onBuffering()
        // A playback attempt is underway (auto retry, manual retry or a zap) —
        // stop any pending countdown but keep the attempt count: only a real
        // success (onPlaying/onVideoResumed) forgives past failures, otherwise
        // a failing retry would reset its own backoff into a 15s hammer-loop.
        cancelAutoRetry(resetAttempts = false)
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingLabel.setText(R.string.buffering)
        binding.bufferingIndicator.visibility = View.VISIBLE
        PlaybackQoeRuntime.setRebuffering(qoeSessionId, true)
        if (!castOwnsPlayback && localPlaybackRequested) playbackSession.setPlaying(true)
    }

    override fun onPlaying(engineName: String) {
        if (castOwnsPlayback || castLoadPending) return
        val canHideLoading = loadingOverlayPolicy.shouldHideOnPlaying(
            engineName = engineName,
            expectsVideo = !viewModel.radioMode,
        )
        cancelAutoRetry(resetAttempts = true)
        binding.errorOverlay.visibility = View.GONE
        PlaybackQoeRuntime.markEngine(qoeSessionId, LivePlaybackQoePolicy.engine(engineName))
        PlaybackQoeRuntime.markReady(qoeSessionId)
        PlaybackQoeRuntime.setRebuffering(qoeSessionId, false)
        if (!castOwnsPlayback) {
            localPlaybackRequested = true
            playbackSession.setPlaying(true)
        }
        // Cold starts/restarts remain covered until a fresh verified frame. A
        // normal rebuffer on an already-verified surface may clear from Playing,
        // because many backends do not emit a second first-frame callback.
        if (canHideLoading) {
            if (viewModel.radioMode) PlaybackQoeRuntime.markFirstFrame(qoeSessionId)
            binding.playbackCover.visibility = View.GONE
            binding.bufferingIndicator.visibility = View.GONE
        }
    }

    override fun onPlaybackRestarting() {
        if (castOwnsPlayback || castLoadPending) return
        loadingOverlayPolicy.requireFreshFrame()
        cancelAutoRetry(resetAttempts = false)
        binding.errorOverlay.visibility = View.GONE
        binding.playbackCover.visibility = View.VISIBLE
        binding.bufferingLabel.setText(R.string.buffering)
        binding.bufferingIndicator.visibility = View.VISIBLE
        PlaybackQoeRuntime.setRebuffering(qoeSessionId, true)
    }

    override fun onVideoResumed() {
        if (castOwnsPlayback || castLoadPending) return
        loadingOverlayPolicy.onVideoResumed()
        // First real frame on the (re)attached surface — clear the hand-off cover
        // and any reconnect indicator the moment playback genuinely resumes.
        cancelAutoRetry(resetAttempts = true)
        binding.errorOverlay.visibility = View.GONE
        binding.playbackCover.visibility = View.GONE
        binding.bufferingIndicator.visibility = View.GONE
        PlaybackQoeRuntime.markFirstFrame(qoeSessionId)
        PlaybackQoeRuntime.setRebuffering(qoeSessionId, false)
        if (!castOwnsPlayback) {
            localPlaybackRequested = true
            playbackSession.setPlaying(true)
        }
    }

    override fun onEngineChanged(engineName: String) {
        if (castOwnsPlayback || castLoadPending) return
        loadingOverlayPolicy.onEngineChanged(engineName)
        PlaybackQoeRuntime.markEngine(qoeSessionId, LivePlaybackQoePolicy.engine(engineName))
    }

    override fun onTransportResolved(format: StreamFormat) {
        resolvedStreamFormat = format
        val transport = LivePlaybackQoePolicy.transport(format)
        if (qoeSessionId == null && localPlaybackRequested && !castOwnsPlayback) {
            beginQoeSessionIfNeeded(format)
        } else {
            PlaybackQoeRuntime.markTransport(qoeSessionId, transport)
        }
    }

    override fun onPlaybackFailure(failure: PlaybackFailure) {
        if (castOwnsPlayback || castLoadPending) return
        PlaybackQoeRuntime.recordFailure(qoeSessionId, failure)
    }

    override fun onRetrying(attempt: Int) {
        if (castOwnsPlayback || castLoadPending) return
        loadingOverlayPolicy.requireFreshFrame()
        // Non-blocking "stream not responding, retrying…" indicator over the
        // picture while the controller re-opens the same channel. Cleared
        // automatically by onPlaying / onVideoResumed once playback resumes.
        cancelAutoRetry(resetAttempts = false)
        binding.errorOverlay.visibility = View.GONE
        binding.playbackCover.visibility = View.VISIBLE
        binding.bufferingLabel.setText(R.string.error_stream_not_responding)
        binding.bufferingIndicator.visibility = View.VISIBLE
    }

    override fun onFatalError() {
        if (castOwnsPlayback || castLoadPending) return
        // Reconnect window elapsed with no recovery: replace the spinner with a
        // clear message and a focusable manual-retry button, then keep retrying
        // on our own growing schedule so an unattended TV heals by itself once
        // the provider/network comes back.
        binding.playbackCover.visibility = View.VISIBLE
        binding.bufferingIndicator.visibility = View.GONE
        PlaybackQoeRuntime.setRebuffering(qoeSessionId, false)
        playbackSession.setPlaying(false)
        binding.errorMessage.setText(R.string.error_cannot_connect)
        binding.errorOverlay.visibility = View.VISIBLE
        binding.retryButton.requestFocus()
        scheduleAutoRetry()
    }

    // ---- Post-fatal automatic retry --------------------------------------

    /**
     * Arms the next automatic retry per [AutoRetryPolicy] and shows a live
     * countdown on the error overlay. When the schedule is exhausted the
     * overlay switches to a "press retry" message and automatic retrying stops.
     */
    private fun scheduleAutoRetry() {
        autoRetryHandler.removeCallbacksAndMessages(null)
        val delayMs = AutoRetryPolicy.delayForAttempt(autoRetryAttempt)
        if (delayMs == null) {
            binding.errorMessage.setText(R.string.error_auto_retry_exhausted)
            finishQoeSession(PlaybackEndReason.FATAL_FAILURE)
            return
        }
        tickAutoRetryCountdown((delayMs / 1000L).coerceAtLeast(1L))
    }

    /** One-second countdown tick; fires the retry when it reaches zero. */
    private fun tickAutoRetryCountdown(secondsLeft: Long) {
        if (secondsLeft <= 0L) {
            autoRetryAttempt++
            binding.errorOverlay.visibility = View.GONE
            binding.bufferingLabel.setText(R.string.buffering)
            binding.bufferingIndicator.visibility = View.VISIBLE
            if (::controller.isInitialized && !castOwnsPlayback) {
                if (prepareLocalPlaybackRequest()) {
                    if (localStreamSubmitted) {
                        beginQoeSessionIfNeeded()
                        controller.retry()
                    } else {
                        currentChannel?.let(::startChannel)
                    }
                }
            }
            return
        }
        binding.errorMessage.text = getString(R.string.error_auto_retry_countdown, secondsLeft)
        autoRetryHandler.postDelayed({ tickAutoRetryCountdown(secondsLeft - 1) }, 1000L)
    }

    /**
     * Stops any pending automatic retry/countdown. [resetAttempts] only on real
     * success or an explicit user action (manual retry / channel change) — never
     * on a mere new attempt, which would defeat the growing backoff.
     */
    private fun cancelAutoRetry(resetAttempts: Boolean) {
        autoRetryHandler.removeCallbacksAndMessages(null)
        if (resetAttempts) autoRetryAttempt = 0
    }

    // ---- Platform playback ownership + anonymous QoE -------------------

    private fun prepareLocalPlaybackRequest(): Boolean {
        if (castOwnsPlayback || castLoadPending) return false
        if (!ProviderConnectionSafety.newConnectionAllowed) {
            localPlaybackRequested = false
            playbackSession.setPlaying(false)
            binding.playbackCover.visibility = View.VISIBLE
            binding.bufferingIndicator.visibility = View.GONE
            binding.errorMessage.setText(R.string.error_playback_ownership_uncertain)
            binding.errorOverlay.visibility = View.VISIBLE
            binding.retryButton.requestFocus()
            return false
        }
        // Focus is requested before opening/reopening the local stream. TV devices
        // normally grant immediately; the listener still owns transient/permanent
        // loss and noisy-output pauses after this boundary.
        if (!playbackSession.requestAudioFocus()) {
            localPlaybackRequested = false
            playbackSession.setPlaying(false)
            binding.playbackCover.visibility = View.VISIBLE
            binding.bufferingIndicator.visibility = View.GONE
            binding.errorMessage.setText(R.string.error_audio_focus_unavailable)
            binding.errorOverlay.visibility = View.VISIBLE
            binding.retryButton.requestFocus()
            return false
        }
        localPlaybackRequested = true
        playbackSession.setPlaying(true)
        return true
    }

    private fun resumeLocalPlayback() {
        if (
            castOwnsPlayback ||
            castLoadPending ||
            !::controller.isInitialized ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        if (playbackResourceToken == null) {
            playbackResourceToken = PlaybackResourceGovernor.begin("live-player")
        }
        if (!prepareLocalPlaybackRequest()) {
            PlaybackResourceGovernor.end(playbackResourceToken)
            playbackResourceToken = null
            return
        }
        if (!localStreamSubmitted) {
            currentChannel?.let(::startChannel)
            return
        }
        beginQoeSessionIfNeeded()
        controller.resume()
    }

    private fun pauseLocalPlayback(
        abandonFocus: Boolean,
        onQuiesced: () -> Unit = {},
    ) {
        localPlaybackRequested = false
        PlaybackQoeRuntime.setRebuffering(qoeSessionId, false)
        playbackSession.setPlaying(false)
        if (::controller.isInitialized) {
            controller.quiesce { onQuiesced() }
        } else {
            onQuiesced()
        }
        if (abandonFocus) playbackSession.abandonAudioFocus()
    }

    private fun onCastPlaybackStarted() {
        // The receiver accepted the load after the preflight confirmed the local
        // socket was closed. Transfer ownership and finalize the local QoE session.
        val replacingLocalPlayback = !castOwnsPlayback
        castLoadPending = false
        castOwnsPlayback = true
        cancelAutoRetry(resetAttempts = false)
        if (replacingLocalPlayback) finishQoeSession(PlaybackEndReason.REPLACED)
    }

    private fun onCastLoadStarting(onQuiesced: (Boolean) -> Unit) {
        if (castOwnsPlayback) {
            onQuiesced(true)
            return
        }
        if (castLoadPending) return
        castLoadPending = true
        // The receiver is about to open the provider URL. Close the local socket
        // first to preserve single-connection accounts; QoE is finalized only if
        // the remote load actually succeeds.
        localPlaybackRequested = false
        PlaybackQoeRuntime.setRebuffering(qoeSessionId, false)
        playbackSession.setPlaying(false)
        playbackSession.abandonAudioFocus()
        if (::controller.isInitialized) {
            controller.quiesce(onQuiesced)
        } else {
            onQuiesced(true)
        }
    }

    private fun onCastQuiesceFailed() {
        castLoadPending = false
        castOwnsPlayback = false
        suppressConnectedCastReloadOnce = true
        pendingLocalRestartAfterCast = false
        cancelAutoRetry(resetAttempts = false)
        binding.playbackCover.visibility = View.VISIBLE
        binding.bufferingIndicator.visibility = View.GONE
        binding.errorMessage.setText(R.string.error_playback_ownership_uncertain)
        binding.errorOverlay.visibility = View.VISIBLE
        binding.retryButton.requestFocus()
        finishQoeSession(PlaybackEndReason.FATAL_FAILURE)
    }

    private fun onCastLoadFailed() {
        castLoadPending = false
        suppressConnectedCastReloadOnce = true
        if (
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            pendingLocalRestartAfterCast = true
            return
        }
        currentChannel?.let(::startChannel)
    }

    private fun onCastPlaybackEnded() {
        if (!castOwnsPlayback) return
        castOwnsPlayback = false
        castLoadPending = false
        suppressConnectedCastReloadOnce = true
        pendingLocalRestartAfterCast = true
        if (
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        // The user may have changed channels while casting. Start the latest one,
        // not the stale URL retained by the paused local controller.
        currentChannel?.let {
            pendingLocalRestartAfterCast = false
            startChannel(it)
        }
    }

    private fun beginQoeSessionIfNeeded(
        format: StreamFormat = resolvedStreamFormat ?: streamFormat,
    ) {
        if (qoeSessionId != null || castOwnsPlayback || currentChannel == null) return
        val descriptor = LivePlaybackQoePolicy.sessionDescriptor(
            radio = viewModel.radioMode,
            hls = format == StreamFormat.HLS,
        )
        qoeSessionId = PlaybackQoeRuntime.start(
            kind = descriptor.content,
            engine = PlaybackEngineKind.UNKNOWN,
            transport = descriptor.transport,
        )
    }

    private fun finishQoeSession(reason: PlaybackEndReason) {
        val id = qoeSessionId ?: return
        qoeSessionId = null
        PlaybackQoeRuntime.setRebuffering(id, false)
        PlaybackQoeRuntime.finish(id, reason)
    }

    // ---- Lifecycle ------------------------------------------------------

    override fun onStop() {
        val resourceToken = playbackResourceToken
        playbackResourceToken = null
        playbackSession.setActive(false)
        // Never carry a half-press across background/foreground. Some Bluetooth
        // remotes lose the UP event while an Activity is stopping.
        confirmPress.clear()
        super.onStop()
        // Drop any pending debounced zap so it can't start a stream after we've
        // backgrounded (it would re-open playback while not visible). Same for a
        // pending automatic retry — it must never start a stream in the background.
        zapHandler.removeCallbacksAndMessages(null)
        pendingZapChannel = null
        cancelAutoRetry(resetAttempts = false)
        if (!castOwnsPlayback) {
            finishQoeSession(PlaybackEndReason.BACKGROUND)
            if (!castLoadPending) {
                pauseLocalPlayback(abandonFocus = true) {
                    PlaybackResourceGovernor.end(resourceToken)
                }
            } else {
                playbackSession.setPlaying(false)
                playbackSession.abandonAudioFocus()
                PlaybackResourceGovernor.end(resourceToken)
            }
        } else {
            playbackSession.setPlaying(false)
            playbackSession.abandonAudioFocus()
            PlaybackResourceGovernor.end(resourceToken)
        }
        NowPlaying.clear(this)
    }

    override fun onStart() {
        super.onStart()
        playbackSession.setActive(true)
        if (::controller.isInitialized && !castOwnsPlayback && !castLoadPending) {
            if (pendingLocalRestartAfterCast) {
                currentChannel?.let {
                    pendingLocalRestartAfterCast = false
                    startChannel(it)
                }
            } else {
                resumeLocalPlayback()
            }
        }
        currentChannel?.let { NowPlaying.set(this, it.name, "Canlı") }
    }

    override fun onDestroy() {
        super.onDestroy()
        PlaybackResourceGovernor.end(playbackResourceToken)
        playbackResourceToken = null
        epgJob?.cancel()
        sleepTimer.release()
        castController.detach()
        finishQoeSession(PlaybackEndReason.APP_SHUTDOWN)
        playbackSession.release()
        zapHandler.removeCallbacksAndMessages(null)
        overlayHandler.removeCallbacksAndMessages(null)
        autoRetryHandler.removeCallbacksAndMessages(null)
        statsHandler.removeCallbacksAndMessages(null)
        debugBinder?.release()
        if (::controller.isInitialized) controller.release()
    }

    // ---- Diagnostics overlay -------------------------------------------

    private fun toggleStats() {
        if (statsVisible) hideStats() else showStats()
    }

    private fun showStats() {
        statsVisible = true
        binding.statsOverlay.visibility = View.VISIBLE
        refreshStats()
    }

    private fun hideStats() {
        statsVisible = false
        statsHandler.removeCallbacksAndMessages(null)
        binding.statsOverlay.visibility = View.GONE
    }

    /** Repaint the overlay every second so resolution/bitrate stay current. */
    private fun refreshStats() {
        if (!statsVisible || !::controller.isInitialized) return
        binding.statsOverlay.text = formatStats(controller.streamInfo())
        statsHandler.removeCallbacksAndMessages(null)
        statsHandler.postDelayed({ refreshStats() }, STATS_REFRESH_MS)
    }

    private fun formatStats(info: StreamInfo?): String {
        if (info == null) return getString(R.string.stream_info_unavailable)
        val res = if (info.width > 0 && info.height > 0) "${info.width}×${info.height}" else DASH
        val fps = if (info.fps > 0f) String.format(Locale.US, "%.0f", info.fps) else DASH
        val bitrate = info.bitrateKbps?.let { "$it kbps" } ?: DASH
        return buildString {
            append(getString(R.string.stream_info)).append('\n')
            append(getString(R.string.stream_info_engine, info.engine)).append('\n')
            append(getString(R.string.stream_info_resolution, res)).append('\n')
            append(getString(R.string.stream_info_fps, fps)).append('\n')
            append(getString(R.string.stream_info_codec, info.codec ?: DASH)).append('\n')
            append(getString(R.string.stream_info_bitrate, bitrate))
        }
    }
}

private const val STATS_REFRESH_MS = 1000L
private const val DASH = "—"
