/*
 * FavoritesActivity.kt
 * Unified TV-first collection for favorite channels, movies and series. Adult
 * metadata is masked before bind, OK opens the correct destination, and holding
 * OK offers removal while preserving focus on the nearest surviving card.
 */
package com.iptv.player.ui.favorites

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
import com.iptv.player.data.model.FavoriteItem
import com.iptv.player.data.model.FavoriteKind
import com.iptv.player.databinding.ActivityFavoritesBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.home.HomeViewModel
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.ui.series.SeriesDetailActivity
import com.iptv.player.ui.vod.VodDetailActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FavoritesActivity : BaseActivity() {

    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var adapter: FavoritesAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    private var observeJob: Job? = null
    private var removeDialog: AlertDialog? = null
    private var currentItems: List<FavoriteItem> = emptyList()
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
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoredFocusId = savedInstanceState?.getString(STATE_FOCUSED_ID)
        lastFocusedId = restoredFocusId
        lastFocusedPosition = savedInstanceState?.getInt(STATE_FOCUSED_POSITION, 0) ?: 0

        ViewCompat.setAccessibilityPaneTitle(
            binding.root,
            getString(R.string.section_favorites)
        )
        ViewCompat.setAccessibilityHeading(binding.screenTitle, true)
        ViewCompat.setAccessibilityLiveRegion(
            binding.stateContainer,
            ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE
        )

        adapter = FavoritesAdapter(
            onClicked = ::openFavorite,
            onLongClicked = ::confirmRemove,
            onFocused = { id, position ->
                lastFocusedId = id
                lastFocusedPosition = position
            }
        )
        gridLayoutManager = GridLayoutManager(this, DEFAULT_SPAN_COUNT)
        binding.favoritesGrid.apply {
            layoutManager = gridLayoutManager
            adapter = this@FavoritesActivity.adapter
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
        // Privacy-first while another screen may change parental settings. The
        // real setting is resolved before the next collection bind.
        if (::adapter.isInitialized) adapter.adultLocked = true
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
        binding.favoritesGrid.doOnLayout { grid ->
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
                    adapter.adultLocked = settings.lockAdult.first() && settings.hasPin()
                    ServiceLocator.repository.observeAllFavorites().collectLatest(::renderItems)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.e(TAG, "Favorites collection failed", error)
                    showError()
                }
            }
        }
    }

    private fun renderItems(items: List<FavoriteItem>) {
        val pendingId = pendingFocusId.also { pendingFocusId = null }
        val pendingPosition = pendingFocusPosition
        val previousFocusedWasRemoved =
            lastFocusedId != null && items.none { it.favoriteId == lastFocusedId }

        val focusTarget = when {
            items.isEmpty() -> null
            pendingId != null -> {
                val position = items.indexOfFirst { it.favoriteId == pendingId }
                (if (position >= 0) position else pendingPosition.coerceIn(items.indices))
            }
            !initialFocusApplied -> {
                initialFocusApplied = true
                val restored = items.indexOfFirst { it.favoriteId == restoredFocusId }
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
        binding.favoritesGrid.doOnNextLayout { grid ->
            grid
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
        binding.favoritesGrid.visibility = View.GONE
        binding.collectionCount.visibility = View.INVISIBLE
        binding.stateContainer.visibility = View.VISIBLE
        binding.stateIcon.visibility = View.GONE
        binding.stateProgress.visibility = View.VISIBLE
        binding.stateTitle.setText(R.string.loading)
        binding.emptyText.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
    }

    private fun showEmpty() {
        binding.favoritesGrid.visibility = View.GONE
        binding.stateContainer.visibility = View.VISIBLE
        binding.stateIcon.apply {
            setImageResource(R.drawable.ic_heart_filled)
            setBackgroundResource(R.drawable.bg_badge_favorites)
            visibility = View.VISIBLE
        }
        binding.stateProgress.visibility = View.GONE
        binding.stateTitle.setText(R.string.section_favorites)
        binding.emptyText.apply {
            setText(R.string.favorites_empty)
            visibility = View.VISIBLE
        }
        binding.retryButton.visibility = View.GONE
    }

    private fun showContent() {
        binding.stateContainer.visibility = View.GONE
        binding.favoritesGrid.visibility = View.VISIBLE
    }

    private fun showError() {
        binding.favoritesGrid.visibility = View.GONE
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

    private fun openFavorite(item: FavoriteItem) {
        if (navigationInFlight) return
        navigationInFlight = true
        PinLockHelper.guard(
            activity = this,
            isAdult = item.isAdult(),
            onDenied = { navigationInFlight = false }
        ) {
            val launched = runCatching {
                when (item.kind) {
                    FavoriteKind.CHANNEL -> startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, item.targetId)
                            .putExtra(
                                PlayerActivity.EXTRA_CATEGORY_ID,
                                HomeViewModel.CAT_FAVORITES
                            )
                            .putExtra(PlayerActivity.EXTRA_PARENTAL_AUTHORIZED, true)
                    )
                    FavoriteKind.MOVIE -> startActivity(
                        Intent(this, VodDetailActivity::class.java)
                            .putExtra(VodDetailActivity.EXTRA_VOD_ID, item.targetId)
                    )
                    FavoriteKind.SERIES -> startActivity(
                        Intent(this, SeriesDetailActivity::class.java)
                            .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.targetId)
                    )
                }
            }.isSuccess
            if (!launched) {
                navigationInFlight = false
                Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmRemove(item: FavoriteItem) {
        if (removeDialog != null) return
        val position = currentItems.indexOfFirst { it.favoriteId == item.favoriteId }
            .takeIf { it >= 0 }
            ?: return
        val displayTitle = if (adapter.adultLocked && item.isAdult()) {
            getString(R.string.adult_locked_title)
        } else {
            item.title
        }

        val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
            .setMessage(getString(R.string.collections_remove_favorite_confirm, displayTitle))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                removeFavorite(item, position)
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

    private fun removeFavorite(item: FavoriteItem, position: Int) {
        val neighbor = currentItems.getOrNull(position + 1)
            ?: currentItems.getOrNull(position - 1)
        pendingFocusId = neighbor?.favoriteId
        pendingFocusPosition = position.coerceAtLeast(0)
        lastFocusedPosition = pendingFocusPosition

        lifecycleScope.launch {
            try {
                val becameFavorite =
                    ServiceLocator.repository.toggleFavorite(item.favoriteId)
                // A stale reactive row may already have been removed elsewhere.
                // toggleFavorite would re-add it; a second toggle restores the
                // requested final state (not favorite).
                if (becameFavorite) {
                    ServiceLocator.repository.toggleFavorite(item.favoriteId)
                }
                Toast.makeText(
                    this@FavoritesActivity,
                    R.string.removed_from_favorites,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Favorite removal failed", error)
                pendingFocusId = null
                Toast.makeText(
                    this@FavoritesActivity,
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
        const val TAG = "FavoritesActivity"
        const val DEFAULT_SPAN_COUNT = 5
        const val MIN_SPAN_COUNT = 3
        const val MAX_SPAN_COUNT = 6
        const val STATE_FOCUSED_ID = "state_focused_id"
        const val STATE_FOCUSED_POSITION = "state_focused_position"
    }
}
