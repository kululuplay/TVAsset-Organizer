/*
 * HomeActivity.kt
 * The 2-pane Android TV Live screen:
 *   left  = a single list that shows categories, and drills into that category's
 *           channels on OK (Back returns to categories).
 *   right = a large channel preview (number + name + current program caption)
 *           on top, and the EPG schedule list for that channel below.
 * Fully D-pad driven. Click a channel to open the player.
 */
package com.iptv.player.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
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
import com.iptv.player.ui.common.hideSoftKeyboard
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.util.DebugOverlayBinder
import com.iptv.player.util.NewContentNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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

    /** Last channels rendered, used by the number-key zap lookup. */
    private var currentChannels: List<Channel> = emptyList()

    /** Guards the one-time initial category selection on first category emission. */
    private var initialSelectionDone = false

    /** When the category changes, the channel list must snap back to its top. */
    private var pendingScrollReset = false

    /** Last category actually selected; used to detect real category changes. */
    private var lastSelectedCategoryId: String? = null

    /** Restores deterministic browse focus after the retry control disappears. */
    private var restoreFocusAfterRetry = false

    /** Last channel row that held focus; restored when returning to the channel list. */
    private var lastFocusedChannelId: String? = null

    /**
     * The channel actually playing when we left for the fullscreen player. On
     * return it takes priority over [lastFocusedChannelId] so the list lands on
     * the channel the user is watching (CH+/- zapping may have changed it inside
     * the player), not the row they originally opened or last browsed. Consumed
     * once on the next channel-list emission.
     */
    private var pendingPlayingChannelId: String? = null

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

    /**
     * Safety net for the reverse hand-off: if no fresh frame event arrives after
     * re-adopting the handed-back controller, clear the channel logo so the
     * preview never stays hidden behind it. Cancelled the moment a frame lands.
     */
    private val previewLogoHandler = Handler(Looper.getMainLooper())

    /** Debounce job so scrolling channels doesn't thrash the single stream socket. */
    private var previewJob: kotlinx.coroutines.Job? = null

    /** A failed preview uses OK as Retry instead of promoting a dead controller. */
    private var previewFailed = false

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

        /**
         * Safety cap for the reverse-hand-off channel-logo cover: if no fresh frame
         * event arrives after re-adopting the handed-back controller, force-clear
         * the logo so the preview can't stay hidden behind it. Mirrors the
         * fullscreen player's hand-off cover timeout.
         */
        private const val HANDOFF_LOGO_TIMEOUT_MS = 6000L
    }

    private val zap by lazy {
        NumberZapInputHelper(
            lookup = { num -> currentChannels.firstOrNull { it.number == num } },
            onResolved = { ch -> ch?.let { openPlayer(it) } },
            onInputChanged = { typed ->
                binding.zapOverlay.text = typed
                binding.zapOverlay.visibility = if (typed.isEmpty()) View.GONE else View.VISIBLE
            }
        )
    }

    /** True while the left pane shows the channel list (drilled into a category). */
    private val inChannelView: Boolean
        get() = binding.channelList.visibility == View.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        setupSearch()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        zap.handleKeyDown(keyCode) || super.onKeyDown(keyCode, event)

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
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> when {
                    // Drill back to categories from the channel list (mirrors Back).
                    inChannelView && binding.channelList.hasFocus() -> {
                        if (viewModel.query.value.isNotEmpty()) exitSearch()
                        else showCategories()
                        return true
                    }
                    // LEFT from the focusable preview card returns to the left list
                    // instead of dead-ending, keeping the drill path uniform.
                    binding.previewCard.hasFocus() -> {
                        (if (inChannelView) binding.channelList else binding.categoryList)
                            .requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                    inChannelView && binding.channelList.hasFocus() -> {
                        binding.previewCard.requestFocus()
                        return true
                    }
                    !inChannelView && binding.categoryList.hasFocus() -> {
                        categoryAdapter.currentList
                            .firstOrNull { it.id == lastSelectedCategoryId }
                            ?.let { drillIntoCategory(it); return true }
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (binding.searchInput.hasFocus()) {
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
                KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED ->
                    // repeatCount guard: a held key must not stack-launch the browser.
                    if (event.repeatCount == 0) {
                        openCatchup()
                        return true
                    }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
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
                if (it.id != lastSelectedCategoryId) {
                    lastSelectedCategoryId = it.id
                    pendingScrollReset = true
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

        // The preview surface goes fullscreen on the PLAYING channel (so a click
        // after browsing other channel names still expands what's on screen),
        // falling back to the focused channel when nothing is previewing yet.
        binding.previewCard.setOnClickListener {
            (previewingChannel ?: currentInfoChannel)?.let {
                if (previewFailed) startPreviewFor(it) else openPlayer(it)
            }
        }
        binding.refreshButton.setOnClickListener { viewModel.refresh() }
        binding.retryButton.setOnClickListener {
            restoreFocusAfterRetry = true
            viewModel.refresh()
        }
        binding.browserTitle.setText(if (radioMode) R.string.nav_radio else R.string.nav_live)
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
                    clearPreview()
                } else if (binding.searchInput.hasFocus()) {
                    // Keep the keyboard/search field active when the last
                    // character is deleted. Moving focus here made it impossible
                    // to immediately type a different query with a TV keyboard.
                    binding.categoryList.visibility = View.VISIBLE
                    binding.channelList.visibility = View.GONE
                }
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
                            }
                        }
                    }
                }
                launch {
                    viewModel.channels.collectLatest { channels ->
                        currentChannels = channels
                        channelAdapter.submitList(channels) {
                            if (pendingScrollReset) {
                                pendingScrollReset = false
                                binding.channelList.scrollToPosition(0)
                            }
                            // Returning to the channel list (e.g. back from the
                            // fullscreen player) re-emits the list, which resets D-pad
                            // focus to the top. Restore focus to the row we left from.
                            if (pendingChannelFocusRestore && inChannelView) {
                                pendingChannelFocusRestore = false
                                // Prefer the actually-playing channel (set on a
                                // hand-back from fullscreen) over the last-browsed
                                // row, then fall back to it for non-player returns.
                                val targetId = pendingPlayingChannelId ?: lastFocusedChannelId
                                pendingPlayingChannelId = null
                                val pos = channels.indexOfFirst { it.id == targetId }
                                if (pos >= 0) {
                                    lastFocusedChannelId = targetId
                                    focusRow(binding.channelList, pos, center = true)
                                }
                            }
                            // While still browsing categories, preview the first channel
                            // of the focused category (like the reference design).
                            if (!inChannelView) {
                                val first = channels.firstOrNull()
                                // Don't auto-preview a locked adult category's first
                                // channel: that would leak its name/logo/EPG before the
                                // PIN is entered. Only metadata is gated here; playback
                                // is already guarded in drillIntoCategory/openPlayer.
                                if (
                                    first != null &&
                                    !isCurrentCategoryLocked() &&
                                    !isChannelLocked(first)
                                ) {
                                    showInfo(first)
                                }
                                else clearPreview()
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
        binding.loadingIndicator.visibility = if (state.loading) View.VISIBLE else View.GONE
        val failed = state.errorRes != null
        binding.loadErrorContainer.visibility = if (failed) View.VISIBLE else View.GONE
        state.errorRes?.let { binding.loadErrorText.setText(it) }
        // Keep an already-focused refresh control in the D-pad graph while the
        // request runs; disabling it makes Android TV eject focus unpredictably.
        binding.refreshButton.isClickable = !state.loading
        binding.refreshButton.alpha = if (state.loading) 0.5f else 1f

        binding.emptyState.setText(
            if (viewModel.query.value.isNotEmpty()) R.string.empty_search
            else R.string.empty_channels
        )
        val empty = currentChannels.isEmpty() && !state.loading && !failed
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        if (failed && currentChannels.isEmpty()) {
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

    /**
     * True between handing the live controller to the fullscreen player and
     * HomeActivity stopping, so onStop does NOT release the connection that the
     * fullscreen player now owns (single-connection hand-off safety).
     */
    private var handingOverPreview = false

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun showInfo(channel: Channel) {
        if (isChannelLocked(channel)) {
            showLockedInfo(channel)
            return
        }
        // Re-selecting the channel already shown (e.g. the list re-emits on a favorite
        // toggle, or a refocus) shouldn't re-fetch its guide or rebuild the panel.
        if (channel.id == currentInfoChannel?.id && currentPrograms.isNotEmpty()) return
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

        nowNextJob?.cancel()
        nowNextJob = lifecycleScope.launch {
            try {
                // Debounce: rapid D-pad scrolling cancels this before the fetch fires,
                // so only the channel the user settles on hits the provider/Room.
                delay(EPG_DEBOUNCE_MS)
                val programs = viewModel.programs(channel)
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
                binding.epgEmpty.visibility =
                    if (programs.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Best-effort guide: never let an EPG fetch crash the UI coroutine.
                currentPrograms = emptyList()
                epgAdapter.submitList(emptyList())
                binding.epgEmpty.visibility = View.VISIBLE
            }
        }
    }

    /** Renders a channel's static metadata (number + logos + title) into the caption. */
    private fun bindCaptionMeta(channel: Channel) {
        binding.previewNumber.text = channel.number?.toString() ?: ""
        binding.previewTitle.text = ChannelText.clean(channel.name)
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
    private fun lockCaptionToPreview(channel: Channel) {
        bindCaptionMeta(channel)
        captionJob?.cancel()
        captionPrograms = emptyList()
        captionJob = lifecycleScope.launch {
            try {
                val programs = viewModel.programs(channel)
                captionPrograms = programs
                binding.previewProgram.text =
                    nowPlayingLabel(programs, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                captionPrograms = emptyList()
                binding.previewProgram.text = ""
            }
        }
    }

    private fun clearPreview() {
        currentInfoChannel = null
        stopPreview()
        binding.catchupHint.visibility = View.GONE
        binding.previewNumber.text = ""
        binding.previewTitle.text = ""
        binding.previewProgram.text = ""
        binding.infoLogo.load(null)
        binding.previewCaptionLogo.load(null)
        binding.previewStatus.visibility = View.GONE
        nowNextJob?.cancel()
        currentPrograms = emptyList()
        epgAdapter.submitList(emptyList())
        binding.epgEmpty.visibility = View.GONE
    }

    /** Renders a metadata-free parental-lock state for an adult channel. */
    private fun showLockedInfo(channel: Channel) {
        nowNextJob?.cancel()
        stopPreview()
        currentInfoChannel = channel
        currentPrograms = emptyList()
        epgAdapter.submitList(emptyList())
        binding.epgEmpty.visibility = View.GONE
        binding.catchupHint.visibility = View.GONE
        binding.previewNumber.text = ""
        binding.previewTitle.setText(R.string.adult_locked_title)
        binding.previewProgram.text = ""
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
        if (
            adultLockEnabled &&
            category.isAdult() &&
            category.id !in unlockedCategories
        ) {
            PinLockHelper.guard(this, isAdult = true) {
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
        if (currentChannels.isEmpty()) return
        categoryAdapter.setSelected(lastSelectedCategoryId)
        binding.channelList.visibility = View.VISIBLE
        binding.categoryList.visibility = View.GONE
        focusFirstChannel()
    }

    /** Returns from the channel list back to the category list. */
    private fun showCategories() {
        binding.categoryList.visibility = View.VISIBLE
        binding.channelList.visibility = View.GONE
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
        list.scrollToPosition(pos)
        list.post(object : Runnable {
            private var remaining = attempts
            override fun run() {
                val holder = list.findViewHolderForAdapterPosition(pos)
                when {
                    holder != null -> {
                        // Center the target row in the viewport when asked, so the
                        // restored/playing channel sits mid-list with rows visible
                        // above and below it (not pinned to the top edge).
                        if (center) {
                            (list.layoutManager as? LinearLayoutManager)?.let { lm ->
                                val offset = (list.height - holder.itemView.height) / 2
                                lm.scrollToPositionWithOffset(pos, offset.coerceAtLeast(0))
                            }
                        }
                        holder.itemView.requestFocus()
                    }
                    remaining-- > 0 -> list.post(this)
                    else -> list.requestFocus()
                }
            }
        })
    }

    private fun openPlayer(channel: Channel) {
        if (isChannelLocked(channel)) {
            PinLockHelper.guard(this, isAdult = true) {
                unlockedChannels.add(channel.id)
                channelAdapter.refreshVisible(binding.channelList)
                openPlayer(channel)
            }
            return
        }

        val controller = previewController
        if (previewingChannel?.id == channel.id && controller != null) {
            // Seamless fullscreen: hand the still-playing preview controller to
            // PlayerActivity instead of releasing + reconnecting. Detach our
            // refs first (without releasing) so onStop can't tear it down, mark
            // the hand-off so onStop skips stopPreview, then park + launch.
            handingOverPreview = true
            previewController = null
            previewingChannel = null
            previewJob?.cancel()
            captionJob?.cancel()
            ServiceLocator.handOverLiveController(controller)
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                putExtra(PlayerActivity.EXTRA_CATEGORY_ID, playbackCategoryId(channel))
                putExtra(PlayerActivity.EXTRA_RADIO_MODE, radioMode)
                putExtra(PlayerActivity.EXTRA_ADOPT_PREVIEW, true)
                putExtra(PlayerActivity.EXTRA_PARENTAL_AUTHORIZED, true)
                channel.categoryId
                    ?.takeIf { it in unlockedCategories }
                    ?.let { putExtra(PlayerActivity.EXTRA_AUTHORIZED_CATEGORY_ID, it) }
            }
            startActivity(intent)
        } else {
            // Free the preview socket before the full player connects (single-connection).
            stopPreview()
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                putExtra(PlayerActivity.EXTRA_CATEGORY_ID, playbackCategoryId(channel))
                putExtra(PlayerActivity.EXTRA_RADIO_MODE, radioMode)
                putExtra(PlayerActivity.EXTRA_PARENTAL_AUTHORIZED, true)
                channel.categoryId
                    ?.takeIf { it in unlockedCategories }
                    ?.let { putExtra(PlayerActivity.EXTRA_AUTHORIZED_CATEGORY_ID, it) }
            }
            startActivity(intent)
        }
    }

    /** Search results zap within their real category; browse keeps synthetic scopes. */
    private fun playbackCategoryId(channel: Channel): String? =
        if (viewModel.query.value.isNotEmpty()) channel.categoryId else lastSelectedCategoryId

    /** Opens the catch-up/archive browser, pre-focusing the previewed channel. */
    private fun openCatchup() {
        val intent = Intent(this, CatchupActivity::class.java)
        currentInfoChannel?.takeIf { it.catchupDays > 0 }?.let {
            intent.putExtra(CatchupActivity.EXTRA_CHANNEL_ID, it.id)
        }
        startActivity(intent)
    }

    /**
     * OK on a channel: the first press starts the in-panel live preview; a
     * second press on the same (already previewing) channel expands to full
     * screen. Navigating to another channel resets this (see onFocused).
     */
    private fun onChannelClicked(channel: Channel) {
        if (previewingChannel?.id == channel.id && previewController != null && !previewFailed) {
            openPlayer(channel)
        } else {
            // Gate the preview for adult channels surfaced in Favorites/Recent,
            // which are never category-locked, so the first OK can't play their
            // live video PIN-free. Skip the prompt inside an adult category the
            // user already unlocked (drillIntoCategory) so we don't re-ask per
            // channel; a real locked category can't be browsed without unlocking.
            if (isChannelLocked(channel)) {
                PinLockHelper.guard(this, isAdult = true) {
                    unlockedChannels.add(channel.id)
                    channelAdapter.refreshVisible(binding.channelList)
                    showInfo(channel)
                    startPreviewFor(channel)
                }
            } else {
                startPreviewFor(channel)
            }
        }
    }

    /** Starts the in-panel live preview for [channel] (explicit user action). */
    private fun startPreviewFor(channel: Channel) {
        currentInfoChannel = channel
        previewingChannel = channel
        previewFailed = false
        binding.previewStatus.setText(R.string.buffering)
        binding.previewStatus.visibility = View.VISIBLE
        // Lock the on-video caption to the channel we're about to play so it stays
        // correct even as the user browses other channel names afterwards.
        lockCaptionToPreview(channel)
        previewJob?.cancel()
        // Drop any pending reverse-hand-off logo timeout so it can't hide the logo
        // of this freshly-started preview before its first frame lands.
        previewLogoHandler.removeCallbacksAndMessages(null)
        binding.infoLogo.visibility = View.VISIBLE
        previewJob = lifecycleScope.launch { startPreview(channel) }
    }

    private suspend fun startPreview(channel: Channel) {
        val settings = ServiceLocator.settings
        // Read settings first (these suspend); then bail if we were cancelled
        // meanwhile (e.g. stopPreview from openPlayer) so a stale resume can't
        // re-open the preview socket while the full player is connecting.
        val mode = settings.playerMode.first()
        val decoder = settings.decoderMode.first()
        val passthrough = settings.audioPassthrough.first()
        val buffer = settings.bufferMode.first()
        currentCoroutineContext().ensureActive()
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
        previewController?.play(channel.streamUrl)
    }

    /**
     * UI callback for the preview controller. Hiding the channel logo on a real
     * video signal reveals the picture; onVideoResumed() also fires after a
     * surface re-attach (e.g. re-adopting the controller handed back from the
     * fullscreen player) where onPlaying may not fire again.
     */
    private fun buildPreviewCallback() = object : PlayerController.Callback {
        override fun onBuffering() {
            binding.previewStatus.setText(R.string.buffering)
            binding.previewStatus.visibility = View.VISIBLE
        }
        override fun onPlaying(engineName: String) {
            previewLogoHandler.removeCallbacksAndMessages(null)
            binding.infoLogo.visibility = View.GONE
            binding.previewStatus.visibility = View.GONE
            previewFailed = false
            // Live preview is rendering: hold the screen on so a long watch on the
            // Home preview never lets Fire TV / Android TV slip into standby.
            binding.previewVideo.keepScreenOn = true
        }
        override fun onVideoResumed() {
            previewLogoHandler.removeCallbacksAndMessages(null)
            binding.infoLogo.visibility = View.GONE
            binding.previewStatus.visibility = View.GONE
            previewFailed = false
            binding.previewVideo.keepScreenOn = true
        }
        override fun onFatalError() {
            // Playback failed: keep the logo up as the "can't play" state and drop
            // the hand-off timeout so it can't later hide it and expose a black surface.
            previewLogoHandler.removeCallbacksAndMessages(null)
            binding.infoLogo.visibility = View.VISIBLE
            binding.previewVideo.keepScreenOn = false
            previewFailed = true
            binding.previewStatus.setText(R.string.preview_playback_failed)
            binding.previewStatus.visibility = View.VISIBLE
        }
        override fun onRetrying(attempt: Int) {
            binding.previewStatus.text = getString(R.string.reconnecting, attempt)
            binding.previewStatus.visibility = View.VISIBLE
        }
    }

    /**
     * Reverse hand-off: re-adopt the live controller the fullscreen player gave
     * back on BACK, rebinding it onto the preview surface so returning to Home
     * keeps the picture playing (no reconnect, single connection preserved). The
     * channel logo stays over the surface until the first frame lands after the
     * re-bind, covering the brief gap with the logo instead of black.
     */
    private fun reAdoptHandedBackPreview(controller: PlayerController, channelId: String?) {
        handingOverPreview = false
        previewController = controller
        previewFailed = false
        controller.setCallback(buildPreviewCallback())
        binding.infoLogo.visibility = View.VISIBLE
        binding.previewStatus.setText(R.string.buffering)
        binding.previewStatus.visibility = View.VISIBLE
        // Safety net: rebind() restarts the stream and the fresh frame event hides
        // the logo, but if that event never arrives, force-clear the logo so the
        // preview can't stay hidden behind it indefinitely.
        previewLogoHandler.removeCallbacksAndMessages(null)
        previewLogoHandler.postDelayed(
            { binding.infoLogo.visibility = View.GONE },
            HANDOFF_LOGO_TIMEOUT_MS
        )
        controller.rebind(binding.previewVideo)
        // The played channel may sit OUTSIDE the current category: fullscreen CH+/-
        // zaps through the full live list, so currentChannels (one category) can
        // miss it. Try the in-memory list first, then fall back to a repository
        // lookup so previewingChannel is set and the caption stays locked to the
        // channel actually on screen instead of drifting to follow focus.
        val local = channelId?.let { id -> currentChannels.firstOrNull { it.id == id } }
        if (local != null) {
            currentInfoChannel = local
            previewingChannel = local
            lockCaptionToPreview(local)
        } else if (channelId != null) {
            lifecycleScope.launch {
                val ch = runCatching { ServiceLocator.repository.getChannel(channelId) }.getOrNull()
                // Only apply if this handed-back controller is still the live preview
                // and nothing newer claimed it, so a late lookup can't clobber fresh
                // state (e.g. the user started a different preview meanwhile).
                if (ch != null && previewController === controller && previewingChannel == null) {
                    currentInfoChannel = ch
                    previewingChannel = ch
                    lockCaptionToPreview(ch)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewLogoHandler.removeCallbacksAndMessages(null)
        debugBinder?.release()
    }

    /** Stops + releases the preview so the single stream connection is freed. */
    private fun stopPreview() {
        previewLogoHandler.removeCallbacksAndMessages(null)
        previewJob?.cancel()
        captionJob?.cancel()
        previewController?.release()
        previewController = null
        previewingChannel = null
        previewFailed = false
        captionPrograms = emptyList()
        binding.infoLogo.visibility = View.VISIBLE
        binding.previewStatus.visibility = View.GONE
        // Preview is gone: release the screen-on hold so we're not pinning the
        // display awake when nothing is playing on Home.
        binding.previewVideo.keepScreenOn = false
    }

    override fun onStart() {
        super.onStart()
        // Coming back on-screen while drilled into a channel (e.g. from the fullscreen
        // player, Settings, Catch-up): the channel list re-emits and resets D-pad focus
        // to the top, so flag a restore to the row we left from on the next emission.
        if (inChannelView) pendingChannelFocusRestore = true
        // Reverse hand-off: if the fullscreen player gave its live controller back
        // on BACK, re-adopt it into the preview surface so returning to Home keeps
        // the picture playing instead of a dead/closed preview. Null on a normal
        // start (nothing parked), so this is a no-op except after a player BACK.
        val handedBack = ServiceLocator.consumePendingLiveController() ?: return
        val channelId = ServiceLocator.consumePendingLiveChannelId()
        // Land focus on the channel that was actually playing in fullscreen (zap
        // may have changed it), not the row originally opened. Only meaningful when
        // returning into the channel list (which arms the restore above); otherwise
        // null it so a stale id can't jump focus on a later unrelated return.
        pendingPlayingChannelId = if (inChannelView) channelId else null
        reAdoptHandedBackPreview(handedBack, channelId)
    }

    override fun onStop() {
        super.onStop()
        if (handingOverPreview) {
            // The live controller was handed to the fullscreen player, which now
            // owns the single connection. Don't release it; just clear our local
            // preview state so a later return to Home starts a fresh preview.
            handingOverPreview = false
            previewJob?.cancel()
            captionJob?.cancel()
            previewController = null
            previewingChannel = null
            captionPrograms = emptyList()
            binding.infoLogo.visibility = View.VISIBLE
        } else {
            stopPreview()
        }
        // Stop any in-flight EPG fetch so the guide isn't loaded off-screen.
        nowNextJob?.cancel()
        // Drop any pending number-zap commit so a delayed lookup can't launch the
        // player after we've left the screen (and to release the buffer/refs).
        zap.cancel()
    }
}
