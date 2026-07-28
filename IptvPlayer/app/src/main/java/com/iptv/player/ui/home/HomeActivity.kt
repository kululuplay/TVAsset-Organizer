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
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.cast.CastController
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.Program
import com.iptv.player.databinding.ActivityHomeBinding
import com.iptv.player.player.PlayerController
import com.iptv.player.ui.catchup.CatchupActivity
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.ChannelText
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.NewContentPopup
import com.iptv.player.ui.common.NumberZapInputHelper
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.SleepTimer
import com.iptv.player.ui.common.hideSoftKeyboard
import com.iptv.player.ui.common.isAdult
import com.iptv.player.player.LiveStreamUrl
import com.iptv.player.ui.player.PlayerDialogs
import com.iptv.player.ui.player.RemoteConfirmPress
import com.iptv.player.util.AutoRetryPolicy
import com.iptv.player.util.DebugOverlayBinder
import com.iptv.player.util.NewContentNotifier
import com.iptv.player.util.NowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

class HomeActivity : BaseActivity() {

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

    /** Debounce job so scrolling channels doesn't thrash the single stream socket. */
    private var previewJob: kotlinx.coroutines.Job? = null

    /** Explicit playback state keeps rapid/double OK presses deterministic. */
    private var previewState = LivePreviewPressPolicy.Phase.IDLE

    /**
     * Fullscreen is a layout state of this Activity, not a second player screen.
     * The same controller, SurfaceView, decoder and network connection stay alive.
     */
    private var inlineFullscreen = false
    private var pendingFullscreenChannelId: String? = null

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

    /** Collapses fast CH+/- taps before touching VLC/Exo on slower TV chipsets. */
    private val fullscreenZapHandler = Handler(Looper.getMainLooper())
    private var pendingFullscreenZapChannel: Channel? = null

    /** One-second fullscreen EPG clock/progress updates + four-second auto-hide. */
    private val fullscreenEpgHandler = Handler(Looper.getMainLooper())
    private val hideFullscreenEpg = Runnable {
        if (inlineFullscreen && !fullscreenGuideVisible) {
            binding.fullscreenEpgOverlay.visibility = View.GONE
        }
    }
    private val fullscreenEpgTick = object : Runnable {
        override fun run() {
            if (
                !inlineFullscreen ||
                isFinishing ||
                isDestroyed ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return
            val now = System.currentTimeMillis()
            if (binding.fullscreenEpgOverlay.visibility == View.VISIBLE) {
                if (fullscreenGuideVisible && fullscreenGuideEpgLoaded) {
                    bindFullscreenEpg(fullscreenGuidePrograms, now)
                } else if (!fullscreenGuideVisible && captionEpgLoaded) {
                    bindFullscreenEpg(captionPrograms, now)
                }
            }
            if (captionEpgLoaded) {
                maybeRefreshExpiredCaptionEpg(now)
            }
            fullscreenEpgHandler.postDelayed(this, FULLSCREEN_EPG_TICK_MS)
        }
    }

    /** Continues a bounded, slow retry schedule after the controller exhausts its own reconnects. */
    private val autoRetryHandler = Handler(Looper.getMainLooper())
    private var autoRetryAttempt = 0

    private data class BrowseLayoutSnapshot(
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val splitPercent: Float,
        val previewTopMargin: Int,
        val previewWeight: Float,
        val previewClipToOutline: Boolean,
        val previewFocusable: Boolean,
        val previewClickable: Boolean,
    )

    private lateinit var browseLayoutSnapshot: BrowseLayoutSnapshot

    /** Prevents repeated OK events from stacking multiple parental dialogs/actions. */
    private var pinPromptInFlight = false
    private var pinRequestGeneration = 0
    private var pinGuardRequest: PinLockHelper.Request? = null

    /** Swallows repeats and matching UP after a pre-dispatch key action. */
    private val consumedUntilUp = mutableSetOf<Int>()

    /** Matches fullscreen confirm DOWN/UP so long-OK favorite remains available. */
    private val fullscreenConfirmPress = RemoteConfirmPress()

    private val sleepTimer = SleepTimer { finish() }
    private val castController by lazy {
        CastController(this) {
            val channel = previewingChannel ?: return@CastController null
            CastController.CastMedia(
                channel.streamUrl,
                ChannelText.clean(channel.name),
                channel.logoUrl,
                isLive = true,
            )
        }
    }

    /** Invalidates delayed RecyclerView focus requests after a mode/layout change. */
    private var focusRequestGeneration = 0

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
        private const val STATS_DASH = "—"
    }

    private val zap by lazy {
        NumberZapInputHelper(
            lookup = { num -> numberZapChannels().firstOrNull { it.number == num } },
            onResolved = { channel ->
                cancelFullscreenZap()
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
                if (typed.isNotEmpty()) cancelFullscreenZap(hideOverlay = false)
                if (typed.isNotEmpty()) lastZapDigits = typed
                binding.zapOverlay.text = typed
                binding.zapOverlay.visibility = if (typed.isEmpty()) View.GONE else View.VISIBLE
                binding.fullscreenZapOverlay.text = typed
                binding.fullscreenZapOverlay.visibility =
                    if (inlineFullscreen && typed.isNotEmpty()) View.VISIBLE else View.GONE
            }
        )
    }

    /** True while the left pane shows the channel list (drilled into a category). */
    private val inChannelView: Boolean
        get() = binding.channelList.visibility == View.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        browseLayoutSnapshot = captureBrowseLayout()
        setContentView(binding.root)
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
        if (fullscreenGuideVisible && event.repeatCount == 0 && isNumberKey(keyCode)) {
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
        if (event.keyCode in consumedUntilUp) {
            if (event.action == KeyEvent.ACTION_UP) consumedUntilUp.remove(event.keyCode)
            return true
        }

        // RecyclerView rows normally consume OK before Activity.onKeyDown. Commit a
        // pending number zap here first so the same press cannot also click a row.
        if (
            event.action == KeyEvent.ACTION_DOWN &&
            zap.hasPendingInput &&
            isConfirmKey(event.keyCode)
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
            cancelsNumberZap(event.keyCode)
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
                        // Drill back to categories from the channel list (mirrors Back).
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
                            binding.previewCard.requestFocus()
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
                    if (event.repeatCount > 0 && binding.searchInput.hasFocus()) return true
                    if (binding.searchInput.hasFocus()) {
                        consumedUntilUp.add(event.keyCode)
                        binding.searchInput.hideSoftKeyboard()
                        when {
                            inChannelView && currentChannels.isNotEmpty() ->
                                focusRow(binding.channelList, 0)
                            categoryAdapter.itemCount > 0 -> {
                                val id = lastSelectedCategoryId
                                if (id != null) focusCategory(id)
                                else focusRow(binding.categoryList, 0)
                            }
                        }
                        return true
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
     */
    private fun dispatchFullscreenKey(event: KeyEvent): Boolean {
        if (fullscreenGuideVisible) return dispatchFullscreenGuideKey(event)

        if (isConfirmKey(event.keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) cancelFullscreenZap()
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

        val handledKey = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> true
            else -> false
        }
        if (!handledKey) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        if (
            event.keyCode != KeyEvent.KEYCODE_DPAD_UP &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_CHANNEL_UP &&
            event.keyCode != KeyEvent.KEYCODE_CHANNEL_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_PAGE_UP &&
            event.keyCode != KeyEvent.KEYCODE_PAGE_DOWN
        ) {
            cancelFullscreenZap()
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                exitPreviewFullscreen()
                consumedUntilUp.add(event.keyCode)
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> zapFullscreenChannel(+1)
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> zapFullscreenChannel(-1)
            KeyEvent.KEYCODE_MENU -> showInlinePlayerMenu()
            // INFO follows normal TV behaviour and controls the programme card.
            // Technical stream diagnostics remain available from MENU.
            KeyEvent.KEYCODE_INFO -> toggleFullscreenCaption()
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> openCatchup()
            // LEFT/RIGHT are intentionally consumed: there is no hidden focus target.
        }
        return true
    }

    /**
     * While the guide is open, RecyclerView owns D-pad and confirm events. Activity
     * handles only global player commands so focus can never escape to hidden panes.
     */
    private fun dispatchFullscreenGuideKey(event: KeyEvent): Boolean {
        if (isConfirmKey(event.keyCode)) return false

        val handledKey = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> true
            // Let the focused RecyclerView row handle ordinary vertical navigation.
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> false
            else -> false
        }
        if (!handledKey) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                closeFullscreenGuide()
                consumedUntilUp.add(event.keyCode)
            }
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                closeFullscreenGuide(showPlayingEpg = false)
                zapFullscreenChannel(+1)
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                closeFullscreenGuide(showPlayingEpg = false)
                zapFullscreenChannel(-1)
            }
            KeyEvent.KEYCODE_MENU -> {
                closeFullscreenGuide(showPlayingEpg = false)
                showInlinePlayerMenu()
            }
            // The dedicated guide already keeps programme information visible.
            KeyEvent.KEYCODE_INFO -> Unit
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> {
                closeFullscreenGuide(showPlayingEpg = false)
                openCatchup()
            }
            // RIGHT is deliberately consumed so focus stays inside the guide.
        }
        return true
    }

    private fun isConfirmKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A -> true
        else -> false
    }

    private fun isNumberKey(keyCode: Int): Boolean =
        keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9

    private fun cancelsNumberZap(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_PROG_RED -> true
        else -> false
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
                if (it.id != previewingChannel?.id) pendingFullscreenChannelId = null
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
        // Same fix as the category list: the channel list re-emits on favorite
        // toggles and lazy content loads, and the default change-animation detaches
        // the focused row mid-diff, causing D-pad focus loss. Rebind in place.
        (binding.channelList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        epgAdapter = ProgramAdapter()
        binding.epgList.layoutManager = LinearLayoutManager(this)
        binding.epgList.adapter = epgAdapter

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
                    focusRow(binding.channelList, 0)
                } else {
                    val id = lastSelectedCategoryId
                    if (id != null) focusCategory(id)
                    else if (categoryAdapter.itemCount > 0) focusRow(binding.categoryList, 0)
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
        binding.previewCard.nextFocusDownId = binding.previewCard.id
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
                                    focusRow(binding.channelList, pos, center = true)
                                } else if (channels.isNotEmpty()) {
                                    val playingPos = channels.indexOfFirst {
                                        it.id == previewingChannel?.id
                                    }
                                    focusRow(
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
                        delay(NOW_NEXT_REFRESH_MS)
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
        val hasCachedBrowseData = hasRealCategories || currentChannels.isNotEmpty()
        val blockingLoading = state.loading && !hasCachedBrowseData
        val blockingFailure = failed && !hasCachedBrowseData
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
        val showingChannels = inChannelView || viewModel.query.value.isNotBlank()
        val visibleItemCount =
            if (showingChannels) currentChannels.size else categoryAdapter.itemCount
        val empty =
            visibleItemCount == 0 && !state.loading && !blockingFailure
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        if (blockingFailure) {
            binding.retryButton.post { binding.retryButton.requestFocus() }
        } else if (restoreFocusAfterRetry && !state.loading) {
            restoreFocusAfterRetry = false
            when {
                inChannelView && currentChannels.isNotEmpty() -> focusFirstChannel()
                categoryAdapter.itemCount > 0 -> {
                    val id = lastSelectedCategoryId
                    if (id != null) focusCategory(id)
                    else focusRow(binding.categoryList, 0)
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
                    binding.previewProgram.text = nowPlayingLabel(programs, now)
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
        binding.previewNumber.visibility = if (number.isEmpty()) View.GONE else View.VISIBLE
        binding.previewLiveBadge.visibility = View.VISIBLE
        binding.previewCaptionLogoPlate.visibility = View.VISIBLE
        val cleanName = ChannelText.clean(channel.name)
        binding.previewTitle.text = cleanName
        if (previewingChannel == null) binding.infoLogo.visibility = View.VISIBLE
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
        bindFullscreenEpgMeta(channel)
    }

    /** Binds only the fullscreen card so guide focus cannot corrupt preview metadata. */
    private fun bindFullscreenEpgMeta(channel: Channel) {
        val number = channel.number?.toString().orEmpty()
        binding.fullscreenEpgNumber.text = number
        binding.fullscreenEpgNumber.visibility =
            if (number.isEmpty()) View.GONE else View.VISIBLE
        binding.fullscreenEpgChannelName.text = ChannelText.clean(channel.name)
        val category = channel.categoryName.orEmpty()
        binding.fullscreenEpgCategory.text = category
        binding.fullscreenEpgCategory.visibility =
            if (category.isBlank()) View.GONE else View.VISIBLE
        val placeholder = LogoPlaceholder.forName(this, channel.name)
        if (channel.logoUrl.isNullOrBlank()) {
            binding.fullscreenEpgLogo.load(placeholder) { crossfade(false) }
        } else {
            binding.fullscreenEpgLogo.load(channel.logoUrl) {
                crossfade(false)
                placeholder(placeholder)
                error(placeholder)
                size(160, 120)
            }
        }
    }

    /** Formats the "now playing" caption line from [programs], or "" if none is live. */
    private fun nowPlayingLabel(programs: List<Program>, now: Long): String {
        val current = programs.firstOrNull { it.isLiveAt(now) } ?: return ""
        return "${timeFmt.format(Date(current.startMs))} - " +
            "${timeFmt.format(Date(current.stopMs))}  ${current.title}"
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
            renderFullscreenEpgLoading()
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
                binding.previewProgram.text = nowPlayingLabel(programs, now)
                if (!fullscreenGuideVisible) {
                    bindFullscreenEpgMeta(channel)
                    bindFullscreenEpg(programs, now)
                    scheduleFullscreenEpgAutoHide()
                } else if (fullscreenGuideFocusedChannel?.id == channel.id) {
                    fullscreenGuidePrograms = programs
                    fullscreenGuideEpgLoaded = true
                    bindFullscreenEpg(programs, now)
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
                    bindFullscreenEpgMeta(channel)
                    bindFullscreenEpg(emptyList(), System.currentTimeMillis())
                    scheduleFullscreenEpgAutoHide()
                } else if (fullscreenGuideFocusedChannel?.id == channel.id) {
                    fullscreenGuidePrograms = emptyList()
                    fullscreenGuideEpgLoaded = true
                    bindFullscreenEpg(emptyList(), System.currentTimeMillis())
                }
                if (!inlineFullscreen && currentInfoChannel?.id == channel.id) {
                    restoreBrowseGuideFromPlayingCaption()
                }
            }
        }
    }

    /** Loading placeholder shown atomically as soon as a new channel is committed. */
    private fun renderFullscreenEpgLoading() {
        binding.fullscreenEpgNowTitle.setText(R.string.loading)
        binding.fullscreenEpgRemaining.visibility = View.GONE
        binding.fullscreenEpgProgress.progress = 0
        binding.fullscreenEpgProgress.visibility = View.GONE
        binding.fullscreenEpgTimingRow.visibility = View.GONE
        binding.fullscreenEpgStart.text = ""
        binding.fullscreenEpgEnd.text = ""
        binding.fullscreenEpgNextRow.visibility = View.GONE
        binding.fullscreenEpgNextTitle.text = ""
        binding.fullscreenEpgNextStart.text = ""
    }

    /** Binds current, progress, remaining time and next for the active EPG card. */
    private fun bindFullscreenEpg(programs: List<Program>, now: Long) {
        val state = LiveEpgOverlayPolicy.resolve(programs, now)
        val current = state.current
        if (current == null) {
            binding.fullscreenEpgNowTitle.setText(R.string.no_guide)
            binding.fullscreenEpgRemaining.visibility = View.GONE
            binding.fullscreenEpgProgress.progress = 0
            binding.fullscreenEpgProgress.visibility = View.GONE
            binding.fullscreenEpgTimingRow.visibility = View.GONE
            binding.fullscreenEpgStart.text = ""
            binding.fullscreenEpgEnd.text = ""
        } else {
            binding.fullscreenEpgNowTitle.text = current.title
            val remainingMs = (current.stopMs - now).coerceAtLeast(0L)
            binding.fullscreenEpgRemaining.text =
                if (remainingMs < 60_000L) {
                    getString(R.string.epg_ending)
                } else {
                    getString(
                        R.string.epg_min_left,
                        state.remainingMinutes ?: 0,
                    )
                }
            binding.fullscreenEpgRemaining.visibility = View.VISIBLE
            binding.fullscreenEpgProgress.progress = state.progressPercent
            binding.fullscreenEpgProgress.visibility = View.VISIBLE
            binding.fullscreenEpgStart.text = timeFmt.format(Date(current.startMs))
            binding.fullscreenEpgEnd.text = timeFmt.format(Date(current.stopMs))
            binding.fullscreenEpgTimingRow.visibility = View.VISIBLE
        }

        val next = state.next
        if (next == null) {
            binding.fullscreenEpgNextRow.visibility = View.GONE
            binding.fullscreenEpgNextTitle.text = ""
            binding.fullscreenEpgNextStart.text = ""
        } else {
            binding.fullscreenEpgNextTitle.text = next.title
            binding.fullscreenEpgNextStart.text = timeFmt.format(Date(next.startMs))
            binding.fullscreenEpgNextRow.visibility = View.VISIBLE
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
        binding.previewCaptionLogoPlate.visibility = View.VISIBLE
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
            binding.previewProgram.text = nowPlayingLabel(captionSource, now)
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
            if (pinPromptInFlight) return
            pinPromptInFlight = true
            val requestGeneration = ++pinRequestGeneration
            pinGuardRequest = PinLockHelper.guard(
                activity = this,
                isAdult = true,
                onDenied = {
                    if (requestGeneration == pinRequestGeneration) {
                        pinPromptInFlight = false
                        pinGuardRequest = null
                    }
                },
                onAllowed = allowed@{
                    if (
                        requestGeneration != pinRequestGeneration ||
                        !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ) return@allowed
                    pinPromptInFlight = false
                    pinGuardRequest = null
                    unlockedCategories.add(category.id)
                    channelAdapter.refreshVisible(binding.channelList)
                    enterChannelView()
                },
            )
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
        else if (categoryAdapter.itemCount > 0) focusRow(binding.categoryList, 0)
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
        focusRow(binding.channelList, pos, center = pos > 0)
    }

    /** Moves focus onto a given category row. */
    private fun focusCategory(id: String) {
        val pos = (0 until categoryAdapter.itemCount)
            .firstOrNull { categoryAdapter.currentList.getOrNull(it)?.id == id } ?: 0
        focusRow(binding.categoryList, pos)
    }

    /**
     * Focuses the row at [pos] in [list], retrying until it is laid out. When the
     * left pane was just toggled from GONE to VISIBLE the relayout is still pending,
     * so a single post() finds no view holder yet and the old code fell back to
     * list.requestFocus() — which lands on the FIRST visible child, snapping focus
     * back to the top category instead of the one we left from. Re-posting until the
     * target holder exists (bounded) lands focus on the correct row.
     */
    private fun focusRow(list: RecyclerView, pos: Int, attempts: Int = 10, center: Boolean = false) {
        val generation = ++focusRequestGeneration
        val stableId = when (list) {
            binding.channelList -> channelAdapter.currentList.getOrNull(pos)?.id
            binding.categoryList -> categoryAdapter.currentList.getOrNull(pos)?.id
            else -> null
        }
        list.scrollToPosition(pos)
        list.post(object : Runnable {
            private var remaining = attempts
            override fun run() {
                if (
                    generation != focusRequestGeneration ||
                    !list.isShown ||
                    (inlineFullscreen && list !== binding.fullscreenGuideList)
                ) return
                val resolvedPosition = when (list) {
                    binding.channelList ->
                        channelAdapter.currentList.indexOfFirst { it.id == stableId }
                    binding.categoryList ->
                        categoryAdapter.currentList.indexOfFirst { it.id == stableId }
                    else -> pos
                }
                if (resolvedPosition < 0) {
                    if ((list.adapter?.itemCount ?: 0) > 0) focusRow(list, 0, center = false)
                    return
                }
                val holder = list.findViewHolderForAdapterPosition(resolvedPosition)
                when {
                    holder != null -> {
                        // Center the target row in the viewport when asked, so the
                        // restored/playing channel sits mid-list with rows visible
                        // above and below it (not pinned to the top edge).
                        if (center) {
                            (list.layoutManager as? LinearLayoutManager)?.let { lm ->
                                val offset = (list.height - holder.itemView.height) / 2
                                lm.scrollToPositionWithOffset(
                                    resolvedPosition,
                                    offset.coerceAtLeast(0),
                                )
                            }
                        }
                        holder.itemView.requestFocus()
                    }
                    remaining-- > 0 -> {
                        list.scrollToPosition(resolvedPosition)
                        list.post(this)
                    }
                    else -> list.requestFocus()
                }
            }
        })
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
            LivePreviewPressPolicy.Action.START_PREVIEW ->
                requestChannelPlayback(channel, enterFullscreenWhenReady = false)
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
        if (pinPromptInFlight) return

        pinPromptInFlight = true
        val requestGeneration = ++pinRequestGeneration
        pinGuardRequest = PinLockHelper.guard(
            activity = this,
            isAdult = true,
            onDenied = {
                if (requestGeneration == pinRequestGeneration) {
                    pinPromptInFlight = false
                    pinGuardRequest = null
                }
            },
            onAllowed = allowed@{
                if (
                    requestGeneration != pinRequestGeneration ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) return@allowed
                pinPromptInFlight = false
                pinGuardRequest = null
                unlockedChannels.add(channel.id)
                channelAdapter.refreshVisible(binding.channelList)
                showInfo(channel)
                startPreviewFor(channel, enterFullscreenWhenReady)
            },
        )
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
        cancelAutoRetry(resetAttempts = true)
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
        previewHasRenderedFrame = false
        previewEngineName = null
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
        binding.infoLogo.visibility = View.VISIBLE
        previewJob = lifecycleScope.launch { startPreview(channel) }
    }

    private suspend fun startPreview(channel: Channel) {
        val settings = ServiceLocator.settings
        // Read settings first (these suspend); then bail if we were cancelled
        // meanwhile (for example by onStop) so a stale resume cannot reopen the
        // preview socket while Home is no longer visible.
        val mode = settings.playerMode.first()
        val decoder = settings.decoderMode.first()
        val passthrough = settings.audioPassthrough.first()
        val buffer = settings.bufferMode.first()
        val streamFormat = settings.streamFormat.first()
        currentCoroutineContext().ensureActive()
        if (
            previewingChannel?.id != channel.id ||
            previewState != LivePreviewPressPolicy.Phase.STARTING
        ) return
        if (previewController == null) {
            previewController = PlayerController(
                context = this,
                container = binding.previewVideo,
                mode = mode,
                decoderMode = decoder,
                allowPassthrough = passthrough,
                bufferMode = buffer,
                callback = buildPreviewCallback()
            )
        }
        previewController?.play(
            LiveStreamUrl.applyFormat(channel.streamUrl, streamFormat),
            routeKey = LiveStreamUrl.routeKey(channel.id, streamFormat),
        )
    }

    private var previewHasRenderedFrame = false
    private var previewEngineName: String? = null

    private fun markPreviewReady() {
        cancelAutoRetry(resetAttempts = true)
        previewState = LivePreviewPressPolicy.Phase.READY
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
            cancelAutoRetry(resetAttempts = false)
            previewState = LivePreviewPressPolicy.Phase.STARTING
            binding.previewLoading.visibility = View.VISIBLE
            binding.previewStatus.setText(R.string.buffering)
            binding.previewStatus.visibility = View.VISIBLE
        }
        override fun onPlaying(engineName: String) {
            if (previewEngineName != null && previewEngineName != engineName) {
                previewHasRenderedFrame = false
            }
            previewEngineName = engineName
            // Audio-only radio has no video-frame callback. For TV, retain the logo
            // and buffering cover until a real decoded frame reaches the SurfaceView.
            if (radioMode) {
                binding.infoLogo.visibility = View.VISIBLE
                markPreviewReady()
            } else if (previewHasRenderedFrame) {
                markPreviewReady()
            }
        }
        override fun onPlaybackRestarting() {
            cancelAutoRetry(resetAttempts = false)
            previewState = LivePreviewPressPolicy.Phase.STARTING
            previewHasRenderedFrame = false
            previewEngineName = null
            if (!radioMode) binding.infoLogo.visibility = View.VISIBLE
            binding.previewLoading.visibility = View.VISIBLE
            binding.previewStatus.setText(R.string.buffering)
            binding.previewStatus.visibility = View.VISIBLE
        }
        override fun onVideoResumed() {
            previewHasRenderedFrame = true
            binding.infoLogo.visibility = View.GONE
            markPreviewReady()
        }
        override fun onFatalError() {
            binding.infoLogo.visibility = View.VISIBLE
            binding.previewVideo.keepScreenOn = false
            previewState = LivePreviewPressPolicy.Phase.FAILED
            pendingFullscreenChannelId = null
            binding.previewLoading.visibility = View.GONE
            binding.previewStatus.setText(R.string.preview_playback_failed)
            binding.previewStatus.visibility = View.VISIBLE
            scheduleAutoRetry()
        }
        override fun onRetrying(attempt: Int) {
            cancelAutoRetry(resetAttempts = false)
            previewState = LivePreviewPressPolicy.Phase.STARTING
            previewHasRenderedFrame = false
            previewEngineName = null
            if (!radioMode) binding.infoLogo.visibility = View.VISIBLE
            binding.previewLoading.visibility = View.VISIBLE
            binding.previewStatus.text = getString(R.string.reconnecting, attempt)
            binding.previewStatus.visibility = View.VISIBLE
        }
    }

    private fun scheduleAutoRetry() {
        autoRetryHandler.removeCallbacksAndMessages(null)
        val delayMs = AutoRetryPolicy.delayForAttempt(autoRetryAttempt)
        if (delayMs == null) {
            binding.previewStatus.setText(R.string.error_auto_retry_exhausted)
            return
        }
        tickAutoRetry((delayMs / 1000L).coerceAtLeast(1L))
    }

    private fun tickAutoRetry(secondsLeft: Long) {
        if (secondsLeft <= 0L) {
            if (
                previewState != LivePreviewPressPolicy.Phase.FAILED ||
                previewController == null ||
                isFinishing ||
                isDestroyed ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return
            autoRetryAttempt++
            previewState = LivePreviewPressPolicy.Phase.STARTING
            previewHasRenderedFrame = false
            previewEngineName = null
            binding.previewLoading.visibility = View.VISIBLE
            binding.previewStatus.setText(R.string.buffering)
            previewController?.retry()
            return
        }
        binding.previewStatus.text =
            getString(R.string.error_auto_retry_countdown, secondsLeft)
        binding.previewStatus.visibility = View.VISIBLE
        autoRetryHandler.postDelayed({ tickAutoRetry(secondsLeft - 1L) }, 1000L)
    }

    private fun cancelAutoRetry(resetAttempts: Boolean) {
        autoRetryHandler.removeCallbacksAndMessages(null)
        if (resetAttempts) autoRetryAttempt = 0
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
        fullscreenGuideVisible = false
        fullscreenConfirmPress.clear()
        focusRequestGeneration++
        binding.previewCard.clearFocus()
        binding.previewCard.isFocusable = false
        binding.previewCard.isClickable = false
        binding.previewCard.clipToOutline = false
        binding.leftPane.visibility = View.GONE
        binding.previewHeader.visibility = View.GONE
        binding.guidePanel.visibility = View.GONE
        binding.catchupHint.visibility = View.GONE
        binding.previewCaption.visibility = View.GONE
        binding.fullscreenGuideOverlay.visibility = View.GONE
        updateFullscreenEpgGuideLayout(guideVisible = false)
        previewingChannel?.let(::bindFullscreenEpgMeta)
        if (captionEpgLoaded) {
            bindFullscreenEpg(captionPrograms, System.currentTimeMillis())
        } else {
            renderFullscreenEpgLoading()
        }
        binding.fullscreenEpgOverlay.visibility = View.VISIBLE
        binding.root.setPadding(0, 0, 0, 0)
        updateSplitGuide(0f)
        (binding.previewCard.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.topMargin = 0
            params.weight = 1f
            binding.previewCard.layoutParams = params
        }
        startFullscreenEpgTicker()
        scheduleFullscreenEpgAutoHide()
    }

    /** Restores the browser layout without touching playback. */
    private fun exitPreviewFullscreen(restoreFocus: Boolean = true) {
        if (!inlineFullscreen) return

        closeFullscreenGuide(showPlayingEpg = false)
        inlineFullscreen = false
        pendingFullscreenChannelId = null
        fullscreenConfirmPress.clear()
        cancelFullscreenZap()
        stopFullscreenEpgTicker()
        focusRequestGeneration++
        val snapshot = browseLayoutSnapshot
        binding.root.setPadding(
            snapshot.paddingLeft,
            snapshot.paddingTop,
            snapshot.paddingRight,
            snapshot.paddingBottom,
        )
        updateSplitGuide(snapshot.splitPercent)
        binding.leftPane.visibility = View.VISIBLE
        binding.previewHeader.visibility = View.VISIBLE
        binding.guidePanel.visibility = View.VISIBLE
        binding.previewCaption.visibility = View.VISIBLE
        binding.fullscreenGuideOverlay.visibility = View.GONE
        binding.fullscreenEpgOverlay.visibility = View.GONE
        updateFullscreenEpgGuideLayout(guideVisible = false)
        binding.catchupHint.visibility =
            if ((currentInfoChannel?.catchupDays ?: 0) > 0) View.VISIBLE else View.GONE
        restoreBrowseGuideFromPlayingCaption()
        (binding.previewCard.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.topMargin = snapshot.previewTopMargin
            params.weight = snapshot.previewWeight
            binding.previewCard.layoutParams = params
        }
        binding.previewCard.clipToOutline = snapshot.previewClipToOutline
        binding.previewCard.isClickable = snapshot.previewClickable
        binding.previewCard.isFocusable = snapshot.previewFocusable

        if (!restoreFocus) return
        val playingId = previewingChannel?.id
        val position = currentChannels.indexOfFirst { it.id == playingId }
        when {
            inChannelView && position >= 0 -> {
                lastFocusedChannelId = playingId
                focusRow(binding.channelList, position, center = true)
            }
            inChannelView && currentChannels.isNotEmpty() -> focusFirstChannel()
            categoryAdapter.itemCount > 0 -> {
                val categoryId = lastSelectedCategoryId
                if (categoryId != null) focusCategory(categoryId)
                else focusRow(binding.categoryList, 0)
            }
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

    private fun updateSplitGuide(percent: Float) {
        val params = binding.splitGuide.layoutParams as ConstraintLayout.LayoutParams
        params.guidePercent = percent
        binding.splitGuide.layoutParams = params
    }

    private fun captureBrowseLayout(): BrowseLayoutSnapshot {
        val guide = binding.splitGuide.layoutParams as ConstraintLayout.LayoutParams
        val preview = binding.previewCard.layoutParams as LinearLayout.LayoutParams
        return BrowseLayoutSnapshot(
            paddingLeft = binding.root.paddingLeft,
            paddingTop = binding.root.paddingTop,
            paddingRight = binding.root.paddingRight,
            paddingBottom = binding.root.paddingBottom,
            splitPercent = guide.guidePercent,
            previewTopMargin = preview.topMargin,
            previewWeight = preview.weight,
            previewClipToOutline = binding.previewCard.clipToOutline,
            previewFocusable = binding.previewCard.isFocusable,
            previewClickable = binding.previewCard.isClickable,
        )
    }

    /** INFO toggles the playing-channel card; every reveal starts a fresh four seconds. */
    private fun toggleFullscreenCaption() {
        fullscreenEpgHandler.removeCallbacks(hideFullscreenEpg)
        if (binding.fullscreenEpgOverlay.visibility == View.VISIBLE) {
            binding.fullscreenEpgOverlay.visibility = View.GONE
        } else {
            showPlayingFullscreenEpg()
        }
    }

    /** Keeps progress/remaining time exact while the fullscreen card is active. */
    private fun startFullscreenEpgTicker() {
        fullscreenEpgHandler.removeCallbacks(fullscreenEpgTick)
        fullscreenEpgHandler.post(fullscreenEpgTick)
    }

    private fun stopFullscreenEpgTicker() {
        fullscreenEpgHandler.removeCallbacksAndMessages(null)
    }

    /** CH+/CH- always reveals the new channel's programme information for four seconds. */
    private fun revealFullscreenEpgForChannelChange() {
        fullscreenEpgHandler.removeCallbacks(hideFullscreenEpg)
        if (fullscreenGuideVisible) closeFullscreenGuide(showPlayingEpg = false)
        previewingChannel?.let(::bindFullscreenEpgMeta)
        if (captionEpgLoaded) {
            bindFullscreenEpg(captionPrograms, System.currentTimeMillis())
        } else {
            renderFullscreenEpgLoading()
        }
        binding.fullscreenEpgOverlay.visibility = View.VISIBLE
        scheduleFullscreenEpgAutoHide()
    }

    /** Starts the timer only after usable EPG (including an empty result) is visible. */
    private fun scheduleFullscreenEpgAutoHide() {
        fullscreenEpgHandler.removeCallbacks(hideFullscreenEpg)
        if (
            inlineFullscreen &&
            !fullscreenGuideVisible &&
            captionEpgLoaded &&
            binding.fullscreenEpgOverlay.visibility == View.VISIBLE
        ) {
            fullscreenEpgHandler.postDelayed(
                hideFullscreenEpg,
                FULLSCREEN_EPG_AUTO_HIDE_MS,
            )
        }
    }

    private fun showPlayingFullscreenEpg() {
        val channel = previewingChannel ?: return
        fullscreenEpgHandler.removeCallbacks(hideFullscreenEpg)
        bindFullscreenEpgMeta(channel)
        if (captionEpgLoaded) {
            bindFullscreenEpg(captionPrograms, System.currentTimeMillis())
        } else {
            renderFullscreenEpgLoading()
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

        cancelFullscreenZap()
        fullscreenEpgHandler.removeCallbacks(hideFullscreenEpg)
        fullscreenGuideEpgJob?.cancel()
        fullscreenGuideEpgGeneration++
        fullscreenGuideVisible = true
        fullscreenConfirmPress.clear()
        focusRequestGeneration++

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
        updateFullscreenEpgGuideLayout(guideVisible = true)

        fullscreenGuideAdapter.submitList(channels) {
            if (!fullscreenGuideVisible || channels.isEmpty()) return@submitList
            val playingPosition = channels.indexOfFirst { it.id == playing?.id }
            focusRow(
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
        focusRequestGeneration++
        binding.fullscreenGuideOverlay.animate().cancel()
        binding.fullscreenGuidePanel.animate().cancel()
        binding.fullscreenGuideOverlay.alpha = 1f
        binding.fullscreenGuidePanel.translationX = 0f
        binding.fullscreenGuideList.clearFocus()
        binding.fullscreenGuideOverlay.visibility = View.GONE
        updateFullscreenEpgGuideLayout(guideVisible = false)
        if (showPlayingEpg && inlineFullscreen) {
            showPlayingFullscreenEpg()
        } else {
            fullscreenEpgHandler.removeCallbacks(hideFullscreenEpg)
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
        fullscreenEpgHandler.removeCallbacks(hideFullscreenEpg)
        binding.fullscreenEpgOverlay.visibility = View.VISIBLE

        if (isChannelLocked(channel)) {
            bindLockedFullscreenEpg()
            fullscreenGuideEpgLoaded = true
            return
        }

        bindFullscreenEpgMeta(channel)
        renderFullscreenEpgLoading()
        if (channel.id == previewingChannel?.id && captionEpgLoaded) {
            fullscreenGuidePrograms = captionPrograms
            fullscreenGuideEpgLoaded = true
            bindFullscreenEpg(captionPrograms, System.currentTimeMillis())
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
                bindFullscreenEpg(programs, System.currentTimeMillis())
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
                bindFullscreenEpg(emptyList(), System.currentTimeMillis())
            }
        }
    }

    private fun bindLockedFullscreenEpg() {
        binding.fullscreenEpgNumber.text = ""
        binding.fullscreenEpgNumber.visibility = View.GONE
        binding.fullscreenEpgChannelName.setText(R.string.adult_locked_title)
        binding.fullscreenEpgCategory.setText(R.string.pin_locked_content)
        binding.fullscreenEpgCategory.visibility = View.VISIBLE
        binding.fullscreenEpgLogo.load(R.drawable.ic_lock) { crossfade(false) }
        binding.fullscreenEpgNowTitle.setText(R.string.pin_locked_content)
        binding.fullscreenEpgRemaining.visibility = View.GONE
        binding.fullscreenEpgProgress.progress = 0
        binding.fullscreenEpgProgress.visibility = View.GONE
        binding.fullscreenEpgTimingRow.visibility = View.GONE
        binding.fullscreenEpgStart.text = ""
        binding.fullscreenEpgEnd.text = ""
        binding.fullscreenEpgNextRow.visibility = View.GONE
        binding.fullscreenEpgNextTitle.text = ""
        binding.fullscreenEpgNextStart.text = ""
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

    /** Makes room for the rail while keeping the EPG card in the TV safe area. */
    private fun updateFullscreenEpgGuideLayout(guideVisible: Boolean) {
        val params = binding.fullscreenEpgOverlay.layoutParams as FrameLayout.LayoutParams
        val safe = resources.getDimensionPixelSize(R.dimen.safe_area_h)
        params.marginStart = if (guideVisible) {
            safe +
                resources.getDimensionPixelSize(R.dimen.fullscreen_guide_width) +
                resources.getDimensionPixelSize(R.dimen.fullscreen_guide_epg_gap)
        } else {
            safe
        }
        params.marginEnd = safe
        binding.fullscreenEpgOverlay.layoutParams = params
        if (guideVisible) binding.fullscreenEpgOverlay.bringToFront()
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

    /** Keeps the essential PlayerActivity controls available without changing player ownership. */
    private fun showInlinePlayerMenu() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val channel = previewingChannel
        if (channel != null) {
            labels.add(
                getString(
                    if (channel.isFavorite) R.string.removed_from_favorites
                    else R.string.added_to_favorites,
                ),
            )
            actions.add(::togglePreviewFavorite)
        }

        labels.add(getString(R.string.sleep_timer))
        actions.add(::showInlineSleepDialog)

        val controller = previewController
        if (controller?.supportsDelay == true) {
            labels.add(getString(R.string.audio_delay))
            actions.add { showInlineDelayDialog(audio = true) }
            labels.add(getString(R.string.subtitle_delay))
            actions.add { showInlineDelayDialog(audio = false) }
        }

        if (castController.isAvailable) {
            labels.add(getString(R.string.cast))
            actions.add { castController.onCastButtonClicked() }
        }

        labels.add(getString(R.string.stream_info))
        actions.add(::showStreamInfo)

        PlayerDialogs.showOptions(
            this,
            getString(R.string.player_menu),
            labels.map { PlayerDialogs.Option(it) },
        ) { index -> actions[index].invoke() }
    }

    private fun showInlineSleepDialog() {
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = minutes.map { value ->
            if (value == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_minutes, value)
        }
        PlayerDialogs.showOptions(
            this,
            getString(R.string.sleep_timer),
            labels.mapIndexed { index, label ->
                PlayerDialogs.Option(label, minutes[index] == sleepTimer.minutes)
            },
        ) { index ->
            val value = minutes[index]
            sleepTimer.set(value)
            Toast.makeText(
                this,
                if (value == 0) getString(R.string.sleep_timer_off)
                else getString(R.string.sleep_timer_set, value),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showInlineDelayDialog(audio: Boolean) {
        val controller = previewController ?: return
        val values = (-2000..2000 step 250).toList()
        val current =
            if (audio) controller.currentAudioDelayMs else controller.currentSubtitleDelayMs
        val title = if (audio) R.string.audio_delay else R.string.subtitle_delay
        PlayerDialogs.showOptions(
            this,
            getString(title),
            values.map { value ->
                PlayerDialogs.Option(
                    getString(R.string.delay_ms, value),
                    selected = value.toLong() == current,
                )
            },
        ) { index ->
            val value = values[index].toLong()
            if (audio) controller.setAudioDelay(value) else controller.setSubtitleDelay(value)
        }
    }

    private fun showStreamInfo() {
        val info = previewController?.streamInfo()
        val message = if (info == null) {
            getString(R.string.stream_info_unavailable)
        } else {
            val resolution =
                if (info.width > 0 && info.height > 0) "${info.width}×${info.height}"
                else STATS_DASH
            val fps =
                if (info.fps > 0f) String.format(Locale.US, "%.0f", info.fps)
                else STATS_DASH
            val bitrate = info.bitrateKbps?.let { "$it kbps" } ?: STATS_DASH
            buildString {
                append(getString(R.string.stream_info_engine, info.engine)).append('\n')
                append(getString(R.string.stream_info_resolution, resolution)).append('\n')
                append(getString(R.string.stream_info_fps, fps)).append('\n')
                append(getString(R.string.stream_info_codec, info.codec ?: STATS_DASH)).append('\n')
                append(getString(R.string.stream_info_bitrate, bitrate))
            }
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun numberZapChannels(): List<Channel> =
        if (inlineFullscreen) previewChannelScope.ifEmpty { currentChannels }
        else currentChannels

    private fun zapFullscreenChannel(direction: Int) {
        val channels = previewChannelScope.ifEmpty { currentChannels }
        if (channels.isEmpty()) return
        val currentId = pendingFullscreenZapChannel?.id ?: previewingChannel?.id
        val currentIndex = channels.indexOfFirst { it.id == currentId }
        val targetIndex = if (currentIndex >= 0) {
            (currentIndex + direction + channels.size) % channels.size
        } else {
            0
        }
        val target = channels[targetIndex]
        lastFocusedChannelId = target.id
        if (isChannelLocked(target)) {
            cancelFullscreenZap()
            requestChannelPlayback(target, enterFullscreenWhenReady = true)
            return
        }

        pendingFullscreenZapChannel = target
        binding.fullscreenZapOverlay.text = buildString {
            target.number?.let { append(it).append("  ") }
            append(ChannelText.clean(target.name))
        }
        binding.fullscreenZapOverlay.visibility = View.VISIBLE
        fullscreenZapHandler.removeCallbacksAndMessages(null)
        fullscreenZapHandler.postDelayed({
            if (
                !inlineFullscreen ||
                isFinishing ||
                isDestroyed ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                cancelFullscreenZap()
                return@postDelayed
            }
            val channel = pendingFullscreenZapChannel ?: return@postDelayed
            pendingFullscreenZapChannel = null
            binding.fullscreenZapOverlay.visibility = View.GONE
            requestChannelPlayback(channel, enterFullscreenWhenReady = true)
        }, FULLSCREEN_ZAP_DEBOUNCE_MS)
    }

    private fun cancelFullscreenZap(hideOverlay: Boolean = true) {
        fullscreenZapHandler.removeCallbacksAndMessages(null)
        pendingFullscreenZapChannel = null
        if (hideOverlay) binding.fullscreenZapOverlay.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimer.release()
        castController.detach()
        autoRetryHandler.removeCallbacksAndMessages(null)
        fullscreenEpgHandler.removeCallbacksAndMessages(null)
        fullscreenGuideEpgJob?.cancel()
        debugBinder?.release()
    }

    /** Stops + releases the preview so the single stream connection is freed. */
    private fun stopPreview() {
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
        cancelAutoRetry(resetAttempts = true)
        previewController?.release()
        previewController = null
        previewingChannel = null
        NowPlaying.clear(this)
        previewState = LivePreviewPressPolicy.Phase.IDLE
        previewHasRenderedFrame = false
        previewEngineName = null
        pendingFullscreenChannelId = null
        previewChannelScope = emptyList()
        previewChannelScopeLabel = null
        cancelFullscreenZap()
        captionPrograms = emptyList()
        fullscreenGuidePrograms = emptyList()
        fullscreenGuideEpgLoaded = false
        fullscreenGuideAdapter.setPlayingChannel(null)
        binding.fullscreenGuideOverlay.visibility = View.GONE
        binding.fullscreenEpgOverlay.visibility = View.GONE
        renderFullscreenEpgLoading()
        binding.infoLogo.visibility = View.VISIBLE
        binding.previewLoading.visibility = View.GONE
        binding.previewStatus.visibility = View.GONE
        // Preview is gone: release the screen-on hold so we're not pinning the
        // display awake when nothing is playing on Home.
        binding.previewVideo.keepScreenOn = false
    }

    override fun onStart() {
        super.onStart()
        externalNavigationInFlight = false
        // Coming back from Settings/Catch-up: restore the channel row after the
        // lifecycle-scoped list collector re-subscribes.
        if (inChannelView) pendingChannelFocusRestore = true
    }

    override fun onStop() {
        super.onStop()
        // External navigation really leaves Home, so restore its browse layout for
        // the next start and then release the sole live connection.
        exitPreviewFullscreen(restoreFocus = false)
        stopPreview()
        // Stop any in-flight EPG fetch so the guide isn't loaded off-screen.
        nowNextJob?.cancel()
        binding.epgLoading.visibility = View.GONE
        // Drop any pending number-zap commit so a delayed lookup can't launch the
        // player after we've left the screen (and to release the buffer/refs).
        zap.cancel()
        pendingEnterCategoryId = null
        consumedUntilUp.clear()
        pinGuardRequest?.cancel()
        pinGuardRequest = null
        pinPromptInFlight = false
        pinRequestGeneration++
    }
}
