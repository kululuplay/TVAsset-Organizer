/*
 * SeriesActivity.kt
 * Series screen: categories on the left and a focusable poster grid in the center.
 * Clicking a poster opens the series detail screen.
 * Mirrors VodActivity; fully D-pad driven for TV.
 */
package com.iptv.player.ui.series

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.iptv.player.databinding.ActivitySeriesBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.NewContentPopup
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.util.NewContentNotifier
import com.iptv.player.ui.common.autoFitColumns
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.home.CategoryAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SeriesActivity : BaseActivity() {

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

    /** Sort orders cycled by the header button, in display order. */
    private val sortCycle = listOf(
        ContentSort.RECENT, ContentSort.NAME, ContentSort.RATING, ContentSort.YEAR
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        observe()
        observeNewContent()
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
            seriesAdapter.notifyItemRangeChanged(0, seriesAdapter.itemCount)
        }
    }

    private fun setupLists() {
        categoryAdapter = CategoryAdapter(
            onFocused = { cat ->
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
            onFocused = { },
            onClicked = { item ->
                PinLockHelper.guard(this, isAdult = item.isAdult()) { openDetail(item.id) }
            }
        )
        seriesAdapter.progressProvider = { id -> progressMap[id] ?: 0 }
        binding.posterGrid.layoutManager = GridLayoutManager(this, 4)
        binding.posterGrid.autoFitColumns(min = 3)
        binding.posterGrid.adapter = seriesAdapter

        binding.sortButton.setOnClickListener {
            val next = sortCycle[(sortCycle.indexOf(viewModel.sort.value) + 1) % sortCycle.size]
            viewModel.setSort(next)
        }
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
        val cat = currentCategory ?: return
        binding.contentTitle.text = if (cat.id == SeriesViewModel.CAT_ALL) {
            if (viewModel.sort.value == ContentSort.RECENT) {
                getString(R.string.cat_recently_added)
            } else {
                getString(R.string.all_series)
            }
        } else {
            cat.name
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                                // Land focus on the first category so the screen is
                                // navigable on entry and the user can D-pad down/right.
                                focusFirstCategory()
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
                            if (state.refresh is LoadState.NotLoading && !binding.posterGrid.hasFocus()) {
                                binding.posterGrid.scrollToPosition(0)
                            }
                        }
                }
                // Empty state: only once the refresh has settled with nothing to show.
                launch {
                    seriesAdapter.loadStateFlow.collectLatest { state ->
                        val empty = state.refresh is LoadState.NotLoading && seriesAdapter.itemCount == 0
                        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                    }
                }
                // Lightweight spinner while a category's series are lazily downloading.
                launch {
                    viewModel.refreshing.collectLatest { loading ->
                        binding.loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
                        if (loading) binding.emptyState.visibility = View.GONE
                    }
                }
            }
        }
    }

    /** Moves focus onto the first category row (the on-entry focus target). */
    private fun focusFirstCategory() {
        binding.categoryList.post {
            (binding.categoryList.findViewHolderForAdapterPosition(0)?.itemView
                ?: binding.categoryList).requestFocus()
        }
    }

    /** Moves focus into the poster grid, always landing on the first item. */
    private fun focusFirstItem() {
        binding.posterGrid.scrollToPosition(0)
        binding.posterGrid.post {
            (binding.posterGrid.findViewHolderForAdapterPosition(0)?.itemView
                ?: binding.posterGrid).requestFocus()
        }
    }

    private fun openDetail(id: String) {
        val intent = Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, id)
        }
        startActivity(intent)
    }
}
