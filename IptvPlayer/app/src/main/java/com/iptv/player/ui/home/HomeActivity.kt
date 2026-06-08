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
import android.view.View
import android.widget.Toast
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.player.PlayerActivity
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
        ViewModelProvider(this, HomeViewModel.Factory(radioMode))[HomeViewModel::class.java]
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

    /** Adult categories the user has already unlocked this session (avoid re-prompting). */
    private val unlockedCategories = mutableSetOf<String>()

    /** Live preview player bound to the preview card (one connection at a time). */
    private var previewController: PlayerController? = null

    /** Debounce job so scrolling channels doesn't thrash the single stream socket. */
    private var previewJob: kotlinx.coroutines.Job? = null

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
        observe()
        observeNewContent()
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
                        showCategories()
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
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (!inChannelView && binding.categoryList.hasFocus()) {
                        categoryAdapter.currentList
                            .firstOrNull { it.id == lastSelectedCategoryId }
                            ?.let { drillIntoCategory(it); return true }
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
            onFocused = { showInfo(it) },
            onClicked = { onChannelClicked(it) },
            onToggleFavorite = {
                viewModel.toggleFavorite(it.id)
                val msg = if (it.isFavorite) R.string.removed_from_favorites
                else R.string.added_to_favorites
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )
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
            (previewingChannel ?: currentInfoChannel)?.let { openPlayer(it) }
        }
    }

    private fun observe() {
        // Lifecycle-scoped: collection is suspended while the screen is STOPPED so we
        // don't do submitList / EPG / UI work off-screen, and resubscribes on START.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                            // While still browsing categories, preview the first channel
                            // of the focused category (like the reference design).
                            if (!inChannelView) {
                                val first = channels.firstOrNull()
                                // Don't auto-preview a locked adult category's first
                                // channel: that would leak its name/logo/EPG before the
                                // PIN is entered. Only metadata is gated here; playback
                                // is already guarded in drillIntoCategory/openPlayer.
                                if (first != null && !isCurrentCategoryLocked()) showInfo(first)
                                else clearPreview()
                            }
                        }
                        binding.emptyState.visibility =
                            if (channels.isEmpty()) View.VISIBLE else View.GONE
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
            binding.infoLogo.setImageDrawable(placeholder)
            binding.previewCaptionLogo.setImageDrawable(placeholder)
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
        binding.infoLogo.setImageDrawable(null)
        binding.previewCaptionLogo.setImageDrawable(null)
        nowNextJob?.cancel()
        currentPrograms = emptyList()
        epgAdapter.submitList(emptyList())
        binding.epgEmpty.visibility = View.GONE
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
        return cat.isAdult() && cat.id !in unlockedCategories
    }

    /**
     * Drills into a category from the category list, prompting for the PIN first
     * on locked (adult) categories. Shared by OK/click and the RIGHT-arrow shortcut.
     */
    private fun drillIntoCategory(category: com.iptv.player.data.model.Category) {
        if (category.isAdult() && category.id !in unlockedCategories) {
            PinLockHelper.guard(this, isAdult = true) {
                unlockedCategories.add(category.id)
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
        lastSelectedCategoryId?.let { focusCategory(it) }
    }

    /** Moves focus into the channel list, always landing on the first channel. */
    private fun focusFirstChannel() {
        binding.channelList.scrollToPosition(0)
        binding.channelList.post {
            (binding.channelList.findViewHolderForAdapterPosition(0)?.itemView
                ?: binding.channelList).requestFocus()
        }
    }

    /** Moves focus onto a given category row. */
    private fun focusCategory(id: String) {
        val pos = (0 until categoryAdapter.itemCount)
            .firstOrNull { categoryAdapter.currentList.getOrNull(it)?.id == id } ?: 0
        binding.categoryList.scrollToPosition(pos)
        binding.categoryList.post {
            (binding.categoryList.findViewHolderForAdapterPosition(pos)?.itemView
                ?: binding.categoryList).requestFocus()
        }
    }

    private fun openPlayer(channel: Channel) {
        PinLockHelper.guard(this, isAdult = channel.isAdult()) {
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
                    putExtra(PlayerActivity.EXTRA_CATEGORY_ID, lastSelectedCategoryId)
                    putExtra(PlayerActivity.EXTRA_RADIO_MODE, radioMode)
                    putExtra(PlayerActivity.EXTRA_ADOPT_PREVIEW, true)
                }
                startActivity(intent)
            } else {
                // Free the preview socket before the full player connects (single-connection).
                stopPreview()
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                    putExtra(PlayerActivity.EXTRA_CATEGORY_ID, lastSelectedCategoryId)
                    putExtra(PlayerActivity.EXTRA_RADIO_MODE, radioMode)
                }
                startActivity(intent)
            }
        }
    }

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
        if (previewingChannel?.id == channel.id && previewController != null) {
            openPlayer(channel)
        } else {
            startPreviewFor(channel)
        }
    }

    /** Starts the in-panel live preview for [channel] (explicit user action). */
    private fun startPreviewFor(channel: Channel) {
        currentInfoChannel = channel
        previewingChannel = channel
        // Lock the on-video caption to the channel we're about to play so it stays
        // correct even as the user browses other channel names afterwards.
        lockCaptionToPreview(channel)
        previewJob?.cancel()
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
        override fun onBuffering() {}
        override fun onPlaying(engineName: String) {
            binding.infoLogo.visibility = View.GONE
        }
        override fun onVideoResumed() {
            binding.infoLogo.visibility = View.GONE
        }
        override fun onFatalError() {
            binding.infoLogo.visibility = View.VISIBLE
        }
        override fun onRetrying(attempt: Int) {}
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
        controller.setCallback(buildPreviewCallback())
        binding.infoLogo.visibility = View.VISIBLE
        controller.rebind(binding.previewVideo)
        val channel = channelId?.let { id -> currentChannels.firstOrNull { it.id == id } }
        if (channel != null) {
            currentInfoChannel = channel
            previewingChannel = channel
            lockCaptionToPreview(channel)
        }
    }

    /** Stops + releases the preview so the single stream connection is freed. */
    private fun stopPreview() {
        previewJob?.cancel()
        captionJob?.cancel()
        previewController?.release()
        previewController = null
        previewingChannel = null
        captionPrograms = emptyList()
        binding.infoLogo.visibility = View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        // Reverse hand-off: if the fullscreen player gave its live controller back
        // on BACK, re-adopt it into the preview surface so returning to Home keeps
        // the picture playing instead of a dead/closed preview. Null on a normal
        // start (nothing parked), so this is a no-op except after a player BACK.
        val handedBack = ServiceLocator.consumePendingLiveController() ?: return
        val channelId = ServiceLocator.consumePendingLiveChannelId()
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
