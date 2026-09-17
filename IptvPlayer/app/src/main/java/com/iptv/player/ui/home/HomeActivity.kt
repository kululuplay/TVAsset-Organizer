/*
 * HomeActivity.kt
 * The 2-pane Android TV Live screen:
 *   left  = a single list that shows categories, and drills into that category's
 *           channels on OK (Back returns to categories).
 *   right = a large channel preview (number + name + current program caption)
 *           on top, and the EPG schedule list for that channel below.
 * Fully D-pad driven. First OK starts preview; second OK expands that same
 * player/surface to fullscreen without reconnecting the live stream.
 */
package com.iptv.player.ui.home

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.cast.CastController
import com.iptv.player.cast.ProviderConnectionSafety
import com.iptv.player.cast.ProviderStartGatePolicy
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.Program
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.databinding.ActivityHomeBinding
import com.iptv.player.playback.android.PlaybackQoeRuntime
import com.iptv.player.playback.android.PlaybackProcessRecovery
import com.iptv.player.playback.core.PlaybackEndReason
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackResourceGovernor
import com.iptv.player.playback.core.PlaybackResourceToken
import com.iptv.player.playback.core.PlaybackSessionId
import com.iptv.player.player.LiveLoadingOverlayPolicy
import com.iptv.player.player.LivePlaybackQoePolicy
import com.iptv.player.player.PlayerController
import com.iptv.player.player.VlcOps
import com.iptv.player.player.TvPlaybackSession
import com.iptv.player.ui.catchup.CatchupActivity
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.ChannelText
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.LowEndUiBudget
import com.iptv.player.ui.common.NewContentPopup
import com.iptv.player.ui.common.NumberZapInputHelper
import com.iptv.player.ui.common.SleepTimer
import com.iptv.player.ui.common.hideSoftKeyboard
import com.iptv.player.ui.common.isAdult
import com.iptv.player.player.LiveStreamUrl
import com.iptv.player.ui.player.RemoteConfirmPress
import com.iptv.player.util.DebugOverlayBinder
import com.iptv.player.util.NewContentNotifier
import com.iptv.player.util.NowPlaying
import com.iptv.player.util.PlaybackRemotePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeActivity : BaseActivity() {

    /**
     * Home normally participates in idle protection, but it also owns the live
     * preview/fullscreen player. Protect only a starting or playing stream: a
     * controller can remain allocated after retries are exhausted, and that
     * static error screen must still be eligible for burn-in protection.
     */
    protected override fun isIdleScreensaverTemporarilyBlocked(): Boolean =
        previewState == LivePreviewPressPolicy.Phase.STARTING ||
            previewState == LivePreviewPressPolicy.Phase.READY

    private lateinit var binding: ActivityHomeBinding

    /** When true, this screen shows radio stations only (launched as the Radio folder). */
    private val radioMode: Boolean by lazy { intent.getBooleanExtra(EXTRA_RADIO_MODE, false) }
    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(
            this,
            HomeViewModel.Factory(application, radioMode),
        )[HomeViewModel::class.java]
    }

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var epgAdapter: ProgramAdapter
    private lateinit var fullscreenGuideAdapter: FullscreenChannelGuideAdapter

    /** Last channels rendered, used by the number-key zap lookup. */
    private var currentChannels: List<Channel> = emptyList()

    /** Guards the one-time initial category selection on first category emission. */
    private var initialSelectionDone = false

    /** When the category changes, the channel list must snap back to its top. */
    private var pendingScrollReset = false

    /** Defers a fast RIGHT/OK until the newly-focused category's rows arrive. */
    private var pendingEnterCategoryId: String? = null
    private var currentChannelSnapshot = HomeViewModel.ChannelSnapshot()

    /** Last category actually selected; used to detect real category changes. */
    private var lastSelectedCategoryId: String? = null

    /** Restores deterministic browse focus after the retry control disappears. */
    private var restoreFocusAfterRetry = false

    /** Prevents rapid GUIDE/RED taps from stacking duplicate Activities. */
    private var externalNavigationInFlight = false

    /** Last channel row that held focus; restored when returning to the channel list. */
    private var lastFocusedChannelId: String? = null

    /** Set on START so the next channel-list emission restores focus to the row we left. */
    private var pendingChannelFocusRestore = false

    /** Adult categories the user has already unlocked this session (avoid re-prompting). */
    private val unlockedCategories = mutableSetOf<String>()

    /** Adult channels surfaced outside their category and unlocked for this session. */
    private val unlockedChannels = mutableSetOf<String>()

    /** Current parental setting; refreshed before each STARTED collection begins. */
    private var adultLockEnabled = false

    /** Live preview player bound to the preview card (one connection at a time). */
    private var previewController: PlayerController? = null
    private var debugBinder: DebugOverlayBinder? = null
    private lateinit var playbackSession: TvPlaybackSession
    private var localPreviewPlaybackRequested = false
    private var previewStreamSubmitted = false
    private var castOwnsPreviewPlayback = false
    private var providerDrainPending = false
    private var castLoadPending = false
    private var suppressConnectedCastReloadOnce = false
    private var previewStreamFormat = StreamFormat.TS
    private var resolvedPreviewStreamFormat: StreamFormat? = null
    private var previewQoeSessionId: PlaybackSessionId? = null

    /** Debounce job so scrolling channels doesn't thrash the single stream socket. */
    private var previewJob: kotlinx.coroutines.Job? = null

    /** Explicit playback state keeps rapid/double OK presses deterministic. */
    private var previewState = LivePreviewPressPolicy.Phase.IDLE
    private val previewLoadingPolicy = LiveLoadingOverlayPolicy()
    private var playbackResourceToken: PlaybackResourceToken? = null

    /**
     * Fullscreen is a layout state of this Activity, not a second player screen.
     * The same controller, SurfaceView, decoder and network connection stay alive.
     */
    private var inlineFullscreen = false
    private var pendingFullscreenChannelId: String? = null

    /**
     * Compatibility playback profile (weak stick). While it plays fullscreen the
     * browse UI drops logo loads + list animations and halves its timer rates.
     */
    private val compatMode: Boolean by lazy {
        runCatching { PlaybackQoeRuntime.devicePlaybackProfile().compatibilityMode }
            .getOrDefault(false)
    }

    /**
     * Whether OK on a channel starts the in-panel preview. When off (weak devices
     * by default, or the user's choice), OK goes straight to fullscreen through the
     * very same controller path and the card shows now/next text instead of video.
     */
    private var livePreviewEnabled = true

    /** Fullscreen OK guide state; browsing it never changes the playing stream. */
    private var fullscreenGuideVisible = false
    private var fullscreenGuideFocusedChannel: Channel? = null
    private var fullscreenGuidePrograms: List<Program> = emptyList()
    private var fullscreenGuideEpgLoaded = false
    private var fullscreenGuideEpgGeneration = 0
    private var fullscreenGuideEpgJob: kotlinx.coroutines.Job? = null

    /** Stable zap scope captured when preview starts; browsing another category cannot replace it. */
    private var previewChannelScope: List<Channel> = emptyList()
    private var previewChannelScopeLabel: String? = null

    /** Prevents repeated OK events from stacking multiple parental dialogs/actions. */
    private val pinGate = HomePinGate(this)

    /** Swallows repeats and matching UP after a pre-dispatch key action. */
    private val consumedUntilUp = ConsumedUntilUpTracker()

    /** Matches fullscreen confirm DOWN/UP so long-OK favorite remains available. */
    private val fullscreenConfirmPress = RemoteConfirmPress()

    private val sleepTimer = SleepTimer { finish() }
    private val castController by lazy {
        CastController(
            activity = this,
            onCastLoadStarting = { onQuiesced ->
                onPreviewCastLoadStarting(onQuiesced)
            },
            onCastQuiesceFailed = { onPreviewCastQuiesceFailed() },
            onCastLoadFailed = { onPreviewCastLoadFailed() },
            onCastStarted = { onPreviewCastStarted() },
            onCastEnded = { onPreviewCastEnded() },
            mediaProvider = mediaProvider@{
                val channel = previewingChannel ?: return@mediaProvider null
                CastController.CastMedia(
                    url = LiveStreamUrl.applyFormat(
                        channel.streamUrl,
                        resolvedPreviewStreamFormat ?: previewStreamFormat,
                    ),
                    title = ChannelText.clean(channel.name),
                    imageUrl = channel.logoUrl,
                    isLive = true,
                )
            },
        )
    }

    /** Fullscreen is a layout state, captured at inflate time (see HomeFullscreenLayout). */
    private lateinit var fullscreenLayout: HomeFullscreenLayout

    private val fullscreenEpgCard by lazy { FullscreenEpgCardBinder(this, binding, timeFmt) }

    private val captionText by lazy {
        HomeCaptionText(nextLabel = getString(R.string.next_label), timeFmt = timeFmt)
    }

    private val rowFocuser = HomeRowFocuser(
        object : HomeRowFocuser.Host {
            override fun stableIdFor(list: RecyclerView, pos: Int): String? = when (list) {
                binding.channelList -> channelAdapter.currentList.getOrNull(pos)?.id
                binding.categoryList -> categoryAdapter.currentList.getOrNull(pos)?.id
                else -> null
            }

            override fun positionFor(list: RecyclerView, stableId: String?): Int? = when (list) {
                binding.channelList ->
                    channelAdapter.currentList.indexOfFirst { it.id == stableId }
                binding.categoryList ->
                    categoryAdapter.currentList.indexOfFirst { it.id == stableId }
                else -> null
            }

            override fun listAllowed(list: RecyclerView): Boolean =
                !(inlineFullscreen && list !== binding.fullscreenGuideList)
        },
    )

    private val fullscreenZap by lazy {
        FullscreenZapDebouncer(
            overlay = binding.fullscreenZapOverlay,
            debounceMs = FULLSCREEN_ZAP_DEBOUNCE_MS,
            host = object : FullscreenZapDebouncer.Host {
                override fun canCommit(): Boolean =
                    inlineFullscreen &&
                        !isFinishing &&
                        !isDestroyed &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

                override fun commit(channel: Channel) {
                    requestChannelPlayback(channel, enterFullscreenWhenReady = true)
                }
            },
        )
    }

    private val fullscreenEpgTimer by lazy {
        FullscreenEpgOverlayTimer(
            overlay = binding.fullscreenEpgOverlay,
            autoHideMs = FULLSCREEN_EPG_AUTO_HIDE_MS,
            host = object : FullscreenEpgOverlayTimer.Host {
                override fun canAutoHide(): Boolean = inlineFullscreen && !fullscreenGuideVisible

                override fun shouldTick(): Boolean =
                    inlineFullscreen &&
                        !isFinishing &&
                        !isDestroyed &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

                override fun onTick(now: Long) {
                    if (binding.fullscreenEpgOverlay.visibility == View.VISIBLE) {
                        if (fullscreenGuideVisible && fullscreenGuideEpgLoaded) {
                            fullscreenEpgCard.bind(fullscreenGuidePrograms, now)
                        } else if (!fullscreenGuideVisible && captionEpgLoaded) {
                            fullscreenEpgCard.bind(captionPrograms, now)
                        }
                    }
                    if (captionEpgLoaded) {
                        maybeRefreshExpiredCaptionEpg(now)
                    }
                }

                override val tickIntervalMs: Long
                    get() = LowEndUiBudget.refreshIntervalMs(FULLSCREEN_EPG_TICK_MS, compatMode)
            },
        )
    }

    private val inlinePlayerMenu by lazy {
        HomeInlinePlayerMenu(
            activity = this,
            scope = lifecycleScope,
            sleepTimer = sleepTimer,
            host = object : HomeInlinePlayerMenu.Host {
                override val previewingChannel: Channel?
                    get() = this@HomeActivity.previewingChannel
                override val previewController: PlayerController?
                    get() = this@HomeActivity.previewController
                override val castController: CastController
                    get() = this@HomeActivity.castController

                override fun togglePreviewFavorite() = this@HomeActivity.togglePreviewFavorite()
            },
        )
    }

    /** Retained through NumberZapInputHelper's clear callback for a useful miss message. */
    private var lastZapDigits = ""

    companion object {
        /** Optional category id to open on (e.g. Favorites from the dashboard). */
        const val EXTRA_INITIAL_CATEGORY = "extra_initial_category"

        /** When true, the screen shows radio stations only (the Radio folder). */
        const val EXTRA_RADIO_MODE = "extra_radio_mode"

        /** How often the preview's "now playing" program + EPG dots roll over. */
        private const val NOW_NEXT_REFRESH_MS = 30_000L

        /**
         * Debounce window before a focus-driven EPG fetch actually runs. Fast D-pad
         * scrolling cancels the in-flight (delayed) job before it hits the provider,
         * so only the channel the user settles on triggers a guide fetch.
         */
        private const val EPG_DEBOUNCE_MS = 200L

        private const val LONG_PRESS_MS = 700L
        private const val FULLSCREEN_ZAP_DEBOUNCE_MS = 280L
        private const val FULLSCREEN_EPG_TICK_MS = 1_000L
        private const val FULLSCREEN_EPG_AUTO_HIDE_MS = 4_000L
        private const val EPG_REFETCH_MIN_INTERVAL_MS = 60_000L
    }

    private val zap by lazy {
        NumberZapInputHelper(
            lookup = { num -> numberZapChannels().firstOrNull { it.number == num } },
            onResolved = { channel ->
                fullscreenZap.cancel()
                if (channel == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.number_zap_no_channel, lastZapDigits),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    requestChannelPlayback(channel, enterFullscreenWhenReady = true)
                }
                lastZapDigits = ""
            },
            onInputChanged = { typed ->
                if (typed.isNotEmpty()) fullscreenZap.cancel(hideOverlay = false)
                if (typed.isNotEmpty()) lastZapDigits = typed
                binding.zapOverlay.text = typed
                binding.zapOverlay.visibility = if (typed.isEmpty()) View.GONE else View.VISIBLE
                // The fullscreen EPG confirms the resolved channel. Avoid a
                // duplicate numeric chip flashing in the top-right meanwhile.
                binding.fullscreenZapOverlay.visibility = View.GONE
            }
        )
    }

    /** True while the left pane shows the channel list (drilled into a category). */
    private val inChannelView: Boolean
        get() = binding.channelList.visibility == View.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        fullscreenLayout = HomeFullscreenLayout(binding)
        setContentView(binding.root)
        // Device default until the persisted choice arrives (collector below), so
        // a weak stick never runs an inline preview during the first OK press.
        livePreviewEnabled = LivePreviewPolicy.defaultEnabled(
            remote = runCatching { PlaybackRemotePolicy.deviceOverrides().livePreviewEnabled }
                .getOrNull(),
            compat = compatMode,
        )
        playbackSession = TvPlaybackSession(
            context = this,
            tag = "KULULUPLAY-Live-Home",
            controls = TvPlaybackSession.Controls(
                onPlay = { resumePreviewPlayback() },
                onPause = { pausePreviewPlayback(abandonFocus = false) },
                onToggle = {
                    if (localPreviewPlaybackRequested) {
                        pausePreviewPlayback(abandonFocus = false)
                    } else {
                        resumePreviewPlayback()
                    }
                },
                onStop = { pausePreviewPlayback(abandonFocus = true) },
            ),
        )
        castController.attach()

        setupLists()
        setupSearch()
        setupFocusGraph()
        observe()
        observeNewContent()

        // On-screen Debug overlay over the live preview (engine/stage + log tail).
        debugBinder = DebugOverlayBinder(binding.debugOverlay) { previewController?.streamInfo() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    ServiceLocator.settings.debugOverlay.collectLatest { debugBinder?.setEnabled(it) }
                } finally {
                    // Leaving STARTED: drop the listener + timer so a stopped Home
                    // doesn't keep rendering the overlay off-screen.
                    debugBinder?.setEnabled(false)
                }
            }
        }
    }

    /**
     * Flashes a transient top-right notice when the launch refresh found live
     * channels that weren't cached before, then clears the tally so it shows once.
     */
    private fun observeNewContent() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NewContentNotifier.newLive.collectLatest { count ->
                    if (count > 0) {
                        NewContentPopup.show(
                            this@HomeActivity,
                            R.drawable.ic_tv,
                            resources.getQuantityString(R.plurals.new_channels_added, count, count)
                        )
                        NewContentNotifier.consumeLive()
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (fullscreenGuideVisible && event.repeatCount == 0 && HomeKeyCodes.isNumberKey(keyCode)) {
            closeFullscreenGuide(showPlayingEpg = false)
        }
        return zap.handleKeyDown(keyCode, event.repeatCount) || super.onKeyDown(keyCode, event)
    }

    /**
     * D-pad shortcuts that mirror the 2-pane drill-down:
     *   - LEFT while in the channel list (and focus is on it) returns to the
     *     category list, same as Back.
     *   - RIGHT while on the category list drills into that category's channels,
     *     same as OK/click.
     * Handled here (before the focus search) so LEFT/RIGHT don't just bounce
     * around inside the lists. Other keys fall through to normal handling.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A fresh DOWN means the matching UP went to another window (a PIN
        // dialog opened by the consumed press). Drop the stale entry and let
        // this press through instead of swallowing it.
        if (
            consumedUntilUp.swallow(
                event.keyCode,
                isDown = event.action == KeyEvent.ACTION_DOWN,
                isUp = event.action == KeyEvent.ACTION_UP,
                repeatCount = event.repeatCount,
            )
        ) {
            return true
        }

        // RecyclerView rows normally consume OK before Activity.onKeyDown. Commit a
        // pending number zap here first so the same press cannot also click a row.
        if (
            event.action == KeyEvent.ACTION_DOWN &&
            zap.hasPendingInput &&
            HomeKeyCodes.isConfirmKey(event.keyCode)
        ) {
            if (event.repeatCount == 0) {
                zap.commitIfPending()
                consumedUntilUp.add(event.keyCode)
            }
            return true
        }
        if (
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            zap.hasPendingInput
        ) {
            zap.cancel()
            consumedUntilUp.add(event.keyCode)
            return true
        }
        if (
            event.action == KeyEvent.ACTION_DOWN &&
            zap.hasPendingInput &&
            HomeKeyCodes.cancelsNumberZap(event.keyCode)
        ) {
            zap.cancel()
        }

        if (inlineFullscreen) {
            if (dispatchFullscreenKey(event)) return true
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    when {
                        binding.epgList.hasFocus() -> {
                            focusGuideChannelFavorite()
                            return true
                        }
                        binding.channelList.hasFocus() && currentFocus?.id == R.id.favStar -> {
                            binding.channelList.findContainingItemView(currentFocus!!)?.requestFocus()
                            return true
                        }
                        // Drill back to categories from the channel name.
                        inChannelView && binding.channelList.hasFocus() -> {
                            if (event.repeatCount > 0) return true
                            consumedUntilUp.add(event.keyCode)
                            if (viewModel.query.value.isNotEmpty()) exitSearch()
                            else showCategories()
                            return true
                        }
                        // LEFT from the focusable preview card returns to the left list
                        // instead of dead-ending, keeping the drill path uniform.
                        binding.previewCard.hasFocus() -> {
                            if (event.repeatCount > 0) return true
                            consumedUntilUp.add(event.keyCode)
                            (if (inChannelView) binding.channelList else binding.categoryList)
                                .requestFocus()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when {
                        inChannelView && binding.channelList.hasFocus() -> {
                            if (event.repeatCount > 0) return true
                            consumedUntilUp.add(event.keyCode)
                            val row = currentFocus?.let(binding.channelList::findContainingItemView)
                            val favorite = row?.findViewById<View>(R.id.favStar)
                            if (currentFocus?.id != R.id.favStar && favorite?.isShown == true) {
                                favorite.requestFocus()
                            } else {
                                focusCurrentProgram()
                            }
                            return true
                        }
                        !inChannelView && binding.categoryList.hasFocus() -> {
                            if (event.repeatCount > 0) return true
                            categoryAdapter.currentList
                                .firstOrNull { it.id == lastSelectedCategoryId }
                                ?.let {
                                    consumedUntilUp.add(event.keyCode)
                                    drillIntoCategory(it)
                                    return true
                                }
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (binding.previewCard.hasFocus()) {
                        focusCurrentProgram()
                        return true
                    }
                    if (event.repeatCount > 0 && binding.searchInput.hasFocus()) return true
                    if (binding.searchInput.hasFocus()) {
                        consumedUntilUp.add(event.keyCode)
                        binding.searchInput.hideSoftKeyboard()
                        when {
                            inChannelView && currentChannels.isNotEmpty() ->
                                rowFocuser.focusRow(binding.channelList, 0)
                            categoryAdapter.itemCount > 0 -> {
                                val id = lastSelectedCategoryId
                                if (id != null) focusCategory(id)
                                else rowFocuser.focusRow(binding.categoryList, 0)
                            }
                        }
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (binding.epgList.hasFocus()) {
                        val row = currentFocus?.let(binding.epgList::findContainingItemView)
                        if (row != null && binding.epgList.getChildAdapterPosition(row) == 0) {
                            binding.previewCard.requestFocus()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> {
                    // repeatCount guard: a held key must not stack-launch the browser.
                    if (event.repeatCount == 0) {
                        consumedUntilUp.add(event.keyCode)
                        openCatchup()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Fullscreen remains inside HomeActivity, so every handled remote event is
     * consumed before Android's focus search can escape into the hidden browser.
     * Confirm keys are matched here (long-OK favourite); every other key goes
     * through the pure decision table in [HomeFullscreenKeyPolicy].
     */
    private fun dispatchFullscreenKey(event: KeyEvent): Boolean {
        if (HomeKeyCodes.isConfirmKey(event.keyCode)) {
            // While the guide is open, RecyclerView owns D-pad and confirm events.
            if (fullscreenGuideVisible) return false
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) fullscreenZap.cancel()
                    fullscreenConfirmPress.onDown(
                        event.keyCode,
                        event.eventTime,
                        event.repeatCount,
                    )
                }
                KeyEvent.ACTION_UP -> {
                    val release = fullscreenConfirmPress.onUp(
                        event.keyCode,
                        event.eventTime,
                        event.isCanceled,
                    ) ?: return true
                    if (!release.canceled) {
                        if (release.heldMs >= LONG_PRESS_MS) {
                            togglePreviewFavorite()
                        } else {
                            val channel = previewingChannel
                            if (
                                previewState == LivePreviewPressPolicy.Phase.FAILED &&
                                channel != null
                            ) {
                                requestChannelPlayback(
                                    channel,
                                    enterFullscreenWhenReady = true,
                                )
                            } else {
                                openFullscreenGuide()
                            }
                        }
                    }
                }
            }
            return true
        }

        val decision = HomeFullscreenKeyPolicy.decide(
            keyCode = event.keyCode,
            isDown = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            guideVisible = fullscreenGuideVisible,
        )
        if (!decision.consume) return false
        if (decision.cancelZap) fullscreenZap.cancel()
        when (decision.command) {
            HomeFullscreenKeyPolicy.Command.NONE -> Unit
            HomeFullscreenKeyPolicy.Command.EXIT_FULLSCREEN -> exitPreviewFullscreen()
            HomeFullscreenKeyPolicy.Command.ZAP_UP -> zapFullscreenChannel(+1)
            HomeFullscreenKeyPolicy.Command.ZAP_DOWN -> zapFullscreenChannel(-1)
            HomeFullscreenKeyPolicy.Command.MENU -> inlinePlayerMenu.show()
            HomeFullscreenKeyPolicy.Command.TOGGLE_CAPTION -> toggleFullscreenCaption()
            HomeFullscreenKeyPolicy.Command.CATCHUP -> openCatchup()
            HomeFullscreenKeyPolicy.Command.CLOSE_GUIDE -> closeFullscreenGuide()
            HomeFullscreenKeyPolicy.Command.GUIDE_ZAP_UP -> {
                closeFullscreenGuide(showPlayingEpg = false)
                zapFullscreenChannel(+1)
            }
            HomeFullscreenKeyPolicy.Command.GUIDE_ZAP_DOWN -> {
                closeFullscreenGuide(showPlayingEpg = false)
                zapFullscreenChannel(-1)
            }
            HomeFullscreenKeyPolicy.Command.GUIDE_MENU -> {
                closeFullscreenGuide(showPlayingEpg = false)
                inlinePlayerMenu.show()
            }
            HomeFullscreenKeyPolicy.Command.GUIDE_CATCHUP -> {
                closeFullscreenGuide(showPlayingEpg = false)
                openCatchup()
            }
        }
        if (decision.markConsumedUntilUp) consumedUntilUp.add(event.keyCode)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Also cover programmatic/system Back paths that bypass dispatchKeyEvent:
        // a pending number must never commit after fullscreen has already exited.
        if (zap.hasPendingInput) {
            zap.cancel()
            return
        }
        if (fullscreenGuideVisible) {
            closeFullscreenGuide()
            return
        }
        if (inlineFullscreen) {
            exitPreviewFullscreen()
            return
        }
        // Number zap can request fullscreen after the next real frame. Back while
        // that channel is still buffering cancels the deferred transition.
        if (pendingFullscreenChannelId != null) {
            pendingFullscreenChannelId = null
            // No inline preview wanted: cancelling the deferred fullscreen must
            // not leave the stream running inside the card.
            if (!livePreviewEnabled) previewingChannel?.let { stopPreview(); showInfo(it) }
            return
        }
        if (viewModel.query.value.isNotEmpty()) {
            exitSearch()
            return
        }
        // Back from the channel list returns to the category list, matching the
        // 2-pane drill-down. Otherwise leave the screen as usual.
        if (inChannelView) {
            showCategories()
        } else {
            super.onBackPressed()
        }
    }

    private fun setupLists() {
        categoryAdapter = CategoryAdapter(
            onFocused = {
                pendingFullscreenChannelId = null
                if (it.id != lastSelectedCategoryId) {
                    pendingEnterCategoryId = null
                    lastSelectedCategoryId = it.id
                    pendingScrollReset = true
                    // Clear the previous category immediately. Without this, a fast
                    // RIGHT+OK sequence can click a stale row before the new Flow
                    // emission reaches the adapter.
                    currentChannels = emptyList()
                    channelAdapter.submitList(emptyList())
                    viewModel.selectCategory(it.id)
                }
            },
            onClicked = { category -> drillIntoCategory(category) }
        )
        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter
        // Category counts update live as content lazy-loads, re-emitting the list.
        // The default change-animation detaches the focused row mid-diff, so fast
        // D-pad browsing loses focus and the screen pops back to the Dashboard.
        // Disabling change animations rebinds the row in place and keeps focus.
        (binding.categoryList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        channelAdapter = ChannelAdapter(
            // Press-to-preview model: navigating only refreshes the info/EPG
            // panel (the stop-if-different guard lives in showInfo so every
            // selection path is covered, not just row focus).
            onFocused = {
                if (it.id != previewingChannel?.id) {
                    val abandonedFullscreen = pendingFullscreenChannelId != null
                    pendingFullscreenChannelId = null
                    // With live preview off the stream only exists to become
                    // fullscreen; abandoning that intent must not leave an inline
                    // preview playing behind the list.
                    if (abandonedFullscreen && !livePreviewEnabled && previewingChannel != null) {
                        stopPreview()
                    }
                }
                lastFocusedChannelId = it.id
                showInfo(it)
            },
            onClicked = { onChannelClicked(it) },
            onToggleFavorite = {
                viewModel.toggleFavorite(it.id)
                val msg = if (it.isFavorite) R.string.removed_from_favorites
                else R.string.added_to_favorites
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )
        channelAdapter.lockedProvider = ::isChannelLocked
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter
        binding.channelList.onNavigateAboveStart = { binding.searchInput.requestFocus() }
        // Weak sticks: no row animations at all, the decoder needs the CPU.
        if (compatMode) binding.channelList.itemAnimator = null

        epgAdapter = ProgramAdapter { program ->
            EpgInfoDialog.show(this, currentInfoChannel, program)
        }
        binding.epgList.layoutManager = LinearLayoutManager(this)
        binding.epgList.adapter = epgAdapter
        binding.epgList.itemAnimator = null

        fullscreenGuideAdapter = FullscreenChannelGuideAdapter(
            onFocused = ::showFullscreenGuideEpg,
            onClicked = ::selectFullscreenGuideChannel,
            onToggleFavorite = ::toggleFullscreenGuideFavorite,
        )
        fullscreenGuideAdapter.lockedProvider = ::isChannelLocked
        binding.fullscreenGuideList.layoutManager = LinearLayoutManager(this)
        binding.fullscreenGuideList.adapter = fullscreenGuideAdapter
        (binding.fullscreenGuideList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        if (compatMode) binding.fullscreenGuideList.itemAnimator = null
        binding.fullscreenGuideIcon.setImageResource(
            if (radioMode) R.drawable.ic_radio else R.drawable.ic_tv,
        )
        binding.fullscreenGuideTitle.setText(
            if (radioMode) R.string.nav_radio else R.string.nav_live,
        )

        // The preview surface goes fullscreen on the PLAYING channel (so a click
        // after browsing other channel names still expands what's on screen),
        // falling back to the focused channel when nothing is previewing yet.
        binding.previewCard.setOnClickListener {
            (previewingChannel ?: currentInfoChannel)?.let(::onChannelClicked)
        }
        binding.refreshButton.setOnClickListener { viewModel.refresh() }
        binding.retryButton.setOnClickListener {
            restoreFocusAfterRetry = true
            viewModel.refresh()
        }
        binding.browserTitle.setText(if (radioMode) R.string.nav_radio else R.string.nav_live)
        binding.browserIcon.setImageResource(if (radioMode) R.drawable.ic_radio else R.drawable.ic_tv)
        binding.previewHeaderTitle.setText(
            if (radioMode) R.string.radio_preview_title else R.string.live_preview_title
        )
        binding.previewCard.contentDescription = binding.previewHeaderTitle.text
        updateBrowseHeader()
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                viewModel.setQuery(query)
                if (query.isNotBlank()) {
                    binding.categoryList.visibility = View.GONE
                    binding.channelList.visibility = View.VISIBLE
                    pendingScrollReset = true
                    // The ViewModel search is debounced. Remove the old category's
                    // rows synchronously so they cannot be opened as search results.
                    currentChannels = emptyList()
                    channelAdapter.submitList(emptyList())
                    clearPreview()
                } else if (binding.searchInput.hasFocus()) {
                    // Keep the keyboard/search field active when the last
                    // character is deleted. Moving focus here made it impossible
                    // to immediately type a different query with a TV keyboard.
                    currentChannels = emptyList()
                    channelAdapter.submitList(emptyList())
                    binding.categoryList.visibility = View.VISIBLE
                    binding.channelList.visibility = View.GONE
                }
                updateBrowseHeader()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                binding.searchInput.hideSoftKeyboard()
                if (viewModel.query.value.isNotBlank() && currentChannels.isNotEmpty()) {
                    rowFocuser.focusRow(binding.channelList, 0)
                } else {
                    val id = lastSelectedCategoryId
                    if (id != null) focusCategory(id)
                    else if (categoryAdapter.itemCount > 0) rowFocuser.focusRow(binding.categoryList, 0)
                }
                true
            } else {
                false
            }
        }
    }

    private fun exitSearch() {
        binding.searchInput.text?.clear()
        binding.searchInput.hideSoftKeyboard()
        binding.searchInput.clearFocus()
        showCategories()
    }

    /**
     * Explicit focus edges prevent Android's geometric focus search from jumping
     * outside the visible pane on dense TV layouts. Dynamic LEFT/RIGHT drill
     * behaviour remains in [dispatchKeyEvent].
     */
    private fun setupFocusGraph() {
        binding.refreshButton.nextFocusDownId = binding.searchInput.id
        binding.refreshButton.nextFocusLeftId = binding.refreshButton.id
        binding.refreshButton.nextFocusRightId = binding.previewCard.id
        binding.refreshButton.nextFocusUpId = binding.refreshButton.id
        binding.searchInput.nextFocusUpId = binding.refreshButton.id
        binding.searchInput.nextFocusRightId = binding.previewCard.id
        binding.categoryList.nextFocusRightId = binding.previewCard.id
        binding.channelList.nextFocusRightId = binding.previewCard.id
        binding.previewCard.nextFocusUpId = binding.refreshButton.id
        binding.previewCard.nextFocusRightId = binding.previewCard.id
        binding.previewCard.nextFocusDownId = binding.epgList.id
    }

    private fun focusCurrentProgram() {
        val programs = epgAdapter.currentList
        if (programs.isEmpty()) {
            binding.previewCard.requestFocus()
            return
        }
        val now = System.currentTimeMillis()
        val index = programs.indexOfFirst { it.isLiveAt(now) }.takeIf { it >= 0 }
            ?: programs.indexOfFirst { it.startMs >= now }.coerceAtLeast(0)
        rowFocuser.focusRow(binding.epgList, index)
    }

    private fun focusGuideChannelFavorite() {
        val index = currentChannels.indexOfFirst { it.id == currentInfoChannel?.id }
        if (index < 0) { binding.previewCard.requestFocus(); return }
        rowFocuser.focusRow(binding.channelList, index)
        binding.channelList.post {
            binding.channelList.findViewHolderForAdapterPosition(index)?.itemView
                ?.findViewById<View>(R.id.favStar)?.takeIf { it.isShown }?.requestFocus()
        }
    }

    /** Keeps the rail label and count synchronized with its currently visible list. */
    private fun updateBrowseHeader() {
        val showingChannels = inChannelView || viewModel.query.value.isNotBlank()
        binding.browseModeLabel.setText(
            if (showingChannels) R.string.channels_title else R.string.categories_title
        )
        val count = if (showingChannels) currentChannels.size else categoryAdapter.itemCount
        binding.browseCount.text = getString(R.string.browse_count_format, count)
    }

    private fun observe() {
        // Lifecycle-scoped: collection is suspended while the screen is STOPPED so we
        // don't do submitList / EPG / UI work off-screen, and resubscribes on START.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Read the lock before starting channel collection, so the first
                // adapter bind cannot briefly expose an adult name or logo.
                adultLockEnabled = ServiceLocator.settings.lockAdult.first() &&
                    ServiceLocator.settings.hasPin()
                channelAdapter.refreshVisible(binding.channelList)

                launch {
                    viewModel.categories.collectLatest { cats ->
                        categoryAdapter.submitList(cats) {
                            // Run the one-time initial selection after the list has
                            // committed so focusCategory() can find the row reliably.
                            if (cats.isNotEmpty() && !initialSelectionDone) {
                                initialSelectionDone = true
                                val requested = intent.getStringExtra(EXTRA_INITIAL_CATEGORY)
                                // Live TV skips the two synthetic rows (Favorites/Recent);
                                // Radio mode has none, so land on its first real category.
                                val target = cats.firstOrNull { it.id == requested }
                                    ?: cats.getOrNull(if (radioMode) 0 else 2) ?: cats.first()
                                lastSelectedCategoryId = target.id
                                viewModel.selectCategory(target.id)
                                focusCategory(target.id)
                            } else if (
                                cats.isNotEmpty() &&
                                cats.none { it.id == lastSelectedCategoryId }
                            ) {
                                // A refresh/content-setting can remove the selected
                                // category. Reconcile model + visible focus together
                                // so RIGHT never searches for a stale id.
                                val target = cats.getOrNull(if (radioMode) 0 else 2)
                                    ?: cats.first()
                                lastSelectedCategoryId = target.id
                                currentChannels = emptyList()
                                channelAdapter.submitList(emptyList())
                                pendingScrollReset = true
                                viewModel.selectCategory(target.id)
                                if (inChannelView) showCategories()
                                else focusCategory(target.id)
                            }
                            updateBrowseHeader()
                        }
                    }
                }
                launch {
                    viewModel.channels.collectLatest { snapshot ->
                        val query = viewModel.query.value
                        if (snapshot.query != query) return@collectLatest
                        if (
                            query.isEmpty() &&
                            snapshot.categoryId != lastSelectedCategoryId
                        ) return@collectLatest

                        currentChannelSnapshot = snapshot
                        val channels = snapshot.channels
                        currentChannels = channels
                        updateBrowseHeader()
                        channelAdapter.submitList(channels) {
                            if (pendingScrollReset) {
                                pendingScrollReset = false
                                binding.channelList.scrollToPosition(0)
                            }
                            val pendingCategory = pendingEnterCategoryId
                            if (
                                pendingCategory != null &&
                                pendingCategory == lastSelectedCategoryId &&
                                snapshot.loaded &&
                                snapshot.query.isEmpty() &&
                                snapshot.categoryId == pendingCategory
                            ) {
                                completePendingCategoryEntry(channels)
                            }
                            // Returning to the channel list (e.g. back from the
                            // fullscreen player) re-emits the list, which resets D-pad
                            // focus to the top. Restore focus to the row we left from.
                            if (pendingChannelFocusRestore && inChannelView) {
                                pendingChannelFocusRestore = false
                                val targetId = lastFocusedChannelId
                                val pos = channels.indexOfFirst { it.id == targetId }
                                if (pos >= 0) {
                                    lastFocusedChannelId = targetId
                                    rowFocuser.focusRow(binding.channelList, pos, center = true)
                                } else if (channels.isNotEmpty()) {
                                    val playingPos = channels.indexOfFirst {
                                        it.id == previewingChannel?.id
                                    }
                                    rowFocuser.focusRow(
                                        binding.channelList,
                                        playingPos.takeIf { it >= 0 } ?: 0,
                                        center = playingPos > 0,
                                    )
                                }
                            }
                            // While still browsing categories, preview the first channel
                            // of the focused category (like the reference design).
                            if (!inlineFullscreen && !inChannelView) {
                                val first = channels.firstOrNull()
                                // Don't auto-preview a locked adult category's first
                                // channel: that would leak its name/logo/EPG before the
                                // PIN is entered. Only metadata is gated here; playback
                                // is already guarded in the category/channel actions.
                                if (
                                    first != null &&
                                    !isCurrentCategoryLocked() &&
                                    !isChannelLocked(first)
                                ) {
                                    showInfo(first)
                                } else if (previewingChannel != null) {
                                    clearFocusedGuidePreservingPreview(
                                        locked = isCurrentCategoryLocked(),
                                    )
                                } else {
                                    clearPreview()
                                }
                            }
                        }
                        renderBrowseState()
                    }
                }
                launch {
                    viewModel.loadState.collectLatest {
                        renderBrowseState()
                    }
                }
                // Time-driven now/next refresh: rolls the "live now" program + EPG dots
                // over program boundaries without a focus/data event. Auto-cancels when
                // STOPPED (repeatOnLifecycle), so no off-screen ticking.
                launch {
                    while (true) {
                        delay(LowEndUiBudget.refreshIntervalMs(NOW_NEXT_REFRESH_MS, compatMode))
                        refreshNowNext()
                    }
                }
                // Live preview on/off: explicit user choice > remote override > profile.
                launch {
                    ServiceLocator.settings.livePreviewChoice.collectLatest { choice ->
                        val remote = runCatching {
                            PlaybackRemotePolicy.deviceOverrides().livePreviewEnabled
                        }.getOrNull()
                        livePreviewEnabled = LivePreviewPolicy.enabled(choice, remote, compatMode)
                        // Without video the card has room for the following program too.
                        binding.previewProgram.maxLines = if (livePreviewEnabled) 1 else 2
                        refreshNowNext()
                    }
                }
            }
        }
    }

    private fun renderBrowseState() {
        val state = viewModel.loadState.value
        val failed = state.errorRes != null
        // Favorites/Recent are synthetic rows and do not prove that a usable
        // live catalogue exists. A first-load failure must still show Retry when
        // those two placeholders are the only rows available.
        val hasRealCategories = categoryAdapter.currentList.any {
            it.id != HomeViewModel.CAT_FAVORITES && it.id != HomeViewModel.CAT_RECENT
        }
        val showingChannels = inChannelView || viewModel.query.value.isNotBlank()
        val visibleItemCount =
            if (showingChannels) currentChannels.size else categoryAdapter.itemCount
        val flags = HomeBrowseStatePolicy.resolve(
            loading = state.loading,
            failed = failed,
            hasRealCategories = hasRealCategories,
            hasChannels = currentChannels.isNotEmpty(),
            visibleItemCount = visibleItemCount,
        )
        val blockingLoading = flags.blockingLoading
        val blockingFailure = flags.blockingFailure
        binding.loadingIndicator.visibility =
            if (blockingLoading) View.VISIBLE else View.GONE
        binding.loadErrorContainer.visibility =
            if (blockingFailure) View.VISIBLE else View.GONE
        state.errorRes?.let { binding.loadErrorText.setText(it) }
        // Keep an already-focused refresh control in the D-pad graph while the
        // request runs; disabling it makes Android TV eject focus unpredictably.
        binding.refreshButton.isClickable = !state.loading
        binding.refreshButton.alpha = if (state.loading) 0.5f else 1f

        binding.emptyState.setText(
            if (viewModel.query.value.isNotEmpty()) R.string.empty_search
            else R.string.empty_channels
        )
        binding.emptyState.visibility = if (flags.empty) View.VISIBLE else View.GONE
        if (blockingFailure) {
            binding.retryButton.post { binding.retryButton.requestFocus() }
        } else if (restoreFocusAfterRetry && !state.loading) {
            restoreFocusAfterRetry = false
            when {
                inChannelView && currentChannels.isNotEmpty() -> focusFirstChannel()
                categoryAdapter.itemCount > 0 -> {
                    val id = lastSelectedCategoryId
                    if (id != null) focusCategory(id)
                    else rowFocuser.focusRow(binding.categoryList, 0)
                }
            }
        }
    }

    private var nowNextJob: kotlinx.coroutines.Job? = null

    /** Last EPG programs fetched for the previewed channel; reused by the now/next tick. */
    private var currentPrograms: List<Program> = emptyList()

    /** The channel currently shown in the preview/info panel. */
    private var currentInfoChannel: Channel? = null

    /**
     * The channel whose live video is actually playing in the preview surface.
     * Set only when the user presses OK on a channel; null means the panel is
     * info-only. A second OK on this same channel expands to full screen. The
     * on-video caption stays bound to THIS channel while the user browses other
     * channel names (whose guide loads into the EPG list independently).
     */
    private var previewingChannel: Channel? = null

    /** The previewing channel's EPG, used to keep the on-video caption's now-playing fresh. */
    private var captionPrograms: List<Program> = emptyList()

    /** Loads the previewing channel's guide for the caption now-playing text. */
    private var captionJob: kotlinx.coroutines.Job? = null

    /** Rejects a late EPG result from a previously-zapped channel. */
    private var captionRequestGeneration = 0
    private var captionEpgLoaded = false
    private var lastCaptionEpgRequestAtMs = 0L

    /** Locale/12-24h aware clock formatter for EPG times. */
    private val timeFmt by lazy { android.text.format.DateFormat.getTimeFormat(this) }

    private fun showInfo(channel: Channel) {
        if (isChannelLocked(channel)) {
            showLockedInfo(channel)
            return
        }
        binding.guideChannelTitle.text = ChannelText.clean(channel.name)
        // Re-selecting the channel already shown (e.g. the list re-emits on a favorite
        // toggle, or a refocus) shouldn't re-fetch its guide or rebuild the panel.
        if (channel.id == currentInfoChannel?.id && currentPrograms.isNotEmpty()) {
            binding.epgLoading.visibility = View.GONE
            return
        }
        // Browsing other channel names must NOT stop the live preview: the preview
        // only switches/stops on an explicit OK (see onChannelClicked). Focus just
        // moves the info/EPG panel; the on-video caption stays on the playing
        // channel (bound below only while nothing is previewing).
        currentInfoChannel = channel
        binding.catchupHint.visibility =
            if (channel.catchupDays > 0) View.VISIBLE else View.GONE
        // The on-video caption follows focus only while nothing is previewing.
        // Once a preview is playing it stays locked to the playing channel so the
        // caption can never describe a different channel than the picture shows.
        if (previewingChannel == null) {
            bindCaptionMeta(channel)
            binding.previewProgram.text = ""
        }
        currentPrograms = emptyList()
        epgAdapter.submitList(emptyList())
        binding.epgEmpty.visibility = View.GONE
        binding.epgLoading.visibility = View.VISIBLE

        nowNextJob?.cancel()
        nowNextJob = lifecycleScope.launch {
            try {
                // Debounce: rapid D-pad scrolling cancels this before the fetch fires,
                // so only the channel the user settles on hits the provider/Room.
                delay(EPG_DEBOUNCE_MS)
                val programs = viewModel.programs(channel)
                if (currentInfoChannel?.id != channel.id) return@launch
                currentPrograms = programs
                val now = System.currentTimeMillis()
                // Caption now-playing follows focus only when nothing is previewing.
                if (previewingChannel == null) {
                    binding.previewProgram.text = captionText.captionProgramLabel(
                        programs,
                        now,
                        includeNext = !livePreviewEnabled && previewingChannel == null,
                    )
                }
                epgAdapter.submitList(programs) {
                    val idx = programs.indexOfFirst { it.isLiveAt(now) }
                    if (idx >= 0) binding.epgList.scrollToPosition(idx)
                }
                binding.epgLoading.visibility = View.GONE
                binding.epgEmpty.visibility =
                    if (programs.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Best-effort guide: never let an EPG fetch crash the UI coroutine.
                if (currentInfoChannel?.id != channel.id) return@launch
                currentPrograms = emptyList()
                epgAdapter.submitList(emptyList())
                binding.epgLoading.visibility = View.GONE
                binding.epgEmpty.visibility = View.VISIBLE
            }
        }
    }

    /** Renders a channel's static metadata (number + logos + title) into the caption. */
    private fun bindCaptionMeta(channel: Channel) {
        val number = channel.number?.toString().orEmpty()
        binding.previewNumber.text = number
        binding.previewNumber.visibility = View.GONE
        binding.previewLiveBadge.visibility = View.GONE
        binding.previewCaptionLogoPlate.visibility = View.GONE
        val cleanName = ChannelText.clean(channel.name)
        binding.previewTitle.text = cleanName
        if (previewingChannel == null) showPreviewIdentityPlaceholder()
        val placeholder = LogoPlaceholder.forName(this, channel.name)
        if (channel.logoUrl.isNullOrBlank()) {
            binding.infoLogo.load(placeholder) { crossfade(false) }
            binding.previewCaptionLogo.load(placeholder) { crossfade(false) }
        } else {
            binding.infoLogo.load(channel.logoUrl) {
                placeholder(placeholder); error(placeholder)
            }
            binding.previewCaptionLogo.load(channel.logoUrl) {
                placeholder(placeholder); error(placeholder)
            }
        }
        fullscreenEpgCard.bindMeta(channel)
    }

    /**
     * Artwork helps identify a channel in the browse preview, but looks like a
     * large splash screen when the same surface is fullscreen. Channel changes
     * in fullscreen therefore keep the centre clear and rely on the EPG card.
     */
    private fun showPreviewIdentityPlaceholder(show: Boolean = true) {
        binding.infoLogo.visibility =
            if (show && !inlineFullscreen) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Locks the on-video caption (number/logo/title/now-playing) to the channel
     * that is actually previewing, and fetches its guide so the caption's
     * now-playing line is correct even while the user browses other channels (the
     * EPG list below follows focus independently via showInfo).
     */
    private fun lockCaptionToPreview(
        channel: Channel,
        showLoading: Boolean = true,
    ) {
        if (showLoading) {
            bindCaptionMeta(channel)
            // Clear every time-dependent field synchronously. Without this, a rapid
            // CH+/CH- could briefly pair the new channel name with the old channel's
            // programme while the fresh Room query was still running.
            binding.previewProgram.text = ""
            fullscreenEpgCard.renderLoading()
        }
        captionJob?.cancel()
        if (showLoading) {
            captionPrograms = emptyList()
            captionEpgLoaded = false
        }
        val requestGeneration = ++captionRequestGeneration
        lastCaptionEpgRequestAtMs = System.currentTimeMillis()
        captionJob = lifecycleScope.launch {
            try {
                val programs = viewModel.programs(channel)
                if (
                    requestGeneration != captionRequestGeneration ||
                    previewingChannel?.id != channel.id ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) return@launch
                captionPrograms = programs
                captionEpgLoaded = true
                val now = System.currentTimeMillis()
                binding.previewProgram.text = captionText.nowPlayingLabel(programs, now)
                if (!fullscreenGuideVisible) {
                    fullscreenEpgCard.bindMeta(channel)
                    fullscreenEpgCard.bind(programs, now)
                    scheduleFullscreenEpgAutoHide()
                } else if (fullscreenGuideFocusedChannel?.id == channel.id) {
                    fullscreenGuidePrograms = programs
                    fullscreenGuideEpgLoaded = true
                    fullscreenEpgCard.bind(programs, now)
                }
                if (!inlineFullscreen && currentInfoChannel?.id == channel.id) {
                    restoreBrowseGuideFromPlayingCaption()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (
                    requestGeneration != captionRequestGeneration ||
                    previewingChannel?.id != channel.id
                ) return@launch
                if (!showLoading) return@launch
                captionPrograms = emptyList()
                captionEpgLoaded = true
                binding.previewProgram.text = ""
                if (!fullscreenGuideVisible) {
                    fullscreenEpgCard.bindMeta(channel)
                    fullscreenEpgCard.bind(emptyList(), System.currentTimeMillis())
                    scheduleFullscreenEpgAutoHide()
                } else if (fullscreenGuideFocusedChannel?.id == channel.id) {
                    fullscreenGuidePrograms = emptyList()
                    fullscreenGuideEpgLoaded = true
                    fullscreenEpgCard.bind(emptyList(), System.currentTimeMillis())
                }
                if (!inlineFullscreen && currentInfoChannel?.id == channel.id) {
                    restoreBrowseGuideFromPlayingCaption()
                }
            }
        }
    }

    /**
     * Re-query at most once per minute when no "next" programme is cached.
     * This covers both very long sessions and providers that append their guide
     * incrementally. Background refresh preserves the current card while loading
     * and never touches or restarts playback.
     */
    private fun maybeRefreshExpiredCaptionEpg(now: Long) {
        if (LiveEpgOverlayPolicy.resolve(captionPrograms, now).next != null) return
        if (now - lastCaptionEpgRequestAtMs < EPG_REFETCH_MIN_INTERVAL_MS) return
        val channel = previewingChannel ?: return
        lockCaptionToPreview(channel, showLoading = false)
    }

    private fun clearPreview() {
        currentInfoChannel = null
        stopPreview()
        binding.catchupHint.visibility = View.GONE
        binding.guideChannelTitle.text = ""
        binding.previewNumber.text = ""
        binding.previewNumber.visibility = View.GONE
        binding.previewLiveBadge.visibility = View.GONE
        binding.previewCaptionLogoPlate.visibility = View.GONE
        binding.previewTitle.text = ""
        binding.previewProgram.text = ""
        binding.infoLogo.load(null)
        binding.infoLogo.visibility = View.INVISIBLE
        binding.previewCaptionLogo.load(null)
        binding.previewStatus.visibility = View.GONE
        binding.previewLoading.visibility = View.GONE
        nowNextJob?.cancel()
        currentPrograms = emptyList()
        epgAdapter.submitList(emptyList())
        binding.epgEmpty.visibility = View.GONE
        binding.epgLoading.visibility = View.GONE
    }

    /**
     * Clears passive category metadata without touching an explicitly-started
     * stream. Empty/locked category emissions must never release a safe preview.
     */
    private fun clearFocusedGuidePreservingPreview(locked: Boolean) {
        val playing = previewingChannel ?: run {
            clearPreview()
            return
        }
        nowNextJob?.cancel()
        currentInfoChannel = playing
        currentPrograms = emptyList()
        epgAdapter.submitList(emptyList())
        binding.epgEmpty.visibility = View.GONE
        binding.epgLoading.visibility = View.GONE
        binding.catchupHint.visibility = View.GONE
        if (locked) {
            binding.guideChannelTitle.setText(R.string.adult_locked_title)
        } else {
            binding.guideChannelTitle.text = ""
        }
    }

    /** Renders a metadata-free parental-lock state for an adult channel. */
    private fun showLockedInfo(channel: Channel) {
        nowNextJob?.cancel()
        currentInfoChannel = channel
        currentPrograms = emptyList()
        epgAdapter.submitList(emptyList())
        binding.epgEmpty.visibility = View.GONE
        binding.epgLoading.visibility = View.GONE
        binding.catchupHint.visibility = View.GONE
        binding.guideChannelTitle.setText(R.string.adult_locked_title)
        // Focusing a locked row must not disconnect an unrelated safe preview.
        // Mask only the focused channel's guide; keep the playing picture and its
        // caption intact until the user explicitly unlocks/plays something else.
        if (previewingChannel != null) return

        binding.previewNumber.text = ""
        binding.previewNumber.visibility = View.GONE
        binding.previewLiveBadge.visibility = View.GONE
        binding.previewCaptionLogoPlate.visibility = View.GONE
        binding.previewTitle.setText(R.string.adult_locked_title)
        binding.previewProgram.text = ""
        binding.infoLogo.visibility = View.VISIBLE
        binding.infoLogo.load(R.drawable.ic_lock) { crossfade(false) }
        binding.previewCaptionLogo.load(R.drawable.ic_lock) { crossfade(false) }
        binding.previewStatus.setText(R.string.pin_locked_content)
        binding.previewStatus.visibility = View.VISIBLE
    }

    /**
     * Re-evaluates the "live now" program from the already-fetched EPG (no
     * network) and refreshes the caption + the EPG row dots, so the guide stays
     * correct across program boundaries. The caption's now-playing tracks the
     * PLAYING channel while previewing (else the focused channel); the EPG list
     * dots always track the focused channel. Driven by a STARTED-scoped ticker.
     */
    private fun refreshNowNext() {
        val now = System.currentTimeMillis()
        val captionSource = if (previewingChannel != null) captionPrograms else currentPrograms
        if (captionSource.isNotEmpty()) {
            binding.previewProgram.text = captionText.captionProgramLabel(
                captionSource,
                now,
                includeNext = !livePreviewEnabled && previewingChannel == null,
            )
        }
        if (currentPrograms.isNotEmpty()) epgAdapter.refreshLiveState()
    }

    /**
     * True when the currently focused category is a locked (adult, not-yet-unlocked)
     * folder, so the right-pane preview/EPG must stay masked until the PIN is entered.
     */
    private fun isCurrentCategoryLocked(): Boolean {
        val cat = categoryAdapter.currentList.firstOrNull { it.id == lastSelectedCategoryId }
            ?: return false
        return adultLockEnabled && cat.isAdult() && cat.id !in unlockedCategories
    }

    /** True when exposing this channel would bypass the enabled parental lock. */
    private fun isChannelLocked(channel: Channel): Boolean =
        adultLockEnabled &&
            channel.isAdult() &&
            channel.categoryId !in unlockedCategories &&
            channel.id !in unlockedChannels

    /**
     * Drills into a category from the category list, prompting for the PIN first
     * on locked (adult) categories. Shared by OK/click and the RIGHT-arrow shortcut.
     */
    private fun drillIntoCategory(category: com.iptv.player.data.model.Category) {
        if (category.id != lastSelectedCategoryId) {
            pendingEnterCategoryId = null
            lastSelectedCategoryId = category.id
            pendingScrollReset = true
            currentChannels = emptyList()
            channelAdapter.submitList(emptyList())
            viewModel.selectCategory(category.id)
        }
        if (
            adultLockEnabled &&
            category.isAdult() &&
            category.id !in unlockedCategories
        ) {
            pinGate.guard {
                unlockedCategories.add(category.id)
                channelAdapter.refreshVisible(binding.channelList)
                enterChannelView()
            }
        } else {
            enterChannelView()
        }
    }

    /** Drills from the category list into its channel list. */
    private fun enterChannelView() {
        if (currentChannels.isEmpty()) {
            val categoryId = lastSelectedCategoryId ?: return
            val snapshot = currentChannelSnapshot
            if (
                snapshot.loaded &&
                snapshot.query.isEmpty() &&
                snapshot.categoryId == categoryId
            ) {
                Toast.makeText(this, R.string.empty_channels, Toast.LENGTH_SHORT).show()
                return
            }
            pendingEnterCategoryId = categoryId
            return
        }
        pendingEnterCategoryId = null
        showChannelView()
    }

    private fun completePendingCategoryEntry(channels: List<Channel>) {
        pendingEnterCategoryId = null
        if (channels.isEmpty()) {
            Toast.makeText(this, R.string.empty_channels, Toast.LENGTH_SHORT).show()
        } else {
            showChannelView()
        }
    }

    private fun showChannelView() {
        categoryAdapter.setSelected(lastSelectedCategoryId)
        binding.channelList.visibility = View.VISIBLE
        binding.categoryList.visibility = View.GONE
        updateBrowseHeader()
        focusFirstChannel()
    }

    /** Returns from the channel list back to the category list. */
    private fun showCategories() {
        pendingFullscreenChannelId = null
        pendingEnterCategoryId = null
        binding.categoryList.visibility = View.VISIBLE
        binding.channelList.visibility = View.GONE
        updateBrowseHeader()
        val id = lastSelectedCategoryId
        if (id != null) focusCategory(id)
        else if (categoryAdapter.itemCount > 0) rowFocuser.focusRow(binding.categoryList, 0)
    }

    /**
     * Moves focus into the channel list. Lands on the currently-playing channel
     * (the in-panel preview) when it's in this category, so re-opening the list
     * after browsing returns to what's actually playing rather than the top row.
     * Falls back to the first channel when nothing is playing in this category.
     */
    private fun focusFirstChannel() {
        val playingId = previewingChannel?.id
        val pos = playingId
            ?.let { id -> currentChannels.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        rowFocuser.focusRow(binding.channelList, pos, center = pos > 0)
    }

    /** Moves focus onto a given category row. */
    private fun focusCategory(id: String) {
        val pos = (0 until categoryAdapter.itemCount)
            .firstOrNull { categoryAdapter.currentList.getOrNull(it)?.id == id } ?: 0
        rowFocuser.focusRow(binding.categoryList, pos)
    }

    /** Opens the catch-up/archive browser, pre-focusing the previewed channel. */
    private fun openCatchup() {
        if (externalNavigationInFlight) return
        val intent = Intent(this, CatchupActivity::class.java)
        val target = if (inlineFullscreen) previewingChannel else currentInfoChannel
        target?.takeIf { it.catchupDays > 0 }?.let {
            intent.putExtra(CatchupActivity.EXTRA_CHANNEL_ID, it.id)
        }
        externalNavigationInFlight = true
        runCatching { startActivity(intent) }
            .onFailure { externalNavigationInFlight = false }
    }

    /**
     * OK on a channel: the first press starts the in-panel live preview; a
     * second press on the same (already previewing) channel expands to full
     * screen. Navigating to another channel resets this (see onFocused).
     */
    private fun onChannelClicked(channel: Channel) {
        val sameChannel = previewingChannel?.id == channel.id
        when (LivePreviewPressPolicy.decide(sameChannel, previewState)) {
            LivePreviewPressPolicy.Action.ENTER_FULLSCREEN -> enterPreviewFullscreen()
            LivePreviewPressPolicy.Action.QUEUE_FULLSCREEN -> {
                // Queue the layout transition, but never issue another play. Once
                // this same channel renders a real frame, markPreviewReady expands
                // it with the existing decoder/socket.
                pendingFullscreenChannelId = channel.id
            }
            // Preview disabled: the same controller/socket path, but the card only
            // hosts the stream until its first frame, then expands (no second play).
            LivePreviewPressPolicy.Action.START_PREVIEW ->
                requestChannelPlayback(channel, enterFullscreenWhenReady = !livePreviewEnabled)
        }
    }

    /**
     * Applies parental gating once, then starts playback. Used by row clicks,
     * number zap and fullscreen CH+/-. Repeated OK cannot stack PIN dialogs.
     */
    private fun requestChannelPlayback(
        channel: Channel,
        enterFullscreenWhenReady: Boolean,
    ) {
        if (!isChannelLocked(channel)) {
            startPreviewFor(channel, enterFullscreenWhenReady)
            return
        }
        pinGate.guard {
            unlockedChannels.add(channel.id)
            channelAdapter.refreshVisible(binding.channelList)
            showInfo(channel)
            startPreviewFor(channel, enterFullscreenWhenReady)
        }
    }

    /** Starts the live preview for [channel] while retaining the same surface/controller. */
    private fun startPreviewFor(
        channel: Channel,
        enterFullscreenWhenReady: Boolean = false,
    ) {
        if (
            previewingChannel?.id == channel.id &&
            previewState == LivePreviewPressPolicy.Phase.STARTING
        ) {
            if (enterFullscreenWhenReady) pendingFullscreenChannelId = channel.id
            if (inlineFullscreen) revealFullscreenEpgForChannelChange()
            return
        }
        if (
            previewingChannel?.id == channel.id &&
            previewState == LivePreviewPressPolicy.Phase.READY
        ) {
            if (enterFullscreenWhenReady) {
                if (inlineFullscreen) revealFullscreenEpgForChannelChange()
                else enterPreviewFullscreen()
            }
            return
        }

        if (playbackResourceToken == null) {
            playbackResourceToken = PlaybackResourceGovernor.begin("live-preview")
        }

        if (previewingChannel?.id != channel.id) {
            finishPreviewQoe(PlaybackEndReason.REPLACED)
            resolvedPreviewStreamFormat = null
            previewStreamSubmitted = false
        }
        if (currentInfoChannel?.id != channel.id) {
            // Fullscreen CH+/CH- changes the playing/info channel while the guide
            // panel is hidden. Invalidate the old focused channel's list now so
            // exiting fullscreen cannot briefly show its stale schedule.
            nowNextJob?.cancel()
            currentInfoChannel = channel
            currentPrograms = emptyList()
            epgAdapter.submitList(emptyList())
        }
        previewingChannel = channel
        fullscreenGuideAdapter.setPlayingChannel(channel.id)
        NowPlaying.set(this, ChannelText.clean(channel.name), if (radioMode) "Radio" else "Canlı")
        if (!inlineFullscreen && currentChannels.any { it.id == channel.id }) {
            previewChannelScope = currentChannels
            previewChannelScopeLabel =
                if (viewModel.query.value.isNotBlank()) {
                    getString(R.string.search_results_title)
                } else {
                    categoryAdapter.currentList
                        .firstOrNull { it.id == lastSelectedCategoryId }
                        ?.name
                        ?: channel.categoryName
                }
        }
        previewState = LivePreviewPressPolicy.Phase.STARTING
        previewLoadingPolicy.requireFreshFrame()
        pendingFullscreenChannelId =
            if (enterFullscreenWhenReady || inlineFullscreen) channel.id else null
        binding.previewLoading.visibility = View.VISIBLE
        binding.previewStatus.setText(R.string.buffering)
        binding.previewStatus.visibility = View.VISIBLE
        // Lock the on-video caption to the channel we're about to play so it stays
        // correct even as the user browses other channel names afterwards.
        lockCaptionToPreview(channel)
        if (inlineFullscreen) revealFullscreenEpgForChannelChange()
        previewJob?.cancel()
        showPreviewIdentityPlaceholder()
        previewJob = lifecycleScope.launch { startPreview(channel) }
    }

    private suspend fun startPreview(channel: Channel) {
        val settings = ServiceLocator.settings
        // Read settings first (these suspend); then bail if we were cancelled
        // meanwhile (for example by onStop) so a stale resume cannot reopen the
        // preview socket while Home is no longer visible.
        val playback = settings.getPlaybackSelection()
        val passthrough = settings.audioPassthrough.first()
        val buffer = settings.bufferMode.first()
        val streamFormat = settings.streamFormat.first()
        val preferredAudioLanguage = settings.getLiveAudioLanguage()
        val preferredSubtitlePreference = settings.getLiveSubtitlePreference()
        currentCoroutineContext().ensureActive()
        if (
            previewingChannel?.id != channel.id ||
            previewState != LivePreviewPressPolicy.Phase.STARTING
        ) return
        previewStreamFormat = streamFormat
        if (castOwnsPreviewPlayback) {
            castController.reloadCurrentMedia()
            markPreviewReady()
            return
        }
        if (castLoadPending) {
            castController.reloadCurrentMedia()
            return
        }
        val offerConnectedCast = castController.isConnected && !suppressConnectedCastReloadOnce
        suppressConnectedCastReloadOnce = false
        if (offerConnectedCast) {
            castController.reloadCurrentMedia()
            return
        }
        when (preparePreviewPlaybackRequest()) {
            ProviderStartGatePolicy.Decision.READY -> Unit
            ProviderStartGatePolicy.Decision.WAIT_FOR_LOCAL_CLEANUP,
            ProviderStartGatePolicy.Decision.RECOVER_LOCAL_PROCESS -> {
                // Expected transition: keep the existing loading UI and continue
                // automatically as soon as the previous provider socket closes.
                return
            }
            ProviderStartGatePolicy.Decision.BLOCKED_BY_REMOTE_OWNER,
            ProviderStartGatePolicy.Decision.BLOCKED -> {
                previewState = LivePreviewPressPolicy.Phase.FAILED
                pendingFullscreenChannelId = null
                binding.previewLoading.visibility = View.GONE
                binding.previewStatus.setText(
                    if (ProviderConnectionSafety.newConnectionAllowed) {
                        R.string.error_audio_focus_unavailable
                    } else {
                        R.string.error_playback_ownership_uncertain
                    },
                )
                binding.previewStatus.visibility = View.VISIBLE
                finishPreviewQoe(PlaybackEndReason.FATAL_FAILURE)
                return
            }
        }
        if (previewController == null) {
            previewController = PlayerController(
                context = this,
                container = binding.previewVideo,
                mode = playback.player,
                decoderMode = playback.decoder,
                allowPassthrough = passthrough,
                bufferMode = buffer,
                expectsVideo = !radioMode,
                preferredAudioLanguage = preferredAudioLanguage,
                preferredSubtitlePreference = preferredSubtitlePreference,
                callback = buildPreviewCallback()
            )
            // A display-mode switch blanks the TV briefly; only the fullscreen
            // player may do that, never the inline preview card.
            previewController?.setDisplayModeSwitchingAllowed(inlineFullscreen)
        }
        previewController?.play(
            LiveStreamUrl.applyFormat(channel.streamUrl, streamFormat),
            routeKey = LiveStreamUrl.routeKey(channel.id, streamFormat, channel.streamUrl),
            transportKey = LiveStreamUrl.transportKey(
                channel.id,
                channel.streamUrl,
                streamFormat,
            ),
        )
        previewStreamSubmitted = true
    }

    private fun markPreviewReady() {
        previewState = LivePreviewPressPolicy.Phase.READY
        binding.previewPlaybackCover.visibility = View.GONE
        binding.previewLoading.visibility = View.GONE
        binding.previewStatus.visibility = View.GONE
        binding.previewVideo.keepScreenOn = true
        val channelId = previewingChannel?.id
        if (channelId != null && pendingFullscreenChannelId == channelId) {
            pendingFullscreenChannelId = null
            enterPreviewFullscreen()
        }
    }

    /** UI callback: TV becomes READY only after a real frame; radio on audio playback. */
    private fun buildPreviewCallback() = object : PlayerController.Callback {
        override fun onBuffering() {
            if (castOwnsPreviewPlayback || castLoadPending) return
            previewLoadingPolicy.onBuffering()
            previewState = LivePreviewPressPolicy.Phase.STARTING
            binding.previewLoading.visibility = View.VISIBLE
            binding.previewStatus.setText(R.string.buffering)
            binding.previewStatus.visibility = View.VISIBLE
            PlaybackQoeRuntime.setRebuffering(previewQoeSessionId, true)
            if (!castOwnsPreviewPlayback && localPreviewPlaybackRequested) {
                playbackSession.setPlaying(true)
            }
        }
        override fun onPlaying(engineName: String) {
            if (castOwnsPreviewPlayback || castLoadPending) return
            val canHideLoading = previewLoadingPolicy.shouldHideOnPlaying(
                engineName = engineName,
                expectsVideo = !radioMode,
            )
            PlaybackQoeRuntime.markEngine(
                previewQoeSessionId,
                LivePlaybackQoePolicy.engine(engineName),
            )
            PlaybackQoeRuntime.markReady(previewQoeSessionId)
            PlaybackQoeRuntime.setRebuffering(previewQoeSessionId, false)
            if (!castOwnsPreviewPlayback) {
                localPreviewPlaybackRequested = true
                playbackSession.setPlaying(true)
            }
            // Initial TV startup still waits for a freshly verified frame. After
            // an ordinary rebuffer, the already-verified surface may become ready
            // from Playing even when the backend has no second first-frame event.
            if (canHideLoading) {
                if (radioMode) {
                    PlaybackQoeRuntime.markFirstFrame(previewQoeSessionId)
                    showPreviewIdentityPlaceholder()
                }
                markPreviewReady()
            }
        }
        override fun onPlaybackRestarting() {
            if (castOwnsPreviewPlayback || castLoadPending) return
            previewState = LivePreviewPressPolicy.Phase.STARTING
            previewLoadingPolicy.requireFreshFrame()
            binding.previewPlaybackCover.visibility = View.VISIBLE
            if (!radioMode) showPreviewIdentityPlaceholder()
            binding.previewLoading.visibility = View.VISIBLE
            binding.previewStatus.setText(R.string.buffering)
            binding.previewStatus.visibility = View.VISIBLE
            PlaybackQoeRuntime.setRebuffering(previewQoeSessionId, true)
        }
        override fun onVideoResumed() {
            if (castOwnsPreviewPlayback || castLoadPending) return
            previewLoadingPolicy.onVideoResumed()
            binding.infoLogo.visibility = View.GONE
            PlaybackQoeRuntime.markFirstFrame(previewQoeSessionId)
            PlaybackQoeRuntime.setRebuffering(previewQoeSessionId, false)
            markPreviewReady()
        }
        override fun onEngineChanged(engineName: String) {
            if (castOwnsPreviewPlayback || castLoadPending) return
            previewLoadingPolicy.onEngineChanged(engineName)
            PlaybackQoeRuntime.markEngine(
                previewQoeSessionId,
                LivePlaybackQoePolicy.engine(engineName),
            )
        }
        override fun onTransportResolved(format: StreamFormat) {
            resolvedPreviewStreamFormat = format
            val transport = LivePlaybackQoePolicy.transport(format)
            if (
                previewQoeSessionId == null &&
                localPreviewPlaybackRequested &&
                !castOwnsPreviewPlayback
            ) {
                beginPreviewQoe(format)
            } else {
                PlaybackQoeRuntime.markTransport(previewQoeSessionId, transport)
            }
        }
        override fun onPlaybackFailure(failure: PlaybackFailure) {
            if (castOwnsPreviewPlayback || castLoadPending) return
            PlaybackQoeRuntime.recordFailure(previewQoeSessionId, failure)
        }
        override fun onLocalProcessRecoveryRequired(): Boolean {
            val launched = PlaybackProcessRecovery.requestIfRequired(
                this@HomeActivity,
                reason = "preview_native_owner_unresolved",
            )
            return launched ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        override fun onFatalError() {
            if (castOwnsPreviewPlayback || castLoadPending) return
            binding.previewPlaybackCover.visibility = View.VISIBLE
            showPreviewIdentityPlaceholder()
            binding.previewVideo.keepScreenOn = false
            previewState = LivePreviewPressPolicy.Phase.FAILED
            pendingFullscreenChannelId = null
            binding.previewLoading.visibility = View.GONE
            binding.previewStatus.setText(R.string.preview_playback_failed)
            binding.previewStatus.visibility = View.VISIBLE
            PlaybackQoeRuntime.setRebuffering(previewQoeSessionId, false)
            playbackSession.setPlaying(false)
            finishPreviewQoe(PlaybackEndReason.FATAL_FAILURE)
        }
        override fun onStreamTooHeavyForDevice(width: Int, height: Int, codec: String?) {
            if (castOwnsPreviewPlayback || castLoadPending) return
            // Notice only: same status line as other preview failures, no focus
            // change, so the channel list keeps the D-pad.
            binding.previewLoading.visibility = View.GONE
            binding.previewStatus.setText(R.string.playback_too_heavy_for_device)
            binding.previewStatus.visibility = View.VISIBLE
        }
        override fun onRetrying(attempt: Int) {
            if (castOwnsPreviewPlayback || castLoadPending) return
            previewState = LivePreviewPressPolicy.Phase.STARTING
            previewLoadingPolicy.requireFreshFrame()
            binding.previewPlaybackCover.visibility = View.VISIBLE
            if (!radioMode) showPreviewIdentityPlaceholder()
            binding.previewLoading.visibility = View.VISIBLE
            binding.previewStatus.text = getString(R.string.reconnecting, attempt)
            binding.previewStatus.visibility = View.VISIBLE
            PlaybackQoeRuntime.setRebuffering(previewQoeSessionId, true)
        }
    }

    // ---- Platform playback ownership + anonymous QoE -------------------

    private fun preparePreviewPlaybackRequest(): ProviderStartGatePolicy.Decision {
        if (castOwnsPreviewPlayback || castLoadPending) {
            return ProviderStartGatePolicy.Decision.BLOCKED_BY_REMOTE_OWNER
        }
        val safety = ProviderConnectionSafety.snapshot()
        val gate = ProviderStartGatePolicy.decide(safety)
        if (gate != ProviderStartGatePolicy.Decision.READY) {
            localPreviewPlaybackRequested = false
            playbackSession.setPlaying(false)
            return when (gate) {
                ProviderStartGatePolicy.Decision.WAIT_FOR_LOCAL_CLEANUP -> {
                    deferPreviewUntilProviderDrain()
                    gate
                }
                ProviderStartGatePolicy.Decision.RECOVER_LOCAL_PROCESS -> {
                    val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    val launched = PlaybackProcessRecovery.requestIfRequired(
                        this,
                        reason = "preview_start_unresolved_native_owner",
                    )
                    if (launched || !resumed) gate
                    else ProviderStartGatePolicy.Decision.BLOCKED
                }
                else -> gate
            }
        }
        if (!playbackSession.requestAudioFocus()) {
            localPreviewPlaybackRequested = false
            playbackSession.setPlaying(false)
            return ProviderStartGatePolicy.Decision.BLOCKED
        }
        localPreviewPlaybackRequested = true
        playbackSession.setPlaying(true)
        return ProviderStartGatePolicy.Decision.READY
    }

    private fun requestPreviewProcessRecovery(reason: String): Boolean =
        PlaybackProcessRecovery.requestIfRequired(
            this,
            reason = reason,
        )

    private fun deferPreviewUntilProviderDrain() {
        if (providerDrainPending) return
        providerDrainPending = true
        previewState = LivePreviewPressPolicy.Phase.STARTING
        binding.previewLoading.visibility = View.VISIBLE
        binding.previewStatus.setText(R.string.buffering)
        binding.previewStatus.visibility = View.VISIBLE
        VlcOps.awaitProviderDrain {
            runOnUiThread {
                providerDrainPending = false
                if (
                    isFinishing ||
                    isDestroyed ||
                    castOwnsPreviewPlayback ||
                    castLoadPending ||
                    previewingChannel == null ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    return@runOnUiThread
                }
                var afterDrain = ProviderConnectionSafety.snapshot()
                if (
                    !afterDrain.remoteUncertain &&
                    afterDrain.localPendingCount > 0 &&
                    !afterDrain.localProcessRecoveryRequired
                ) {
                    ProviderConnectionSafety.requireProcessRecoveryForPendingLocalStops()
                    afterDrain = ProviderConnectionSafety.snapshot()
                }
                when (ProviderStartGatePolicy.decide(afterDrain)) {
                    ProviderStartGatePolicy.Decision.READY -> resumePreviewPlayback()
                    ProviderStartGatePolicy.Decision.RECOVER_LOCAL_PROCESS -> {
                        val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        if (
                            !requestPreviewProcessRecovery(
                                "preview_provider_drain_unresolved",
                            ) &&
                            resumed
                        ) {
                            binding.previewLoading.visibility = View.GONE
                            binding.previewStatus.setText(
                                R.string.error_playback_ownership_uncertain,
                            )
                            binding.previewStatus.visibility = View.VISIBLE
                        }
                    }
                    ProviderStartGatePolicy.Decision.WAIT_FOR_LOCAL_CLEANUP -> {
                        // A new owner entered teardown after our FIFO marker.
                        // Requeue without surfacing an error.
                        deferPreviewUntilProviderDrain()
                    }
                    ProviderStartGatePolicy.Decision.BLOCKED_BY_REMOTE_OWNER,
                    ProviderStartGatePolicy.Decision.BLOCKED -> {
                        binding.previewLoading.visibility = View.GONE
                        binding.previewStatus.setText(
                            R.string.error_playback_ownership_uncertain,
                        )
                        binding.previewStatus.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun resumePreviewPlayback() {
        if (
            castOwnsPreviewPlayback ||
            castLoadPending ||
            previewingChannel == null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        when (preparePreviewPlaybackRequest()) {
            ProviderStartGatePolicy.Decision.READY -> Unit
            ProviderStartGatePolicy.Decision.WAIT_FOR_LOCAL_CLEANUP,
            ProviderStartGatePolicy.Decision.RECOVER_LOCAL_PROCESS -> return
            ProviderStartGatePolicy.Decision.BLOCKED_BY_REMOTE_OWNER,
            ProviderStartGatePolicy.Decision.BLOCKED -> {
                binding.previewLoading.visibility = View.GONE
                binding.previewStatus.setText(
                    if (ProviderConnectionSafety.newConnectionAllowed) {
                        R.string.error_audio_focus_unavailable
                    } else {
                        R.string.error_playback_ownership_uncertain
                    },
                )
                binding.previewStatus.visibility = View.VISIBLE
                return
            }
        }
        if (!previewStreamSubmitted) {
            val channel = previewingChannel ?: return
            previewState = LivePreviewPressPolicy.Phase.STARTING
            previewJob?.cancel()
            previewJob = lifecycleScope.launch { startPreview(channel) }
            return
        }
        val controller = previewController ?: return
        beginPreviewQoe()
        previewState = LivePreviewPressPolicy.Phase.STARTING
        controller.resume()
    }

    private fun pausePreviewPlayback(abandonFocus: Boolean) {
        localPreviewPlaybackRequested = false
        PlaybackQoeRuntime.setRebuffering(previewQoeSessionId, false)
        playbackSession.setPlaying(false)
        previewController?.pause()
        if (abandonFocus) playbackSession.abandonAudioFocus()
    }

    private fun onPreviewCastStarted() {
        if (castOwnsPreviewPlayback) return
        castLoadPending = false
        castOwnsPreviewPlayback = true
        finishPreviewQoe(PlaybackEndReason.REPLACED)
        // The preflight already confirmed the local provider socket is closed.
        previewState = LivePreviewPressPolicy.Phase.READY
        binding.previewLoading.visibility = View.GONE
        binding.previewStatus.visibility = View.GONE
    }

    private fun onPreviewCastLoadStarting(onQuiesced: (Boolean) -> Unit) {
        if (castOwnsPreviewPlayback) {
            onQuiesced(true)
            return
        }
        if (castLoadPending) return
        castLoadPending = true
        localPreviewPlaybackRequested = false
        PlaybackQoeRuntime.setRebuffering(previewQoeSessionId, false)
        playbackSession.setPlaying(false)
        playbackSession.abandonAudioFocus()
        previewState = LivePreviewPressPolicy.Phase.STARTING
        binding.previewLoading.visibility = View.VISIBLE
        binding.previewStatus.setText(R.string.buffering)
        binding.previewStatus.visibility = View.VISIBLE
        val controller = previewController
        if (controller == null) {
            onQuiesced(true)
        } else {
            controller.quiesce(onQuiesced)
        }
    }

    private fun onPreviewCastQuiesceFailed() {
        castLoadPending = false
        castOwnsPreviewPlayback = false
        suppressConnectedCastReloadOnce = true
        finishPreviewQoe(PlaybackEndReason.FATAL_FAILURE)
        previewState = LivePreviewPressPolicy.Phase.FAILED
        binding.previewPlaybackCover.visibility = View.VISIBLE
        binding.previewLoading.visibility = View.GONE
        binding.previewStatus.setText(R.string.error_playback_ownership_uncertain)
        binding.previewStatus.visibility = View.VISIBLE
    }

    private fun onPreviewCastLoadFailed() {
        castLoadPending = false
        suppressConnectedCastReloadOnce = true
        val channel = previewingChannel ?: return
        if (
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        resolvedPreviewStreamFormat = null
        previewStreamSubmitted = false
        previewState = LivePreviewPressPolicy.Phase.STARTING
        previewLoadingPolicy.requireFreshFrame()
        binding.previewPlaybackCover.visibility = View.VISIBLE
        binding.previewLoading.visibility = View.VISIBLE
        binding.previewStatus.setText(R.string.buffering)
        binding.previewStatus.visibility = View.VISIBLE
        previewJob?.cancel()
        previewJob = lifecycleScope.launch { startPreview(channel) }
    }

    private fun onPreviewCastEnded() {
        if (!castOwnsPreviewPlayback) return
        castOwnsPreviewPlayback = false
        castLoadPending = false
        suppressConnectedCastReloadOnce = true
        val channel = previewingChannel ?: return
        if (
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        // Cast may have zapped while local playback was suspended. Cold-start the
        // latest selected channel instead of resuming the controller's stale URL.
        resolvedPreviewStreamFormat = null
        previewStreamSubmitted = false
        previewState = LivePreviewPressPolicy.Phase.STARTING
        previewLoadingPolicy.requireFreshFrame()
        binding.previewPlaybackCover.visibility = View.VISIBLE
        binding.previewLoading.visibility = View.VISIBLE
        binding.previewStatus.setText(R.string.buffering)
        binding.previewStatus.visibility = View.VISIBLE
        previewJob?.cancel()
        previewJob = lifecycleScope.launch { startPreview(channel) }
    }

    private fun beginPreviewQoe(
        format: StreamFormat = resolvedPreviewStreamFormat ?: previewStreamFormat,
    ) {
        if (
            previewQoeSessionId != null ||
            castOwnsPreviewPlayback ||
            previewingChannel == null
        ) return
        val descriptor = LivePlaybackQoePolicy.sessionDescriptor(
            radio = radioMode,
        )
        previewQoeSessionId = PlaybackQoeRuntime.start(
            kind = descriptor.content,
            engine = PlaybackEngineKind.UNKNOWN,
            transport = descriptor.transport,
        )
    }

    private fun finishPreviewQoe(reason: PlaybackEndReason) {
        val id = previewQoeSessionId ?: return
        previewQoeSessionId = null
        PlaybackQoeRuntime.setRebuffering(id, false)
        PlaybackQoeRuntime.finish(id, reason)
    }

    /**
     * Expands the existing preview in place. No player API is called here: the
     * SurfaceView never leaves its parent, so the decoder and network socket are
     * unchanged across preview -> fullscreen and fullscreen -> preview.
     */
    private fun enterPreviewFullscreen() {
        if (
            inlineFullscreen ||
            previewState != LivePreviewPressPolicy.Phase.READY ||
            previewController == null
        ) return

        inlineFullscreen = true
        previewController?.setDisplayModeSwitchingAllowed(true)
        fullscreenGuideVisible = false
        fullscreenConfirmPress.clear()
        // Compatibility devices: guide rows bind placeholders only while the
        // decoder owns the CPU (restored, and visible rows rebound, on exit).
        channelAdapter.suppressLogos = compatMode
        fullscreenGuideAdapter.suppressLogos = compatMode
        rowFocuser.invalidate()
        fullscreenLayout.enterPanes()
        showPreviewIdentityPlaceholder(show = false)
        binding.fullscreenGuideOverlay.visibility = View.GONE
        fullscreenEpgCard.updateGuideLayout(guideVisible = false)
        previewingChannel?.let(fullscreenEpgCard::bindMeta)
        if (captionEpgLoaded) {
            fullscreenEpgCard.bind(captionPrograms, System.currentTimeMillis())
        } else {
            fullscreenEpgCard.renderLoading()
        }
        binding.fullscreenEpgOverlay.visibility = View.VISIBLE
        fullscreenLayout.enterMetrics()
        startFullscreenEpgTicker()
        scheduleFullscreenEpgAutoHide()
    }

    /** Restores the browser layout without touching playback. */
    private fun exitPreviewFullscreen(restoreFocus: Boolean = true) {
        if (!inlineFullscreen) return

        closeFullscreenGuide(showPlayingEpg = false)
        inlineFullscreen = false
        previewController?.setDisplayModeSwitchingAllowed(false)
        pendingFullscreenChannelId = null
        fullscreenConfirmPress.clear()
        if (channelAdapter.suppressLogos) {
            channelAdapter.suppressLogos = false
            fullscreenGuideAdapter.suppressLogos = false
            channelAdapter.refreshVisible(binding.channelList)
        }
        fullscreenZap.cancel()
        stopFullscreenEpgTicker()
        rowFocuser.invalidate()
        fullscreenLayout.exitPanes()
        binding.fullscreenGuideOverlay.visibility = View.GONE
        binding.fullscreenEpgOverlay.visibility = View.GONE
        fullscreenEpgCard.updateGuideLayout(guideVisible = false)
        binding.catchupHint.visibility =
            if ((currentInfoChannel?.catchupDays ?: 0) > 0) View.VISIBLE else View.GONE
        restoreBrowseGuideFromPlayingCaption()
        fullscreenLayout.exitCard()

        if (!restoreFocus) return
        val playing = previewingChannel
        val playingId = playing?.id
        val position = currentChannels.indexOfFirst { it.id == playingId }
        when {
            inChannelView && position >= 0 -> {
                lastFocusedChannelId = playingId
                rowFocuser.focusRow(binding.channelList, position, center = true)
            }
            inChannelView && currentChannels.isNotEmpty() -> focusFirstChannel()
            categoryAdapter.itemCount > 0 -> {
                val categoryId = lastSelectedCategoryId
                if (categoryId != null) focusCategory(categoryId)
                else rowFocuser.focusRow(binding.categoryList, 0)
            }
        }
        // No inline preview wanted: leaving fullscreen ends the stream (focus is
        // already on the playing row above) and the card shows now/next text.
        if (!livePreviewEnabled && playing != null) {
            stopPreview()
            showInfo(playing)
        }
    }

    /** Reuses the already-loaded playing-channel EPG when returning to the browser. */
    private fun restoreBrowseGuideFromPlayingCaption() {
        val channel = previewingChannel ?: return
        if (!captionEpgLoaded || currentInfoChannel?.id != channel.id) return
        nowNextJob?.cancel()
        val programs = captionPrograms
        val now = System.currentTimeMillis()
        currentPrograms = programs
        binding.guideChannelTitle.text = ChannelText.clean(channel.name)
        binding.epgLoading.visibility = View.GONE
        binding.epgEmpty.visibility = if (programs.isEmpty()) View.VISIBLE else View.GONE
        epgAdapter.submitList(programs) {
            val current = programs.indexOfFirst { it.isLiveAt(now) }
            if (current >= 0) binding.epgList.scrollToPosition(current)
        }
    }

    /** INFO toggles the playing-channel card; every reveal starts a fresh four seconds. */
    private fun toggleFullscreenCaption() {
        fullscreenEpgTimer.cancelAutoHide()
        if (binding.fullscreenEpgOverlay.visibility == View.VISIBLE) {
            binding.fullscreenEpgOverlay.visibility = View.GONE
        } else {
            showPlayingFullscreenEpg()
        }
    }

    /** Keeps progress/remaining time exact while the fullscreen card is active. */
    private fun startFullscreenEpgTicker() {
        fullscreenEpgTimer.startTicker()
    }

    private fun stopFullscreenEpgTicker() {
        fullscreenEpgTimer.stopAll()
    }

    /** CH+/CH- always reveals the new channel's programme information for four seconds. */
    private fun revealFullscreenEpgForChannelChange() {
        fullscreenEpgTimer.cancelAutoHide()
        if (fullscreenGuideVisible) closeFullscreenGuide(showPlayingEpg = false)
        previewingChannel?.let(fullscreenEpgCard::bindMeta)
        if (captionEpgLoaded) {
            fullscreenEpgCard.bind(captionPrograms, System.currentTimeMillis())
        } else {
            fullscreenEpgCard.renderLoading()
        }
        binding.fullscreenEpgOverlay.visibility = View.VISIBLE
        scheduleFullscreenEpgAutoHide()
    }

    /** Starts the timer only after usable EPG (including an empty result) is visible. */
    private fun scheduleFullscreenEpgAutoHide() {
        fullscreenEpgTimer.cancelAutoHide()
        if (
            inlineFullscreen &&
            !fullscreenGuideVisible &&
            captionEpgLoaded &&
            binding.fullscreenEpgOverlay.visibility == View.VISIBLE
        ) {
            fullscreenEpgTimer.postAutoHide()
        }
    }

    private fun showPlayingFullscreenEpg() {
        val channel = previewingChannel ?: return
        fullscreenEpgTimer.cancelAutoHide()
        fullscreenEpgCard.bindMeta(channel)
        if (captionEpgLoaded) {
            fullscreenEpgCard.bind(captionPrograms, System.currentTimeMillis())
        } else {
            fullscreenEpgCard.renderLoading()
        }
        binding.fullscreenEpgOverlay.visibility = View.VISIBLE
        scheduleFullscreenEpgAutoHide()
    }

    /**
     * Opens a separate, remote-first rail over the existing SurfaceView. Merely
     * focusing a row changes only the EPG card; playback changes only on OK.
     */
    private fun openFullscreenGuide() {
        if (!inlineFullscreen || fullscreenGuideVisible) return
        val playing = previewingChannel
        val channels = previewChannelScope
            .ifEmpty { currentChannels }
            .ifEmpty { listOfNotNull(playing) }

        fullscreenZap.cancel()
        fullscreenEpgTimer.cancelAutoHide()
        fullscreenGuideEpgJob?.cancel()
        fullscreenGuideEpgGeneration++
        fullscreenGuideVisible = true
        fullscreenConfirmPress.clear()
        rowFocuser.invalidate()

        binding.fullscreenGuideCategory.text =
            previewChannelScopeLabel?.takeIf { it.isNotBlank() }
                ?: playing?.categoryName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.channels_title)
        binding.fullscreenGuideCount.text =
            getString(R.string.browse_count_format, channels.size)
        binding.fullscreenGuideEmpty.visibility =
            if (channels.isEmpty()) View.VISIBLE else View.GONE
        binding.fullscreenGuideList.visibility =
            if (channels.isEmpty()) View.GONE else View.VISIBLE
        fullscreenGuideAdapter.setPlayingChannel(playing?.id)
        val entranceOffset = resources.getDimension(R.dimen.space_l)
        binding.fullscreenGuideOverlay.animate().cancel()
        binding.fullscreenGuidePanel.animate().cancel()
        binding.fullscreenGuideOverlay.alpha = 0f
        binding.fullscreenGuidePanel.translationX =
            if (binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                entranceOffset
            } else {
                -entranceOffset
            }
        binding.fullscreenGuideOverlay.visibility = View.VISIBLE
        binding.fullscreenGuideOverlay.animate()
            .alpha(1f)
            .setDuration(160L)
            .start()
        binding.fullscreenGuidePanel.animate()
            .translationX(0f)
            .setDuration(190L)
            .start()
        fullscreenEpgCard.updateGuideLayout(guideVisible = true)

        fullscreenGuideAdapter.submitList(channels) {
            if (!fullscreenGuideVisible || channels.isEmpty()) return@submitList
            val playingPosition = channels.indexOfFirst { it.id == playing?.id }
            rowFocuser.focusRow(
                binding.fullscreenGuideList,
                playingPosition.takeIf { it >= 0 } ?: 0,
                center = playingPosition > 0,
            )
        }

        val initial = channels.firstOrNull { it.id == playing?.id } ?: channels.firstOrNull()
        if (initial != null) {
            showFullscreenGuideEpg(initial)
        } else {
            binding.fullscreenEpgOverlay.visibility = View.GONE
        }
    }

    private fun closeFullscreenGuide(showPlayingEpg: Boolean = true) {
        if (!fullscreenGuideVisible) return
        fullscreenGuideVisible = false
        fullscreenGuideFocusedChannel = null
        fullscreenGuidePrograms = emptyList()
        fullscreenGuideEpgLoaded = false
        fullscreenGuideEpgJob?.cancel()
        fullscreenGuideEpgJob = null
        fullscreenGuideEpgGeneration++
        rowFocuser.invalidate()
        binding.fullscreenGuideOverlay.animate().cancel()
        binding.fullscreenGuidePanel.animate().cancel()
        binding.fullscreenGuideOverlay.alpha = 1f
        binding.fullscreenGuidePanel.translationX = 0f
        binding.fullscreenGuideList.clearFocus()
        binding.fullscreenGuideOverlay.visibility = View.GONE
        fullscreenEpgCard.updateGuideLayout(guideVisible = false)
        if (showPlayingEpg && inlineFullscreen) {
            showPlayingFullscreenEpg()
        } else {
            fullscreenEpgTimer.cancelAutoHide()
            binding.fullscreenEpgOverlay.visibility = View.GONE
        }
    }

    /** Loads EPG for the focused guide row without touching player/controller state. */
    private fun showFullscreenGuideEpg(channel: Channel) {
        if (!fullscreenGuideVisible) return
        fullscreenGuideFocusedChannel = channel
        fullscreenGuidePrograms = emptyList()
        fullscreenGuideEpgLoaded = false
        fullscreenGuideEpgJob?.cancel()
        val requestGeneration = ++fullscreenGuideEpgGeneration
        fullscreenEpgTimer.cancelAutoHide()
        binding.fullscreenEpgOverlay.visibility = View.VISIBLE

        if (isChannelLocked(channel)) {
            fullscreenEpgCard.bindLocked()
            fullscreenGuideEpgLoaded = true
            return
        }

        fullscreenEpgCard.bindMeta(channel)
        fullscreenEpgCard.renderLoading()
        if (channel.id == previewingChannel?.id && captionEpgLoaded) {
            fullscreenGuidePrograms = captionPrograms
            fullscreenGuideEpgLoaded = true
            fullscreenEpgCard.bind(captionPrograms, System.currentTimeMillis())
            return
        }

        fullscreenGuideEpgJob = lifecycleScope.launch {
            try {
                delay(EPG_DEBOUNCE_MS)
                val programs = viewModel.programs(channel)
                if (
                    requestGeneration != fullscreenGuideEpgGeneration ||
                    !fullscreenGuideVisible ||
                    fullscreenGuideFocusedChannel?.id != channel.id ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) return@launch
                fullscreenGuidePrograms = programs
                fullscreenGuideEpgLoaded = true
                fullscreenEpgCard.bind(programs, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (
                    requestGeneration != fullscreenGuideEpgGeneration ||
                    !fullscreenGuideVisible ||
                    fullscreenGuideFocusedChannel?.id != channel.id
                ) return@launch
                fullscreenGuidePrograms = emptyList()
                fullscreenGuideEpgLoaded = true
                fullscreenEpgCard.bind(emptyList(), System.currentTimeMillis())
            }
        }
    }

    private fun selectFullscreenGuideChannel(channel: Channel) {
        val scope = fullscreenGuideAdapter.currentList.toList()
        lastFocusedChannelId = channel.id
        previewChannelScope = scope
        closeFullscreenGuide(showPlayingEpg = false)
        requestChannelPlayback(channel, enterFullscreenWhenReady = true)
    }

    private fun toggleFullscreenGuideFavorite(channel: Channel) {
        val updated = channel.copy(isFavorite = !channel.isFavorite)
        viewModel.toggleFavorite(channel.id)
        fullscreenGuideAdapter.replaceChannel(updated)
        previewChannelScope = previewChannelScope.map {
            if (it.id == updated.id) updated else it
        }
        fullscreenGuideFocusedChannel = updated
        if (previewingChannel?.id == updated.id) {
            previewingChannel = updated
            fullscreenGuideAdapter.setPlayingChannel(updated.id)
        }
        if (currentInfoChannel?.id == updated.id) currentInfoChannel = updated
        Toast.makeText(
            this,
            if (updated.isFavorite) R.string.added_to_favorites
            else R.string.removed_from_favorites,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun togglePreviewFavorite() {
        val channel = previewingChannel ?: return
        val favorite = !channel.isFavorite
        viewModel.toggleFavorite(channel.id)
        val updated = channel.copy(isFavorite = favorite)
        previewingChannel = updated
        if (currentInfoChannel?.id == updated.id) currentInfoChannel = updated
        previewChannelScope = previewChannelScope.map {
            if (it.id == updated.id) updated else it
        }
        Toast.makeText(
            this,
            if (favorite) R.string.added_to_favorites else R.string.removed_from_favorites,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun numberZapChannels(): List<Channel> =
        if (inlineFullscreen) previewChannelScope.ifEmpty { currentChannels }
        else currentChannels

    private fun zapFullscreenChannel(direction: Int) {
        val channels = previewChannelScope.ifEmpty { currentChannels }
        if (channels.isEmpty()) return
        val currentId = fullscreenZap.pending?.id ?: previewingChannel?.id
        val target = channels[HomeZapTarget.nextIndex(channels, currentId, direction)]
        lastFocusedChannelId = target.id
        if (isChannelLocked(target)) {
            fullscreenZap.cancel()
            requestChannelPlayback(target, enterFullscreenWhenReady = true)
            return
        }
        fullscreenZap.arm(target)
    }

    override fun onDestroy() {
        super.onDestroy()
        PlaybackResourceGovernor.end(playbackResourceToken)
        playbackResourceToken = null
        sleepTimer.release()
        castController.detach()
        finishPreviewQoe(PlaybackEndReason.APP_SHUTDOWN)
        playbackSession.release()
        fullscreenEpgTimer.stopAll()
        fullscreenGuideEpgJob?.cancel()
        debugBinder?.release()
    }

    /** Stops + releases the preview so the single stream connection is freed. */
    private fun stopPreview(
        endReason: PlaybackEndReason = PlaybackEndReason.USER_STOP,
    ) {
        closeFullscreenGuide(showPlayingEpg = false)
        previewJob?.cancel()
        captionJob?.cancel()
        fullscreenGuideEpgJob?.cancel()
        fullscreenGuideEpgJob = null
        fullscreenGuideEpgGeneration++
        captionRequestGeneration++
        captionEpgLoaded = false
        lastCaptionEpgRequestAtMs = 0L
        stopFullscreenEpgTicker()
        finishPreviewQoe(endReason)
        if (!castLoadPending) {
            // This path permanently retires the preview controller. Do not queue
            // stopAndThen() and release() back-to-back: one full release closes
            // the provider socket and native decoder with less queue latency.
            localPreviewPlaybackRequested = false
            PlaybackQoeRuntime.setRebuffering(previewQoeSessionId, false)
            playbackSession.setPlaying(false)
            playbackSession.abandonAudioFocus()
        } else {
            localPreviewPlaybackRequested = false
            playbackSession.setPlaying(false)
            playbackSession.abandonAudioFocus()
        }
        val retiringController = previewController
        retiringController?.release()
        previewController = null
        val resourceToken = playbackResourceToken
        playbackResourceToken = null
        if (retiringController == null) {
            PlaybackResourceGovernor.end(resourceToken)
        } else {
            // PlayerController queues any blocking VLC teardown on the shared FIFO.
            // Release the background-work gate only after that queue reaches this
            // marker; Exo/no-VLC paths pass it immediately.
            VlcOps.post {
                runOnUiThread { PlaybackResourceGovernor.end(resourceToken) }
            }
        }
        previewingChannel = null
        NowPlaying.clear(this)
        previewState = LivePreviewPressPolicy.Phase.IDLE
        previewLoadingPolicy.requireFreshFrame()
        previewStreamSubmitted = false
        resolvedPreviewStreamFormat = null
        pendingFullscreenChannelId = null
        previewChannelScope = emptyList()
        previewChannelScopeLabel = null
        fullscreenZap.cancel()
        captionPrograms = emptyList()
        fullscreenGuidePrograms = emptyList()
        fullscreenGuideEpgLoaded = false
        fullscreenGuideAdapter.setPlayingChannel(null)
        binding.fullscreenGuideOverlay.visibility = View.GONE
        binding.fullscreenEpgOverlay.visibility = View.GONE
        fullscreenEpgCard.renderLoading()
        binding.infoLogo.visibility = View.VISIBLE
        binding.previewLoading.visibility = View.GONE
        binding.previewStatus.visibility = View.GONE
        // Preview is gone: release the screen-on hold so we're not pinning the
        // display awake when nothing is playing on Home.
        binding.previewVideo.keepScreenOn = false
    }

    override fun onStart() {
        super.onStart()
        playbackSession.setActive(true)
        externalNavigationInFlight = false
        // Coming back from Settings/Catch-up: restore the channel row after the
        // lifecycle-scoped list collector re-subscribes.
        if (inChannelView) pendingChannelFocusRestore = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The key UP for a consumed DOWN is delivered to whichever window has
        // focus. Once a dialog takes it, this Activity never sees that UP.
        if (!hasFocus) consumedUntilUp.clear()
    }

    override fun onStop() {
        playbackSession.setActive(false)
        super.onStop()
        // External navigation really leaves Home, so restore its browse layout for
        // the next start and then release the sole live connection.
        exitPreviewFullscreen(restoreFocus = false)
        stopPreview(PlaybackEndReason.BACKGROUND)
        // Stop any in-flight EPG fetch so the guide isn't loaded off-screen.
        nowNextJob?.cancel()
        binding.epgLoading.visibility = View.GONE
        // Drop any pending number-zap commit so a delayed lookup can't launch the
        // player after we've left the screen (and to release the buffer/refs).
        zap.cancel()
        pendingEnterCategoryId = null
        consumedUntilUp.clear()
        pinGate.reset()
    }
}
