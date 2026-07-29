/*
 * ContinueWatchingActivity.kt
 * TV-first collection of in-progress movies and episodes. Adult classification
 * includes cached source-category metadata, and removal/focus behavior remains
 * deterministic as reactive resume rows disappear.
 */
package com.iptv.player.ui.dashboard

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.databinding.ActivityContinueWatchingBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.player.VodPlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ContinueWatchingActivity : BaseActivity() {

    private lateinit var binding: ActivityContinueWatchingBinding
    private lateinit var adapter: ContinueWatchingAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    private var observeJob: Job? = null
    private var removeDialog: AlertDialog? = null
    private var currentItems: List<ContinueItem> = emptyList()
    private var currentAdultIds: Set<String> = emptySet()
    private val adultClassificationCache = mutableMapOf<String, Boolean>()
    private var hasRendered = false
    private var initialFocusApplied = false
    private var restoredFocusId: String? = null
    private var lastFocusedId: String? = null
    private var lastFocusedPosition = 0
    private var pendingFocusId: String? = null
    private var pendingFocusPosition = 0
    private var navigationInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContinueWatchingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoredFocusId = savedInstanceState?.getString(STATE_FOCUSED_ID)
        lastFocusedId = restoredFocusId
        lastFocusedPosition = savedInstanceState?.getInt(STATE_FOCUSED_POSITION, 0) ?: 0

        ViewCompat.setAccessibilityPaneTitle(
            binding.root,
            getString(R.string.section_continue_watching)
        )
        ViewCompat.setAccessibilityHeading(binding.screenTitle, true)
        ViewCompat.setAccessibilityLiveRegion(
            binding.stateContainer,
            ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE
        )

        adapter = ContinueWatchingAdapter(
            onClicked = ::resume,
            onLongClicked = ::confirmRemove,
            onFocused = { id, position ->
                lastFocusedId = id
                lastFocusedPosition = position
            }
        )
        gridLayoutManager = GridLayoutManager(this, DEFAULT_SPAN_COUNT)
        binding.continueGrid.apply {
            layoutManager = gridLayoutManager
            adapter = this@ContinueWatchingActivity.adapter
            itemAnimator = null
            setHasFixedSize(true)
        }
        configureResponsiveSpan()

        binding.retryButton.setOnClickListener {
            // The retry control disappears as soon as content returns. Preserve
            // the last card anchor so focus cannot fall back to the window root.
            pendingFocusId = lastFocusedId
            pendingFocusPosition = lastFocusedPosition
            startObserving(forceLoading = true)
        }
        showLoading()
        startObserving()
    }

    override fun onResume() {
        super.onResume()
        navigationInFlight = false
    }

    override fun onStop() {
        // Pessimistically mask every classified adult card until the parental
        // setting is read again on STARTED, preventing a one-frame metadata leak.
        if (::adapter.isInitialized) adapter.lockedAdultIds = currentAdultIds
        removeDialog?.dismiss()
        super.onStop()
    }

    override fun onDestroy() {
        removeDialog?.dismiss()
        removeDialog = null
        observeJob?.cancel()
        observeJob = null
        super.onDestroy()
    }

    private fun configureResponsiveSpan() {
        binding.continueGrid.doOnLayout { grid ->
            val available = grid.width - grid.paddingStart - grid.paddingEnd
            val desired = resources.getDimensionPixelSize(R.dimen.collection_card_min_width)
            if (available <= 0 || desired <= 0) return@doOnLayout
            val spans = (available / desired).coerceIn(MIN_SPAN_COUNT, MAX_SPAN_COUNT)
            if (gridLayoutManager.spanCount != spans) gridLayoutManager.spanCount = spans
        }
    }

    private fun startObserving(forceLoading: Boolean = false) {
        observeJob?.cancel()
        var forceLoadingPending = forceLoading
        observeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (forceLoadingPending || !hasRendered) showLoading()
                forceLoadingPending = false
                try {
                    val settings = ServiceLocator.settings
                    val lockEnabled = settings.lockAdult.first() && settings.hasPin()
                    ServiceLocator.repository.observeContinueWatching(PAGE_LIMIT)
                        .collectLatest { items ->
                            val adultIds = resolveAdultIds(items)
                            currentAdultIds = adultIds
                            adapter.lockedAdultIds =
                                if (lockEnabled) adultIds else emptySet()
                            renderItems(items)
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.e(TAG, "Continue Watching collection failed", error)
                    showError()
                }
            }
        }
    }

    private suspend fun resolveAdultIds(items: List<ContinueItem>): Set<String> {
        val result = LinkedHashSet<String>()
        items.forEach { item ->
            if (isAdultContent(item)) result += item.contentId
        }
        return result
    }

    private suspend fun isAdultContent(item: ContinueItem): Boolean {
        if (PinLockHelper.looksAdult(item.title)) return true
        val lookupKey = when (item.kind) {
            ResumeKind.MOVIE -> "movie:${item.vodId ?: item.contentId}"
            ResumeKind.EPISODE -> "series:${item.seriesId ?: item.contentId}"
            ResumeKind.CATCHUP -> return false
        }
        adultClassificationCache[lookupKey]?.let { return it }

        val classified = try {
            when (item.kind) {
                ResumeKind.MOVIE -> {
                    val id = item.vodId
                        ?: item.contentId.removePrefix("vod_").takeIf { it.isNotBlank() }
                    id?.let { ServiceLocator.repository.getVodCached(it)?.isAdult() }
                        // Missing legacy metadata is privacy-sensitive: mask it
                        // until the PIN rather than exposing an unknown category.
                        ?: true
                }
                ResumeKind.EPISODE -> item.seriesId
                    ?.let { ServiceLocator.repository.getSeriesCached(it)?.isAdult() }
                    ?: true
                ResumeKind.CATCHUP -> false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Adult metadata lookup failed for ${item.contentId}", error)
            // Do not cache lookup failures; a later emission can classify again.
            return true
        }
        adultClassificationCache[lookupKey] = classified
        return classified
    }

    private fun renderItems(items: List<ContinueItem>) {
        val pendingId = pendingFocusId.also { pendingFocusId = null }
        val pendingPosition = pendingFocusPosition
        val previousFocusedWasRemoved =
            lastFocusedId != null && items.none { it.contentId == lastFocusedId }

        val focusTarget = when {
            items.isEmpty() -> null
            pendingId != null -> {
                val position = items.indexOfFirst { it.contentId == pendingId }
                (if (position >= 0) position else pendingPosition.coerceIn(items.indices))
            }
            !initialFocusApplied -> {
                initialFocusApplied = true
                val restored = items.indexOfFirst { it.contentId == restoredFocusId }
                if (restored >= 0) restored else lastFocusedPosition.coerceIn(items.indices)
            }
            previousFocusedWasRemoved -> lastFocusedPosition.coerceIn(items.indices)
            else -> null
        }

        currentItems = items
        hasRendered = true
        updateCount(items.size)
        if (items.isEmpty()) showEmpty() else showContent()

        adapter.submitList(items) {
            focusTarget?.let(::focusPosition)
        }
    }

    private fun focusPosition(position: Int) {
        if (adapter.itemCount == 0) return
        val safePosition = position.coerceIn(0, adapter.itemCount - 1)
        gridLayoutManager.scrollToPositionWithOffset(safePosition, 0)
        binding.continueGrid.doOnNextLayout {
            binding.continueGrid
                .findViewHolderForAdapterPosition(safePosition)
                ?.itemView
                ?.requestFocus()
        }
    }

    private fun updateCount(count: Int) {
        binding.collectionCount.text = resources.getQuantityString(
            R.plurals.collections_item_count,
            count,
            count
        )
        binding.collectionCount.visibility = View.VISIBLE
    }

    private fun showLoading() {
        binding.continueGrid.visibility = View.GONE
        binding.collectionCount.visibility = View.INVISIBLE
        binding.stateContainer.visibility = View.VISIBLE
        binding.stateIcon.visibility = View.GONE
        binding.stateProgress.visibility = View.VISIBLE
        binding.stateTitle.setText(R.string.loading)
        binding.emptyText.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
    }

    private fun showEmpty() {
        binding.continueGrid.visibility = View.GONE
        binding.stateContainer.visibility = View.VISIBLE
        binding.stateIcon.apply {
            setImageResource(R.drawable.ic_clock)
            setBackgroundResource(R.drawable.bg_badge_catchup)
            visibility = View.VISIBLE
        }
        binding.stateProgress.visibility = View.GONE
        binding.stateTitle.setText(R.string.section_continue_watching)
        binding.emptyText.apply {
            setText(R.string.continue_empty)
            visibility = View.VISIBLE
        }
        binding.retryButton.visibility = View.GONE
    }

    private fun showContent() {
        binding.stateContainer.visibility = View.GONE
        binding.continueGrid.visibility = View.VISIBLE
    }

    private fun showError() {
        binding.continueGrid.visibility = View.GONE
        binding.collectionCount.visibility = View.INVISIBLE
        binding.stateContainer.visibility = View.VISIBLE
        binding.stateIcon.apply {
            setImageResource(R.drawable.ic_vod_player_error)
            setBackgroundResource(R.drawable.bg_badge_movies)
            visibility = View.VISIBLE
        }
        binding.stateProgress.visibility = View.GONE
        binding.stateTitle.setText(R.string.collections_error_title)
        binding.emptyText.apply {
            setText(R.string.collections_error_message)
            visibility = View.VISIBLE
        }
        binding.retryButton.visibility = View.VISIBLE
        binding.retryButton.post { binding.retryButton.requestFocus() }
    }

    private fun resume(item: ContinueItem) {
        if (navigationInFlight) return
        navigationInFlight = true
        lifecycleScope.launch {
            if (item.streamUrl.isBlank()) {
                navigationInFlight = false
                Toast.makeText(
                    this@ContinueWatchingActivity,
                    R.string.error_cannot_play_content,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val adult = item.contentId in currentAdultIds || isAdultContent(item)
            PinLockHelper.guard(
                activity = this@ContinueWatchingActivity,
                isAdult = adult,
                onDenied = { navigationInFlight = false }
            ) {
                val launched = runCatching {
                    startActivity(
                        Intent(this@ContinueWatchingActivity, VodPlayerActivity::class.java)
                            .putExtra(VodPlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
                            .putExtra(VodPlayerActivity.EXTRA_TITLE, item.title)
                            .putExtra(VodPlayerActivity.EXTRA_RESUME_ID, item.contentId)
                            .putExtra(VodPlayerActivity.EXTRA_RESUME_TYPE, item.kind.raw)
                            .putExtra(VodPlayerActivity.EXTRA_RESUME_TITLE, item.title)
                            .putExtra(VodPlayerActivity.EXTRA_POSTER_URL, item.posterUrl)
                            .putExtra(VodPlayerActivity.EXTRA_VOD_ID, item.vodId)
                            .putExtra(VodPlayerActivity.EXTRA_SERIES_ID, item.seriesId)
                            .putExtra(VodPlayerActivity.EXTRA_SEASON, item.seasonNumber)
                            .putExtra(VodPlayerActivity.EXTRA_EPISODE, item.episodeNumber)
                            .putExtra(VodPlayerActivity.EXTRA_AUTO_RESUME, true)
                    )
                }.isSuccess
                if (!launched) {
                    navigationInFlight = false
                    Toast.makeText(
                        this@ContinueWatchingActivity,
                        R.string.error_unknown,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun confirmRemove(item: ContinueItem) {
        if (removeDialog != null) return
        val position = currentItems.indexOfFirst { it.contentId == item.contentId }
            .takeIf { it >= 0 }
            ?: return
        val displayTitle = if (item.contentId in adapter.lockedAdultIds) {
            getString(R.string.adult_locked_title)
        } else {
            item.title
        }

        val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
            .setMessage(getString(R.string.collections_remove_continue_confirm, displayTitle))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                removeContinueItem(item, position)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        removeDialog = dialog
        dialog.setOnShowListener {
            trackIdleInteractions(dialog)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
        }
        dialog.setOnDismissListener {
            removeDialog = null
            if (pendingFocusId == null && currentItems.isNotEmpty()) {
                focusPosition(position.coerceIn(currentItems.indices))
            }
        }
        dialog.show()
    }

    private fun removeContinueItem(item: ContinueItem, position: Int) {
        val neighbor = currentItems.getOrNull(position + 1)
            ?: currentItems.getOrNull(position - 1)
        pendingFocusId = neighbor?.contentId
        pendingFocusPosition = position.coerceAtLeast(0)
        lastFocusedPosition = pendingFocusPosition

        lifecycleScope.launch {
            try {
                ServiceLocator.repository.clearResume(item.contentId)
                Toast.makeText(
                    this@ContinueWatchingActivity,
                    R.string.collections_removed_continue,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Continue item removal failed", error)
                pendingFocusId = null
                Toast.makeText(
                    this@ContinueWatchingActivity,
                    R.string.error_unknown,
                    Toast.LENGTH_SHORT
                ).show()
                focusPosition(position)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FOCUSED_ID, lastFocusedId ?: restoredFocusId)
        outState.putInt(STATE_FOCUSED_POSITION, lastFocusedPosition)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val TAG = "ContinueWatching"
        const val DEFAULT_SPAN_COUNT = 5
        const val MIN_SPAN_COUNT = 3
        const val MAX_SPAN_COUNT = 6
        const val PAGE_LIMIT = 60
        const val STATE_FOCUSED_ID = "state_focused_id"
        const val STATE_FOCUSED_POSITION = "state_focused_position"
    }
}
