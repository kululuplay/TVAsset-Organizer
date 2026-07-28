/*
 * SeriesActivity.kt
 * Series screen: categories on the left and a focusable poster grid in the center.
 * Clicking a poster opens the series detail screen.
 * Mirrors VodActivity; fully D-pad driven for TV.
 */
package com.iptv.player.ui.series

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.ContentSort
import com.iptv.player.databinding.ActivitySeriesBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.CatalogLoadState
import com.iptv.player.ui.common.NewContentPopup
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.SafeGridLayoutManager
import com.iptv.player.util.NewContentNotifier
import com.iptv.player.ui.common.autoFitColumns
import com.iptv.player.ui.common.hideSoftKeyboard
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.common.requestFocusAt
import com.iptv.player.ui.home.CategoryAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SeriesActivity : BaseActivity() {

    companion object {
        private const val STATE_CATEGORY = "series_category"
        private const val STATE_QUERY = "series_query"
        private const val STATE_GRID_POSITION = "series_grid_position"
        private const val STATE_SERIES_ID = "series_grid_id"
        private const val STATE_FOCUS_ZONE = "series_focus_zone"

        private const val FOCUS_CATEGORY = 0
        private const val FOCUS_GRID = 1
        private const val FOCUS_SEARCH = 2
        private const val FOCUS_REFRESH = 3
        private const val FOCUS_SORT = 4
    }

    private lateinit var binding: ActivitySeriesBinding
    private val viewModel: SeriesViewModel by lazy {
        ViewModelProvider(this)[SeriesViewModel::class.java]
    }

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var seriesAdapter: SeriesAdapter

    /** Latest in-progress percent keyed by series id, refreshed in [onResume]. */
    private var progressMap: Map<String, Int> = emptyMap()

    /** The category currently shown, so the header title can follow the sort. */
    private var currentCategory: Category? = null

    /** Latest Paging refresh state, updated by the load-state collector. */
    private var adapterRefreshSettled = false

    /** Focus this poster once an async category/search page commits. */
    private var pendingGridFocusPosition: Int? = null

    /** Return focus to browse content after the retry card disappears. */
    private var restoreFocusAfterRetry = false

    /** Current states are combined so stale content is never hidden by a refresh. */
    private var catalogLoadState = CatalogLoadState()
    private var pagingRefreshState: LoadState = LoadState.Loading

    /** Explicit focus restoration for configuration/process recreation. */
    private var restoredCategoryId: String? = null
    private var restoredFocusZone = FOCUS_CATEGORY
    private var restoredGridPosition = 0
    private var lastFocusedGridPosition = 0
    private var lastFocusedSeriesId: String? = null
    private var initialFocusRestored = false
    private var restoreGridFocusOnResume = false

    /** Sort orders cycled by the header button, in display order. */
    private val sortCycle = listOf(
        ContentSort.RECENT, ContentSort.NAME, ContentSort.RATING, ContentSort.YEAR
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoredCategoryId = savedInstanceState?.getString(STATE_CATEGORY)
        restoredGridPosition = savedInstanceState?.getInt(STATE_GRID_POSITION, 0) ?: 0
        lastFocusedGridPosition = restoredGridPosition
        lastFocusedSeriesId = savedInstanceState?.getString(STATE_SERIES_ID)
        restoredFocusZone =
            savedInstanceState?.getInt(STATE_FOCUS_ZONE, FOCUS_CATEGORY) ?: FOCUS_CATEGORY
        savedInstanceState?.getString(STATE_QUERY)
            ?.takeIf { it.isNotEmpty() && viewModel.query.value.isEmpty() }
            ?.let(viewModel::setQuery)

        setupLists()
        setupSearch()
        ViewCompat.setAccessibilityHeading(binding.contentTitle, true)
        ViewCompat.setAccessibilityPaneTitle(
            binding.contentPanel,
            getString(R.string.nav_series),
        )
        observe()
        observeNewContent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CATEGORY, currentCategory?.id ?: viewModel.selectedCategoryId)
        outState.putString(STATE_QUERY, viewModel.query.value)
        outState.putInt(STATE_GRID_POSITION, lastFocusedGridPosition)
        outState.putString(STATE_SERIES_ID, lastFocusedSeriesId)
        outState.putInt(
            STATE_FOCUS_ZONE,
            when {
                binding.posterGrid.hasFocus() -> FOCUS_GRID
                binding.searchInput.hasFocus() -> FOCUS_SEARCH
                binding.refreshButton.hasFocus() -> FOCUS_REFRESH
                binding.sortButton.hasFocus() -> FOCUS_SORT
                else -> FOCUS_CATEGORY
            },
        )
        super.onSaveInstanceState(outState)
    }

    /**
     * Flashes a transient top-right notice when the launch refresh found series
     * that weren't cached before, then clears the tally so it shows only once.
     */
    private fun observeNewContent() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NewContentNotifier.newSeries.collectLatest { count ->
                    if (count > 0) {
                        NewContentPopup.show(
                            this@SeriesActivity,
                            R.drawable.ic_series,
                            resources.getQuantityString(R.plurals.new_series_added, count, count)
                        )
                        NewContentNotifier.consumeSeries()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.dateText.text = DateFormat
            .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date())
        refreshWatchState()
        if (restoreGridFocusOnResume) {
            restoreGridFocusOnResume = false
            binding.posterGrid.post { restoreGridFocus() }
        }
    }

    /**
     * Reloads per-series resume progress (a cheap Room read) so the poster bars
     * reflect playback that happened since we left, then rebinds visible cards.
     */
    private fun refreshWatchState() {
        lifecycleScope.launch {
            val settings = ServiceLocator.settings
            seriesAdapter.adultLocked = settings.lockAdult.first() && settings.hasPin()
            progressMap = viewModel.repoSeriesWatchProgress()
            // Direct rebind of attached cards only. A blanket notifyItemRangeChanged
            // on this Paging adapter races Paging's own page invalidation and crashes
            // with "Inconsistency detected", bouncing the user to the Dashboard.
            seriesAdapter.refreshVisible(binding.posterGrid)
        }
    }

    private fun setupLists() {
        categoryAdapter = CategoryAdapter(
            onFocused = { cat ->
                pendingGridFocusPosition = null
                val categoryChanged = currentCategory?.id != cat.id
                if (viewModel.query.value.isNotEmpty()) {
                    binding.searchInput.text?.clear()
                }
                if (categoryChanged) {
                    adapterRefreshSettled = false
                    lastFocusedGridPosition = 0
                    lastFocusedSeriesId = null
                }
                viewModel.selectCategory(cat.id)
                categoryAdapter.setSelected(cat.id)
                currentCategory = cat
                updateContentTitle()
            },
            onClicked = { focusFirstItem() }
        )
        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter
        // Counts update live as series lazy-load, re-emitting the category list.
        // The default change-animation detaches the focused row mid-diff, so fast
        // D-pad browsing loses focus and the screen pops back to the Dashboard.
        // Disabling change animations rebinds the row in place and keeps focus.
        (binding.categoryList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        seriesAdapter = SeriesAdapter(
            onFocused = { item ->
                lastFocusedSeriesId = item.id
                seriesAdapter.snapshot().items
                    .indexOfFirst { it.id == item.id }
                    .takeIf { it >= 0 }
                    ?.let { lastFocusedGridPosition = it }
            },
            onClicked = { item ->
                PinLockHelper.guard(this, isAdult = item.isAdult()) { openDetail(item.id) }
            }
        )
        seriesAdapter.progressProvider = { id -> progressMap[id] ?: 0 }
        binding.posterGrid.layoutManager = SafeGridLayoutManager(this, 4)
        binding.posterGrid.autoFitColumns(min = 4)
        binding.posterGrid.adapter = seriesAdapter
        binding.posterGrid.setHasFixedSize(true)
        (binding.posterGrid.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        binding.sortButton.setOnClickListener {
            val next = sortCycle[(sortCycle.indexOf(viewModel.sort.value) + 1) % sortCycle.size]
            adapterRefreshSettled = false
            viewModel.setSort(next)
        }
        binding.refreshButton.setOnClickListener { viewModel.refreshCatalog() }
        binding.retryButton.setOnClickListener {
            restoreFocusAfterRetry = true
            adapterRefreshSettled = false
            if (pagingRefreshState is LoadState.Error) {
                seriesAdapter.retry()
            } else {
                viewModel.retryLoad()
            }
        }
    }

    private fun setupSearch() {
        val restored = viewModel.query.value
        if (restored.isNotEmpty()) {
            binding.searchInput.setText(restored)
            binding.searchInput.setSelection(restored.length)
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingGridFocusPosition = null
                adapterRefreshSettled = false
                lastFocusedGridPosition = 0
                lastFocusedSeriesId = null
                viewModel.setQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                binding.searchInput.hideSoftKeyboard()
                focusFirstItem()
                true
            } else {
                false
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val rtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val towardContent =
                if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val towardCategories =
                if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            when {
                event.keyCode == towardContent && binding.categoryList.hasFocus() -> {
                    focusFirstItem()
                    return true
                }
                event.keyCode == towardContent &&
                    binding.searchInput.hasFocus() &&
                    binding.searchInput.selectionStart >=
                    (binding.searchInput.text?.length ?: 0) -> {
                    binding.searchInput.hideSoftKeyboard()
                    binding.refreshButton.requestFocus()
                    return true
                }
                event.keyCode == towardContent && binding.refreshButton.hasFocus() -> {
                    if (binding.sortButton.visibility == View.VISIBLE) {
                        binding.sortButton.requestFocus()
                    }
                    return true
                }
                event.keyCode == towardContent && binding.sortButton.hasFocus() -> {
                    return true
                }
                event.keyCode == towardCategories &&
                    binding.posterGrid.hasFocus() &&
                    focusedPosterIsInStartColumn() -> {
                    focusCurrentCategory()
                    return true
                }
                event.keyCode == towardCategories && binding.sortButton.hasFocus() -> {
                    binding.refreshButton.requestFocus()
                    return true
                }
                event.keyCode == towardCategories && binding.refreshButton.hasFocus() -> {
                    binding.searchInput.requestFocus()
                    return true
                }
                event.keyCode == towardCategories &&
                    binding.searchInput.hasFocus() &&
                    binding.searchInput.selectionStart <= 0 -> {
                    binding.searchInput.hideSoftKeyboard()
                    focusCurrentCategory()
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    binding.searchInput.hasFocus() -> {
                        binding.searchInput.hideSoftKeyboard()
                        focusFirstItem()
                        return true
                    }
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    (binding.refreshButton.hasFocus() || binding.sortButton.hasFocus()) -> {
                    focusGridAt(lastFocusedGridPosition)
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                    binding.posterGrid.hasFocus() &&
                    focusedPosterIsInFirstRow() -> {
                    focusToolbarForCurrentColumn()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (viewModel.query.value.isNotEmpty() || binding.searchInput.hasFocus()) {
            binding.searchInput.text?.clear()
            binding.searchInput.hideSoftKeyboard()
            binding.searchInput.clearFocus()
            focusCurrentCategory()
            return
        }
        super.onBackPressed()
    }

    /** Localised "Sort: <order>" label for the header chip. */
    private fun sortLabel(order: ContentSort): String {
        val option = when (order) {
            ContentSort.RECENT -> R.string.sort_recent
            ContentSort.NAME -> R.string.sort_az
            ContentSort.RATING -> R.string.sort_rating
            ContentSort.YEAR -> R.string.sort_year
        }
        return "${getString(R.string.sort_label)}: ${getString(option)}"
    }

    /**
     * Header title for the current selection. The "Recently added" rail keeps its
     * label only while newest-first; any other sort relabels it "All series" so the
     * heading never contradicts the visible order. Real categories show their name.
     */
    private fun updateContentTitle() {
        val query = viewModel.query.value
        val cat = currentCategory
        binding.contentTitle.text = when {
            query.isNotEmpty() -> getString(R.string.search_results_title)
            cat == null -> getString(R.string.nav_series)
            cat.id == SeriesViewModel.CAT_ALL -> {
                if (viewModel.sort.value == ContentSort.RECENT) {
                    getString(R.string.cat_recently_added)
                } else {
                    getString(R.string.all_series)
                }
            }
            else -> cat.name
        }
        binding.sortButton.visibility =
            if (query.isEmpty() && cat?.id == SeriesViewModel.CAT_POPULAR) View.GONE
            else View.VISIBLE
    }

    /**
     * One rendering function owns loading, empty and error visibility. Refreshing
     * a populated cache keeps the posters usable instead of covering them with a
     * blocking spinner/error card.
     */
    private fun renderScreenState() {
        val hasContent = seriesAdapter.itemCount > 0
        val pagingLoading = pagingRefreshState is LoadState.Loading
        val blockingLoading = !hasContent && (catalogLoadState.loading || pagingLoading)
        val errorRes = if (!hasContent) {
            catalogLoadState.errorRes
                ?: if (pagingRefreshState is LoadState.Error) R.string.error_unknown else null
        } else {
            null
        }
        val empty = currentCategory != null && adapterRefreshSettled && !hasContent &&
            !blockingLoading && errorRes == null

        binding.emptyState.setText(
            if (viewModel.query.value.isNotEmpty()) R.string.empty_search
            else R.string.empty_series
        )
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.loadingContainer.visibility = if (blockingLoading) View.VISIBLE else View.GONE
        val showLoadingStatus = blockingLoading
        val showProgress = catalogLoadState.loading && catalogLoadState.total > 1
        binding.loadingStatus.visibility =
            if (showLoadingStatus) View.VISIBLE else View.GONE
        if (showProgress) {
            binding.loadingStatus.text = getString(
                R.string.catalog_loading_progress,
                catalogLoadState.completed,
                catalogLoadState.total,
            )
        } else if (showLoadingStatus) {
            binding.loadingStatus.setText(R.string.loading)
        }
        binding.loadErrorContainer.visibility =
            if (errorRes != null && !blockingLoading) View.VISIBLE else View.GONE
        errorRes?.let { binding.loadErrorText.setText(it) }

        // Keep an already-focused refresh control in the D-pad graph while the
        // request runs; clickability still prevents duplicate refreshes.
        binding.refreshButton.isClickable = !catalogLoadState.loading
        binding.refreshButton.alpha = if (catalogLoadState.loading) 0.56f else 1f
        if (errorRes != null && !hasContent) {
            binding.retryButton.post { binding.retryButton.requestFocus() }
        } else if (
            restoreFocusAfterRetry &&
            !blockingLoading &&
            adapterRefreshSettled
        ) {
            restoreFocusAfterRetry = false
            if (hasContent) focusGridAt(lastFocusedGridPosition) else focusCurrentCategory()
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Mask adult posters from the very first paging bind. adultLocked
                // otherwise stays false until onResume's async read lands, so adult
                // artwork/titles could flash unmasked on entry before then.
                val settings = ServiceLocator.settings
                seriesAdapter.adultLocked = settings.lockAdult.first() && settings.hasPin()
                launch {
                    viewModel.categories.collectLatest { cats ->
                        val firstLoad = categoryAdapter.currentList.isEmpty()
                        val categoryRailHadFocus = binding.categoryList.hasFocus()
                        categoryAdapter.submitList(cats) {
                            if (cats.isEmpty()) {
                                currentCategory = null
                                updateContentTitle()
                                return@submitList
                            }
                            val selectedId =
                                currentCategory?.id
                                    ?: viewModel.selectedCategoryId
                                    ?: restoredCategoryId
                            val selectionStillVisible = cats.any { it.id == selectedId }
                            if (firstLoad || !selectionStillVisible) {
                                // Restore the prior selection (survives config
                                // change via the ViewModel) instead of always
                                // snapping back to the first category.
                                val target =
                                    cats.firstOrNull { it.id == selectedId } ?: cats.first()
                                // Reselect whenever the restored target differs (incl.
                                // a now-hidden/removed prior selection), so the grid and
                                // any lazy fetch align with a still-visible category.
                                if (viewModel.selectedCategoryId != target.id) {
                                    viewModel.selectCategory(target.id)
                                }
                                categoryAdapter.setSelected(target.id)
                                currentCategory = target
                                restoredCategoryId = null
                                updateContentTitle()
                                if (firstLoad) {
                                    restoreInitialFocus()
                                } else {
                                    // Content Manager removed/hidden the active
                                    // category. Move selection and focus together
                                    // so the rail and grid cannot disagree.
                                    adapterRefreshSettled = false
                                    lastFocusedGridPosition = 0
                                    lastFocusedSeriesId = null
                                    if (categoryRailHadFocus) {
                                        focusCurrentCategory()
                                    }
                                }
                            }
                        }
                    }
                }
                launch {
                    viewModel.items.collectLatest { data ->
                        seriesAdapter.submitData(data)
                    }
                }
                launch {
                    viewModel.sort.collectLatest { order ->
                        binding.sortButton.text = sortLabel(order)
                        updateContentTitle()
                    }
                }
                launch {
                    viewModel.query.collectLatest {
                        updateContentTitle()
                        renderScreenState()
                    }
                }
                // When a fresh page settles (category switch / new search), jump to the top.
                launch {
                    seriesAdapter.loadStateFlow
                        .distinctUntilChangedBy { it.refresh }
                        .collectLatest { state ->
                            // Only snap to the top when the user is NOT already inside the
                            // grid. Lazily-downloaded series write to Room while browsing,
                            // which invalidates the PagingSource and re-settles refresh; an
                            // unconditional scrollToPosition(0) then yanked the grid to the
                            // top mid-scroll and focus escaped back to the category list.
                            if (
                                state.refresh is LoadState.NotLoading &&
                                !binding.posterGrid.hasFocus() &&
                                pendingGridFocusPosition == null
                            ) {
                                binding.posterGrid.scrollToPosition(0)
                            }
                        }
                }
                // Empty state: only once the refresh has settled with nothing to show.
                launch {
                    seriesAdapter.loadStateFlow.collectLatest { state ->
                        pagingRefreshState = state.refresh
                        adapterRefreshSettled = state.refresh is LoadState.NotLoading
                        val pendingPosition = pendingGridFocusPosition
                        if (adapterRefreshSettled && pendingPosition != null &&
                            seriesAdapter.itemCount > 0) {
                            pendingGridFocusPosition = null
                            val target = pendingPosition.coerceAtMost(seriesAdapter.itemCount - 1)
                            lastFocusedGridPosition = target
                            binding.posterGrid.requestFocusAt(target)
                        }
                        renderScreenState()
                    }
                }
                launch {
                    viewModel.loadState.collectLatest { state ->
                        catalogLoadState = state
                        renderScreenState()
                    }
                }
            }
        }
    }

    /** Moves focus onto the first category row (the on-entry focus target). */
    private fun focusFirstCategory() {
        binding.categoryList.requestFocusAt(0)
    }

    /** Moves focus into the poster grid, always landing on the first item. */
    private fun focusFirstItem() {
        focusGridAt(0)
    }

    /** Moves focus into the grid, deferring until Paging commits when necessary. */
    private fun focusGridAt(position: Int) {
        if (!adapterRefreshSettled || seriesAdapter.itemCount == 0) {
            pendingGridFocusPosition = position.coerceAtLeast(0)
            return
        }
        pendingGridFocusPosition = null
        binding.searchInput.hideSoftKeyboard()
        val target = position.coerceIn(0, seriesAdapter.itemCount - 1)
        lastFocusedGridPosition = target
        binding.posterGrid.requestFocusAt(target)
    }

    private fun focusCurrentCategory() {
        pendingGridFocusPosition = null
        val id = currentCategory?.id
        val position = categoryAdapter.currentList.indexOfFirst { it.id == id }
            .takeIf { it >= 0 } ?: 0
        binding.categoryList.requestFocusAt(position)
    }

    /** Position zero is at layout-start in both LTR and RTL GridLayoutManager. */
    private fun focusedPosterIsInStartColumn(): Boolean {
        val focused = currentFocus ?: return false
        val holder = binding.posterGrid.findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        val span = (binding.posterGrid.layoutManager as? GridLayoutManager)?.spanCount ?: return false
        return position % span == 0
    }

    private fun focusedPosterIsInFirstRow(): Boolean {
        val position = focusedPosterPosition() ?: return false
        val span = (binding.posterGrid.layoutManager as? GridLayoutManager)?.spanCount ?: return false
        return position in 0 until span
    }

    private fun focusedPosterPosition(): Int? {
        val focused = currentFocus ?: return null
        val holder = binding.posterGrid.findContainingViewHolder(focused) ?: return null
        return holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
    }

    /** Maps the grid's logical column to the toolbar without relying on geometry. */
    private fun focusToolbarForCurrentColumn() {
        val position = focusedPosterPosition() ?: 0
        val span = (binding.posterGrid.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        val column = position % span
        when {
            column == span - 1 && binding.sortButton.visibility == View.VISIBLE ->
                binding.sortButton.requestFocus()
            column >= span - 2 -> binding.refreshButton.requestFocus()
            else -> binding.searchInput.requestFocus()
        }
    }

    /** Restores the last logical focus zone only after categories are attached. */
    private fun restoreInitialFocus() {
        if (initialFocusRestored) return
        initialFocusRestored = true
        when (restoredFocusZone) {
            FOCUS_GRID -> restoreGridFocus()
            FOCUS_SEARCH -> binding.searchInput.post { binding.searchInput.requestFocus() }
            FOCUS_REFRESH -> binding.refreshButton.post { binding.refreshButton.requestFocus() }
            FOCUS_SORT -> {
                if (binding.sortButton.visibility == View.VISIBLE) {
                    binding.sortButton.post { binding.sortButton.requestFocus() }
                } else {
                    focusCurrentCategory()
                }
            }
            else -> {
                if (viewModel.query.value.isNotEmpty()) {
                    binding.searchInput.post { binding.searchInput.requestFocus() }
                } else {
                    focusCurrentCategory()
                }
            }
        }
    }

    private fun openDetail(id: String) {
        restoreGridFocusOnResume = true
        val intent = Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, id)
        }
        startActivity(intent)
    }

    private fun restoreGridFocus() {
        val id = lastFocusedSeriesId
        val positionById = if (id == null) {
            -1
        } else {
            seriesAdapter.snapshot().items.indexOfFirst { it.id == id }
        }
        focusGridAt(
            if (positionById >= 0) positionById
            else lastFocusedGridPosition.coerceAtLeast(restoredGridPosition),
        )
    }
}
