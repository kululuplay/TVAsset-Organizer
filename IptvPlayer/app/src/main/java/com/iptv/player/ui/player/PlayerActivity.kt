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
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.NowNext
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.databinding.ActivityPlayerBinding
import com.iptv.player.player.LiveStreamUrl
import com.iptv.player.player.PlayerController
import com.iptv.player.player.StreamInfo
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

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        // Scopes up/down zapping to a real category, Favorites or Recent. Null
        // uses all visible live/radio channels.
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        /** When true, up/down zapping stays within the radio list, not Live TV. */
        const val EXTRA_RADIO_MODE = "extra_radio_mode"
        /**
         * When true, adopt the live preview's already-playing PlayerController
         * (parked on ServiceLocator) instead of creating a fresh one — going
         * fullscreen then continues the picture with no reconnect/re-buffer.
         */
        const val EXTRA_ADOPT_PREVIEW = "extra_adopt_preview"
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
        // Safety cap for the preview->fullscreen black-gap cover, in case the
        // engine never emits a fresh video-output event after the surface swap.
        private const val HANDOFF_COVER_TIMEOUT_MS = 6000L
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
    private val handoffCoverHandler = Handler(Looper.getMainLooper())
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

    /** True when this player adopted the live preview's controller (came from Home). */
    private var adoptedPreview = false

    /**
     * Adopted preview controller consumed in onCreate but not yet transferred to
     * [controller] — the channel resolve that precedes the assignment is async, so
     * a fast BACK can destroy this activity mid-setup. Held here so onDestroy can
     * still release it; otherwise the handed-over stream keeps decoding and playing
     * audio in the background with nothing owning it (ghost audio after exit).
     * Cleared once ownership transfers to [controller] (or the adopt path is dropped).
     */
    private var pendingAdopted: PlayerController? = null

    /**
     * True once we've parked the controller for Home to re-adopt on BACK (the
     * reverse hand-off), so onStop/onDestroy must NOT pause or release it — Home
     * now owns the single live connection and keeps the picture going.
     */
    private var handingBack = false

    /** User's preferred live container; applied to the URL at play time. */
    private var streamFormat: StreamFormat = StreamFormat.TS

    /** Diagnostics overlay state (toggled via the menu / INFO key). */
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsVisible = false

    private val sleepTimer = SleepTimer { finish() }
    private val castController by lazy {
        CastController(this) {
            val channel = currentChannel ?: return@CastController null
            CastController.CastMedia(channel.streamUrl, channel.name, channel.logoUrl, isLive = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        castController.attach()

        // Manual retry after the reconnect window expired: re-open the same
        // channel and reset the reconnect state. An explicit user action also
        // restarts the automatic-retry schedule from scratch.
        binding.retryButton.setOnClickListener {
            cancelAutoRetry(resetAttempts = true)
            binding.errorOverlay.visibility = View.GONE
            binding.bufferingLabel.setText(R.string.buffering)
            binding.bufferingIndicator.visibility = View.VISIBLE
            if (::controller.isInitialized) controller.retry()
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

        // Take the handed-over preview controller (if any) immediately so it is
        // never left parked. Null when launched normally (Favorites/number-zap).
        val adopted: PlayerController? =
            if (intent.getBooleanExtra(EXTRA_ADOPT_PREVIEW, false))
                ServiceLocator.consumePendingLiveController() else null
        // Track it until ownership transfers to [controller] below, so a teardown
        // during the async channel resolve still releases the handed-over stream.
        pendingAdopted = adopted

        lifecycleScope.launch {
            streamFormat = viewModel.streamFormat()
            val channel = viewModel.resolveChannel(channelId)
            // Playlist/settings resolution may finish after HOME was pressed. Do
            // not open a PIN dialog or construct a player behind another screen;
            // resume this one-time result only when this Activity is visible again.
            lifecycle.withStarted {
                if (channel == null) {
                    // Release the adopted connection so the hand-off can't orphan it.
                    adopted?.release()
                    pendingAdopted = null
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
                authorizeInitialChannel(channel, adopted)
            }
        }
    }

    /**
     * Defense-in-depth for direct PlayerActivity launches. Normal entry points
     * pass an authorization token after their PIN gate, while any unguarded or
     * future entry point is stopped here before a surface or metadata is shown.
     */
    private fun authorizeInitialChannel(channel: Channel, adopted: PlayerController?) {
        if (!requiresParentalAuthorization(channel)) {
            initializeChannel(channel, adopted)
            return
        }
        pinPromptInFlight = true
        PinLockHelper.guard(
            activity = this,
            isAdult = true,
            onDenied = {
                pinPromptInFlight = false
                adopted?.release()
                pendingAdopted = null
                finish()
            },
        ) {
            pinPromptInFlight = false
            unlockedAdultChannels.add(channel.id)
            initializeChannel(channel, adopted)
        }
    }

    private fun initializeChannel(channel: Channel, adopted: PlayerController?) {
        lifecycleScope.launch {
            if (adopted != null) {
                lifecycle.withStarted {
                    if (isFinishing || isDestroyed) {
                        adopted.release()
                        pendingAdopted = null
                        return@withStarted
                    }
                    // Seamless hand-off: rebind the still-playing engine to this
                    // screen's surface and take ownership.
                    adoptedPreview = true
                    controller = adopted
                    // Ownership transferred to [controller]; onDestroy now releases it
                    // via the ::controller path, so the pending reference is no longer needed.
                    pendingAdopted = null
                    controller.setCallback(this@PlayerActivity)
                    // The hand-off tears down and recreates the video surface, so the
                    // picture goes black for a beat (audio keeps playing) until the new
                    // surface gets its first frame. Cover that gap with the buffering
                    // indicator; onVideoResumed() clears it, with a safety timeout.
                    binding.bufferingIndicator.visibility = View.VISIBLE
                    handoffCoverHandler.postDelayed(
                        { binding.bufferingIndicator.visibility = View.GONE },
                        HANDOFF_COVER_TIMEOUT_MS
                    )
                    controller.rebind(binding.videoContainer)
                    currentChannel = channel
                    viewModel.markWatched(channel.id)
                    showOverlay(channel)
                    if (statsVisible) refreshStats()
                }
            } else {
                val mode: PlayerMode = viewModel.playerMode()
                val decoderMode = viewModel.decoderMode()
                val allowPassthrough = viewModel.audioPassthrough()
                val bufferMode = viewModel.bufferMode()
                // Each settings read can suspend. Gate the actual player creation
                // again so onStop cannot be followed by hidden playback/ghost audio.
                lifecycle.withStarted {
                    if (isFinishing || isDestroyed) return@withStarted
                    controller = PlayerController(
                        context = this@PlayerActivity,
                        container = binding.videoContainer,
                        mode = mode,
                        decoderMode = decoderMode,
                        allowPassthrough = allowPassthrough,
                        bufferMode = bufferMode,
                        callback = this@PlayerActivity
                    )
                    startChannel(channel)
                }
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
            if (isFinishing || isDestroyed || handingBack) return@postDelayed
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
        currentChannel = channel
        viewModel.markWatched(channel.id)
        // A new channel cancels any reconnect-failed state from the previous one,
        // including a pending automatic retry aimed at the old channel.
        cancelAutoRetry(resetAttempts = true)
        binding.errorOverlay.visibility = View.GONE
        // Per-channel route-memory key (Tier 2 self-healing): the channel id plus
        // the chosen container format, which together determine the stream the
        // decoder sees. No URL/token so it survives credential rotation.
        controller.play(
            LiveStreamUrl.applyFormat(channel.streamUrl, streamFormat),
            routeKey = LiveStreamUrl.routeKey(channel.id, streamFormat),
        )
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
    private fun isGlobalConfirmKey(keyCode: Int): Boolean =
        KeyEvent.isConfirmKey(keyCode) ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A

    override fun onBackPressed() {
        // Back peels overlays one at a time: stats first, then info, then exit.
        when {
            statsVisible -> hideStats()
            binding.infoOverlay.visibility == View.VISIBLE -> hideOverlay()
            else -> {
                handBackPreviewIfPossible()
                super.onBackPressed()
            }
        }
    }

    /**
     * Reverse hand-off: when leaving a player that adopted the live preview, park
     * the still-playing controller (plus the current channel id) so Home re-adopts
     * it into the preview surface instead of a dead/closed preview. onStop/onDestroy
     * then skip pause/release because Home now owns the single live connection.
     * Only the BACK exit hands back; sleep-timer/error exits fall through to release.
     */
    private fun handBackPreviewIfPossible() {
        if (!adoptedPreview || !::controller.isInitialized) return
        // Drop any pending debounced zap so it can't start a stream on the
        // controller after we've handed it back to Home.
        zapHandler.removeCallbacksAndMessages(null)
        pendingZapChannel = null
        handingBack = true
        ServiceLocator.handOverLiveController(controller, currentChannel?.id)
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
        // A playback attempt is underway (auto retry, manual retry or a zap) —
        // stop any pending countdown but keep the attempt count: only a real
        // success (onPlaying/onVideoResumed) forgives past failures, otherwise
        // a failing retry would reset its own backoff into a 15s hammer-loop.
        cancelAutoRetry(resetAttempts = false)
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingLabel.setText(R.string.buffering)
        binding.bufferingIndicator.visibility = View.VISIBLE
    }

    override fun onPlaying(engineName: String) {
        cancelAutoRetry(resetAttempts = true)
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingIndicator.visibility = View.GONE
    }

    override fun onVideoResumed() {
        // First real frame on the (re)attached surface — clear the hand-off cover
        // and any reconnect indicator the moment playback genuinely resumes.
        cancelAutoRetry(resetAttempts = true)
        handoffCoverHandler.removeCallbacksAndMessages(null)
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingIndicator.visibility = View.GONE
    }

    override fun onRetrying(attempt: Int) {
        // Non-blocking "stream not responding, retrying…" indicator over the
        // picture while the controller re-opens the same channel. Cleared
        // automatically by onPlaying / onVideoResumed once playback resumes.
        cancelAutoRetry(resetAttempts = false)
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingLabel.setText(R.string.error_stream_not_responding)
        binding.bufferingIndicator.visibility = View.VISIBLE
    }

    override fun onFatalError() {
        // Reconnect window elapsed with no recovery: replace the spinner with a
        // clear message and a focusable manual-retry button, then keep retrying
        // on our own growing schedule so an unattended TV heals by itself once
        // the provider/network comes back.
        binding.bufferingIndicator.visibility = View.GONE
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
            if (::controller.isInitialized) controller.retry()
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

    // ---- Lifecycle ------------------------------------------------------

    override fun onStop() {
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
        // Don't pause when handing the controller back to Home — it must keep
        // playing into the preview surface that Home re-adopted.
        if (!handingBack && ::controller.isInitialized) controller.pause()
        NowPlaying.clear(this)
    }

    override fun onStart() {
        super.onStart()
        if (::controller.isInitialized) controller.resume()
        currentChannel?.let { NowPlaying.set(this, it.name, "Canlı") }
    }

    override fun onDestroy() {
        super.onDestroy()
        epgJob?.cancel()
        sleepTimer.release()
        castController.detach()
        zapHandler.removeCallbacksAndMessages(null)
        overlayHandler.removeCallbacksAndMessages(null)
        handoffCoverHandler.removeCallbacksAndMessages(null)
        autoRetryHandler.removeCallbacksAndMessages(null)
        statsHandler.removeCallbacksAndMessages(null)
        debugBinder?.release()
        // Don't release a controller we've handed back to Home — it owns it now.
        if (!handingBack) {
            if (::controller.isInitialized) controller.release()
            // Adopted preview controller torn down before onCreate finished wiring
            // it to [controller]: release it so the handed-over stream can't keep
            // decoding/playing audio in the background with no owner.
            else pendingAdopted?.release()
        }
        pendingAdopted = null
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
