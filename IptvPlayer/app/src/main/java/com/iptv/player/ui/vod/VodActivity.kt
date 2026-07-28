/*
 * VodActivity.kt
 * Movies (VOD) screen: categories on the left and a focusable poster grid in the
 * center. Clicking a poster opens the VOD detail screen. Fully D-pad driven for TV.
 */
package com.iptv.player.ui.vod

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.ContentSort
import com.iptv.player.databinding.ActivityVodBinding
import com.iptv.player.ui.common.BaseActivity
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

class VodActivity : BaseActivity() {

    companion object {
        private const val STATE_FOCUS_REGION = "vod_focus_region"
        private const val STATE_POSTER_ID = "vod_focused_poster_id"
        private const val STATE_POSTER_POSITION = "vod_focused_poster_position"

        private const val FOCUS_CATEGORY = "category"
        private const val FOCUS_GRID = "grid"
        private const val FOCUS_SEARCH = "search"
        private const val FOCUS_REFRESH = "refresh"
        private const val FOCUS_SORT = "sort"
    }

    private lateinit var binding: ActivityVodBinding
    private val viewModel: VodViewModel by lazy {
        ViewModelProvider(this)[VodViewModel::class.java]
    }

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var vodAdapter: VodAdapter

    /** Watch-progress percent and watched ids, refreshed in [onResume]. */
    private var progressMap: Map<String, Int> = emptyMap()
    private var watchedSet: Set<String> = emptySet()

    /** The category currently shown, so the header title can follow the sort. */
    private var currentCategory: Category? = null

    /** Latest Paging refresh state, updated by the load-state collector. */
    private var adapterRefreshSettled = false
    private var pagingRefreshState: LoadState = LoadState.Loading

    /** Focus the first fresh poster once an async category/search page commits. */
    private var pendingGridFocus = false
    private var pendingGridPosition = 0

    /** Return focus to browse content after the retry card disappears. */
    private var restoreFocusAfterRetry = false

    /** Exact poster anchor used after detail playback and configuration changes. */
    private var lastFocusedPosterId: String? = null
    private var lastFocusedPosterPosition = 0
    private var restorePosterOnResume = false

    /** Initial D-pad region is restored only after async categories are attached. */
    private var restoredFocusRegion: String? = null
    private var initialFocusApplied = false
    private var lastKnownFocusRegion = FOCUS_CATEGORY

    /** Sort orders cycled by the header button, in display order. */
    private val sortCycle = listOf(
        ContentSort.RECENT, ContentSort.NAME, ContentSort.RATING, ContentSort.YEAR
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setAccessibilityHeading(binding.contentTitle, true)
        ViewCompat.setAccessibilityPaneTitle(
            binding.contentPanel,
            getString(R.string.nav_movies),
        )

        restoredFocusRegion = savedInstanceState?.getString(STATE_FOCUS_REGION)
        lastKnownFocusRegion = restoredFocusRegion ?: FOCUS_CATEGORY
        lastFocusedPosterId = savedInstanceState?.getString(STATE_POSTER_ID)
        lastFocusedPosterPosition =
            savedInstanceState?.getInt(STATE_POSTER_POSITION, 0) ?: 0

        setupLists()
        setupSearch()
        observe()
        observeNewContent()
    }

    /**
     * Flashes a transient top-right notice when the launch refresh found movies
     * that weren't cached before, then clears the tally so it shows only once.
     */
    private fun observeNewContent() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NewContentNotifier.newMovies.collectLatest { count ->
                    if (count > 0) {
                        NewContentPopup.show(
                            this@VodActivity,
                            R.drawable.ic_movie,
                            resources.getQuantityString(R.plurals.new_movies_added, count, count)
                        )
                        NewContentNotifier.consumeMovies()
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
        if (restorePosterOnResume) {
            restorePosterOnResume = false
            binding.posterGrid.post { restorePosterFocus() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FOCUS_REGION, focusedRegion())
        outState.putString(STATE_POSTER_ID, lastFocusedPosterId)
        outState.putInt(STATE_POSTER_POSITION, lastFocusedPosterPosition)
        super.onSaveInstanceState(outState)
    }

    /**
     * Reloads watch-progress and watched-state maps (cheap Room reads) so the
     * poster bars and ticks reflect playback that happened since we left, then
     * rebinds the visible cards.
     */
    private fun refreshWatchState() {
        lifecycleScope.launch {
            val settings = ServiceLocator.settings
            vodAdapter.adultLocked = settings.lockAdult.first() && settings.hasPin()
            progressMap = viewModel.repoAllWatchProgress()
            watchedSet = viewModel.repoWatchedIds()
            // Direct rebind of attached cards only. A blanket notifyItemRangeChanged
            // on this Paging adapter races Paging's own page invalidation and crashes
            // with "Inconsistency detected", bouncing the user to the Dashboard.
            vodAdapter.refreshVisible(binding.posterGrid)
        }
    }

    private fun setupLists() {
        categoryAdapter = CategoryAdapter(
            onFocused = { cat ->
                lastKnownFocusRegion = FOCUS_CATEGORY
                pendingGridFocus = false
                val categoryChanged = currentCategory?.id != cat.id
                if (viewModel.query.value.isNotEmpty()) {
                    binding.searchInput.text?.clear()
                }
                if (categoryChanged) {
                    adapterRefreshSettled = false
                    resetPosterAnchor()
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
        // Counts update live as movies lazy-load, re-emitting the category list.
        // The default change-animation detaches the focused row mid-diff, so fast
        // D-pad browsing loses focus and the screen pops back to the Dashboard.
        // Disabling change animations rebinds the row in place and keeps focus.
        (binding.categoryList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        vodAdapter = VodAdapter(
            onFocused = { },
            onClicked = { item ->
                PinLockHelper.guard(this, isAdult = item.isAdult()) { openDetail(item.id) }
            }
        )
        vodAdapter.onFocusedAt = { item, position ->
            lastKnownFocusRegion = FOCUS_GRID
            lastFocusedPosterId = item.id
            lastFocusedPosterPosition = position
        }
        vodAdapter.progressProvider = { id -> progressMap[id] ?: 0 }
        vodAdapter.watchedProvider = { id -> id in watchedSet }
        binding.posterGrid.layoutManager = SafeGridLayoutManager(this, 4)
        binding.posterGrid.autoFitColumns(min = 4)
        binding.posterGrid.adapter = vodAdapter
        // A modest cache keeps the immediately adjacent TV row warm without
        // retaining dozens of full-size bitmaps on low-memory streaming sticks.
        binding.posterGrid.setItemViewCacheSize(8)
        (binding.posterGrid.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        binding.sortButton.setOnClickListener {
            resetPosterAnchor()
            val next = sortCycle[(sortCycle.indexOf(viewModel.sort.value) + 1) % sortCycle.size]
            adapterRefreshSettled = false
            viewModel.setSort(next)
        }
        binding.refreshButton.setOnClickListener {
            if (!viewModel.loadState.value.loading) {
                resetPosterAnchor()
                viewModel.refreshCatalog()
            }
        }
        binding.refreshButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) lastKnownFocusRegion = FOCUS_REFRESH
        }
        binding.sortButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) lastKnownFocusRegion = FOCUS_SORT
        }
        binding.retryButton.setOnClickListener {
            restoreFocusAfterRetry = true
            if (pagingRefreshState is LoadState.Error) {
                adapterRefreshSettled = false
                vodAdapter.retry()
            } else {
                adapterRefreshSettled = pagingRefreshState is LoadState.NotLoading
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
                pendingGridFocus = false
                adapterRefreshSettled = false
                resetPosterAnchor()
                viewModel.setQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastKnownFocusRegion = FOCUS_SEARCH
            } else {
                binding.searchInput.hideSoftKeyboard()
            }
        }
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
            val towardGrid =
                if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val towardCategories =
                if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            when (event.keyCode) {
                towardGrid -> {
                    when {
                        binding.categoryList.hasFocus() -> focusFirstItem()
                        binding.searchInput.hasFocus() &&
                            binding.searchInput.selectionStart >=
                            (binding.searchInput.text?.length ?: 0) -> {
                            binding.searchInput.hideSoftKeyboard()
                            binding.refreshButton.requestFocus()
                        }
                        binding.refreshButton.hasFocus() &&
                            binding.sortButton.visibility == View.VISIBLE -> {
                            binding.sortButton.requestFocus()
                        }
                        binding.refreshButton.hasFocus() ||
                            binding.sortButton.hasFocus() -> Unit
                        else -> return super.dispatchKeyEvent(event)
                    }
                    return true
                }
                towardCategories -> {
                    when {
                        binding.posterGrid.hasFocus() &&
                            focusedPosterIsInFirstColumn() -> focusCurrentCategory()
                        binding.sortButton.hasFocus() -> binding.refreshButton.requestFocus()
                        binding.refreshButton.hasFocus() -> binding.searchInput.requestFocus()
                        binding.searchInput.hasFocus() &&
                            binding.searchInput.selectionStart <= 0 -> {
                            binding.searchInput.hideSoftKeyboard()
                            focusCurrentCategory()
                        }
                        binding.categoryList.hasFocus() -> Unit
                        else -> return super.dispatchKeyEvent(event)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (
                        binding.searchInput.hasFocus() ||
                        binding.refreshButton.hasFocus() ||
                        binding.sortButton.hasFocus() ||
                        binding.retryButton.hasFocus()
                    ) {
                        binding.searchInput.hideSoftKeyboard()
                        focusGridItem(lastFocusedPosterPosition)
                        return true
                    }
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (binding.posterGrid.hasFocus() && focusedPosterIsInFirstRow()) {
                        if (binding.loadErrorContainer.visibility == View.VISIBLE) {
                            binding.retryButton.requestFocus()
                        } else {
                            binding.searchInput.requestFocus()
                        }
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
     * label only while newest-first; any other sort relabels it "All movies" so the
     * heading never contradicts the visible order. Real categories show their name.
     */
    private fun updateContentTitle() {
        val query = viewModel.query.value
        val cat = currentCategory
        binding.contentTitle.text = when {
            query.isNotEmpty() -> getString(R.string.search_results_title)
            cat == null -> getString(R.string.nav_movies)
            cat.id == VodViewModel.CAT_ALL -> {
                if (viewModel.sort.value == ContentSort.RECENT) {
                    getString(R.string.cat_recently_added)
                } else {
                    getString(R.string.all_movies)
                }
            }
            else -> cat.name
        }
        // "You may like" always shows highest-rated first regardless of the sort
        // control. Search supports all sort modes, so keep the control there.
        binding.sortButton.visibility =
            if (query.isEmpty() && cat?.id == VodViewModel.CAT_POPULAR) View.GONE
            else View.VISIBLE
    }

    private fun renderEmptyState() {
        val catalogState = viewModel.loadState.value
        val hasBrowseContext =
            currentCategory != null || viewModel.query.value.isNotEmpty()
        val empty = hasBrowseContext &&
            adapterRefreshSettled && vodAdapter.itemCount == 0 &&
            !catalogState.loading && catalogState.errorRes == null &&
            pagingRefreshState !is LoadState.Error
        binding.emptyState.setText(
            if (viewModel.query.value.isNotEmpty()) R.string.empty_search
            else R.string.empty_movies
        )
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun renderCatalogState(state: com.iptv.player.ui.common.CatalogLoadState) {
        val hasContent = vodAdapter.itemCount > 0
        positionStatusOverlay(binding.loadingContainer, centered = !hasContent)
        positionStatusOverlay(binding.loadErrorContainer, centered = !hasContent)
        val errorRes = state.errorRes
            ?: if (pagingRefreshState is LoadState.Error) R.string.error_unknown else null
        val failed = errorRes != null
        val loading = (state.loading || pagingRefreshState is LoadState.Loading) && !failed
        binding.loadingContainer.visibility = if (loading) View.VISIBLE else View.GONE
        val showProgress = state.loading && state.total > 1
        binding.loadingStatus.visibility = if (loading) View.VISIBLE else View.GONE
        binding.loadingStatus.text = if (showProgress) {
            getString(
                R.string.catalog_loading_progress,
                state.completed,
                state.total,
            )
        } else getString(R.string.loading)
        binding.loadErrorContainer.visibility = if (failed) View.VISIBLE else View.GONE
        errorRes?.let { binding.loadErrorText.setText(it) }
        // Keep an already-focused refresh control in the D-pad graph while the
        // request runs; clickability still prevents duplicate refreshes.
        binding.refreshButton.isClickable = !state.loading
        binding.refreshButton.alpha = if (state.loading) 0.5f else 1f
        if (failed && (vodAdapter.itemCount == 0 || restoreFocusAfterRetry)) {
            restoreFocusAfterRetry = false
            binding.retryButton.post { binding.retryButton.requestFocus() }
        } else if (restoreFocusAfterRetry && !loading && adapterRefreshSettled) {
            restoreFocusAfterRetry = false
            if (hasContent) {
                focusFirstItem()
            } else if (viewModel.query.value.isNotEmpty()) {
                binding.searchInput.requestFocus()
            } else {
                focusCurrentCategory()
            }
        }
        renderEmptyState()
    }

    /**
     * A full-screen cold load/error is centered. When cached posters are already
     * usable, the same state becomes a compact top-end status card so catalog
     * hydration never blocks browsing or obscures the focused movie.
     */
    private fun positionStatusOverlay(view: View, centered: Boolean) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val desired = if (centered) Gravity.CENTER else Gravity.TOP or Gravity.END
        if (params.gravity != desired) {
            params.gravity = desired
            view.layoutParams = params
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Mask adult posters from the very first paging bind. adultLocked
                // otherwise stays false until onResume's async read lands, so adult
                // artwork/titles could flash unmasked on entry before then.
                val settings = ServiceLocator.settings
                vodAdapter.adultLocked = settings.lockAdult.first() && settings.hasPin()
                launch {
                    viewModel.categories.collectLatest { cats ->
                        categoryAdapter.submitList(cats) {
                            if (cats.isEmpty()) return@submitList

                            // Keep the selected model fresh as category counts/names
                            // update. If Content Manager hid the selected category,
                            // atomically fall back to the first visible row.
                            val existing = viewModel.selectedCategoryId
                            val target = cats.firstOrNull { it.id == existing } ?: cats.first()
                            val selectionMissing = existing != null && target.id != existing
                            val restoreGridAfterFallback =
                                selectionMissing && binding.posterGrid.hasFocus()
                            if (selectionMissing) {
                                adapterRefreshSettled = false
                                resetPosterAnchor()
                                if (restoreGridAfterFallback) {
                                    pendingGridFocus = true
                                    pendingGridPosition = 0
                                }
                            }
                            viewModel.selectCategory(
                                target.id,
                                ensureLoaded = !initialFocusApplied || selectionMissing,
                            )
                            categoryAdapter.setSelected(target.id)
                            currentCategory = target
                            updateContentTitle()

                            if (!initialFocusApplied) {
                                initialFocusApplied = true
                                applyInitialFocus()
                            } else if (selectionMissing && binding.categoryList.hasFocus()) {
                                focusCurrentCategory()
                            }
                        }
                    }
                }
                launch {
                    viewModel.items.collectLatest { data ->
                        vodAdapter.submitData(data)
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
                        renderEmptyState()
                    }
                }
                // When a fresh page settles (category switch / new search), jump to the top.
                launch {
                    vodAdapter.loadStateFlow
                        .distinctUntilChangedBy { it.refresh }
                        .collectLatest { state ->
                            // Only snap to the top when the user is NOT already inside the
                            // grid. Lazily-downloaded movies write to Room while browsing,
                            // which invalidates the PagingSource and re-settles refresh; an
                            // unconditional scrollToPosition(0) then yanked the grid to the
                            // top mid-scroll and focus escaped back to the category list.
                            if (
                                state.refresh is LoadState.NotLoading &&
                                !binding.posterGrid.hasFocus() &&
                                !pendingGridFocus
                            ) {
                                binding.posterGrid.scrollToPosition(0)
                            }
                        }
                }
                // Empty state: only once the refresh has settled with nothing to show.
                launch {
                    vodAdapter.loadStateFlow.collectLatest { state ->
                        pagingRefreshState = state.refresh
                        adapterRefreshSettled = state.refresh is LoadState.NotLoading
                        if (
                            adapterRefreshSettled &&
                            pendingGridFocus &&
                            vodAdapter.itemCount > 0
                        ) {
                            pendingGridFocus = false
                            binding.posterGrid.requestFocusAt(
                                pendingGridPosition.coerceAtMost(vodAdapter.itemCount - 1)
                            )
                        }
                        renderCatalogState(viewModel.loadState.value)
                    }
                }
                launch {
                    viewModel.loadState.collectLatest { state ->
                        renderCatalogState(state)
                    }
                }
            }
        }
    }

    /** Moves focus into the poster grid, always landing on the first item. */
    private fun focusFirstItem() {
        focusGridItem(0)
    }

    private fun focusGridItem(position: Int) {
        pendingGridPosition = position.coerceAtLeast(0)
        if (!adapterRefreshSettled || vodAdapter.itemCount == 0) {
            pendingGridFocus = true
            return
        }
        pendingGridFocus = false
        binding.searchInput.hideSoftKeyboard()
        binding.posterGrid.requestFocusAt(
            pendingGridPosition.coerceAtMost(vodAdapter.itemCount - 1)
        )
    }

    private fun restorePosterFocus() {
        val exactPosition = lastFocusedPosterId?.let { id ->
            vodAdapter.snapshot().items.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }
        focusGridItem(exactPosition ?: lastFocusedPosterPosition)
    }

    private fun resetPosterAnchor() {
        lastFocusedPosterId = null
        lastFocusedPosterPosition = 0
        pendingGridPosition = 0
    }

    private fun applyInitialFocus() {
        when (restoredFocusRegion) {
            FOCUS_GRID -> restorePosterFocus()
            FOCUS_SEARCH -> binding.searchInput.post { binding.searchInput.requestFocus() }
            FOCUS_REFRESH -> binding.refreshButton.post { binding.refreshButton.requestFocus() }
            FOCUS_SORT -> {
                val target =
                    if (binding.sortButton.visibility == View.VISIBLE) binding.sortButton
                    else binding.searchInput
                target.post { target.requestFocus() }
            }
            FOCUS_CATEGORY -> focusCurrentCategory()
            else -> {
                if (viewModel.query.value.isNotEmpty()) {
                    binding.searchInput.post { binding.searchInput.requestFocus() }
                } else {
                    focusCurrentCategory()
                }
            }
        }
        restoredFocusRegion = null
    }

    private fun focusedRegion(): String = when {
        binding.categoryList.hasFocus() -> FOCUS_CATEGORY
        binding.posterGrid.hasFocus() -> FOCUS_GRID
        binding.searchInput.hasFocus() -> FOCUS_SEARCH
        binding.refreshButton.hasFocus() -> FOCUS_REFRESH
        binding.sortButton.hasFocus() -> FOCUS_SORT
        else -> restoredFocusRegion ?: lastKnownFocusRegion
    }

    private fun focusCurrentCategory() {
        pendingGridFocus = false
        val id = currentCategory?.id
        val position = categoryAdapter.currentList.indexOfFirst { it.id == id }
            .takeIf { it >= 0 } ?: 0
        binding.categoryList.requestFocusAt(position)
    }

    private fun focusedPosterIsInFirstColumn(): Boolean {
        val focused = currentFocus ?: return false
        val holder = binding.posterGrid.findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        val span = (binding.posterGrid.layoutManager as? GridLayoutManager)?.spanCount ?: return false
        return position % span == 0
    }

    private fun focusedPosterIsInFirstRow(): Boolean {
        val focused = currentFocus ?: return false
        val holder = binding.posterGrid.findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        val span = (binding.posterGrid.layoutManager as? GridLayoutManager)?.spanCount ?: return false
        return position < span
    }

    private fun openDetail(id: String) {
        val focused = currentFocus
        val holder = focused?.let(binding.posterGrid::findContainingViewHolder)
        holder?.bindingAdapterPosition
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?.let { lastFocusedPosterPosition = it }
        lastFocusedPosterId = id
        restorePosterOnResume = true
        val intent = Intent(this, VodDetailActivity::class.java).apply {
            putExtra(VodDetailActivity.EXTRA_VOD_ID, id)
        }
        startActivity(intent)
    }
}
