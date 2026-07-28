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
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
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

    /** Focus the first fresh poster once an async category/search page commits. */
    private var pendingGridFocus = false

    /** Return focus to browse content after the retry card disappears. */
    private var restoreFocusAfterRetry = false

    /** Sort orders cycled by the header button, in display order. */
    private val sortCycle = listOf(
        ContentSort.RECENT, ContentSort.NAME, ContentSort.RATING, ContentSort.YEAR
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                pendingGridFocus = false
                val categoryChanged = currentCategory?.id != cat.id
                if (viewModel.query.value.isNotEmpty()) {
                    binding.searchInput.text?.clear()
                }
                if (categoryChanged) adapterRefreshSettled = false
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
        vodAdapter.progressProvider = { id -> progressMap[id] ?: 0 }
        vodAdapter.watchedProvider = { id -> id in watchedSet }
        binding.posterGrid.layoutManager = SafeGridLayoutManager(this, 4)
        binding.posterGrid.autoFitColumns(min = 4)
        binding.posterGrid.adapter = vodAdapter
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
            viewModel.retryLoad()
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
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (binding.categoryList.hasFocus()) {
                        focusFirstItem()
                        return true
                    }
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (binding.posterGrid.hasFocus() && focusedPosterIsInFirstColumn()) {
                        focusCurrentCategory()
                        return true
                    }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (binding.searchInput.hasFocus()) {
                        binding.searchInput.hideSoftKeyboard()
                        focusFirstItem()
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
        val empty = adapterRefreshSettled && vodAdapter.itemCount == 0 &&
            !catalogState.loading && catalogState.errorRes == null
        binding.emptyState.setText(
            if (viewModel.query.value.isNotEmpty()) R.string.empty_search
            else R.string.empty_movies
        )
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun renderCatalogState(state: com.iptv.player.ui.common.CatalogLoadState) {
        binding.loadingContainer.visibility = if (state.loading) View.VISIBLE else View.GONE
        val showProgress = state.loading && state.total > 1
        binding.loadingStatus.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            binding.loadingStatus.text = getString(
                R.string.catalog_loading_progress,
                state.completed,
                state.total,
            )
        }
        val failed = state.errorRes != null
        binding.loadErrorContainer.visibility = if (failed) View.VISIBLE else View.GONE
        state.errorRes?.let { binding.loadErrorText.setText(it) }
        // Keep an already-focused refresh control in the D-pad graph while the
        // request runs; clickability still prevents duplicate refreshes.
        binding.refreshButton.isClickable = !state.loading
        binding.refreshButton.alpha = if (state.loading) 0.5f else 1f
        if (failed && vodAdapter.itemCount == 0) {
            binding.retryButton.post { binding.retryButton.requestFocus() }
        } else if (restoreFocusAfterRetry && !state.loading) {
            restoreFocusAfterRetry = false
            focusFirstItem()
        }
        renderEmptyState()
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
                        val firstLoad = categoryAdapter.currentList.isEmpty()
                        categoryAdapter.submitList(cats) {
                            if (firstLoad && cats.isNotEmpty()) {
                                // Restore the prior selection (survives config
                                // change via the ViewModel) instead of always
                                // snapping back to the first category.
                                val existing = viewModel.selectedCategoryId
                                val target = cats.firstOrNull { it.id == existing } ?: cats.first()
                                // Reselect whenever the restored target differs (incl.
                                // a now-hidden/removed prior selection), so the grid and
                                // any lazy fetch align with a still-visible category.
                                if (viewModel.selectedCategoryId != target.id) {
                                    viewModel.selectCategory(target.id)
                                }
                                categoryAdapter.setSelected(target.id)
                                currentCategory = target
                                updateContentTitle()
                                // Preserve an active search across recreation. Focusing
                                // a category invokes onFocused and intentionally clears
                                // search, so only do that in browse mode.
                                if (viewModel.query.value.isNotEmpty()) {
                                    binding.searchInput.post {
                                        binding.searchInput.requestFocus()
                                    }
                                } else {
                                    focusFirstCategory()
                                }
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
                            if (state.refresh is LoadState.NotLoading && !binding.posterGrid.hasFocus()) {
                                binding.posterGrid.scrollToPosition(0)
                            }
                        }
                }
                // Empty state: only once the refresh has settled with nothing to show.
                launch {
                    vodAdapter.loadStateFlow.collectLatest { state ->
                        adapterRefreshSettled = state.refresh is LoadState.NotLoading
                        if (
                            adapterRefreshSettled &&
                            pendingGridFocus &&
                            vodAdapter.itemCount > 0
                        ) {
                            pendingGridFocus = false
                            binding.posterGrid.requestFocusAt(0)
                        }
                        renderEmptyState()
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

    /** Moves focus onto the first category row (the on-entry focus target). */
    private fun focusFirstCategory() {
        binding.categoryList.requestFocusAt(0)
    }

    /** Moves focus into the poster grid, always landing on the first item. */
    private fun focusFirstItem() {
        if (!adapterRefreshSettled || vodAdapter.itemCount == 0) {
            pendingGridFocus = true
            return
        }
        pendingGridFocus = false
        binding.searchInput.hideSoftKeyboard()
        binding.posterGrid.requestFocusAt(0)
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
        if (position == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
        val span = (binding.posterGrid.layoutManager as? GridLayoutManager)?.spanCount ?: return false
        return position % span == 0
    }

    private fun openDetail(id: String) {
        val intent = Intent(this, VodDetailActivity::class.java).apply {
            putExtra(VodDetailActivity.EXTRA_VOD_ID, id)
        }
        startActivity(intent)
    }
}
