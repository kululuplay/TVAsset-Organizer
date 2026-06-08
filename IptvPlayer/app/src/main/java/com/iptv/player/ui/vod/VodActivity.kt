/*
 * VodActivity.kt
 * Movies (VOD) screen: categories on the left and a focusable poster grid in the
 * center. Clicking a poster opens the VOD detail screen. Fully D-pad driven for TV.
 */
package com.iptv.player.ui.vod

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
import com.iptv.player.data.model.ContentSort
import com.iptv.player.databinding.ActivityVodBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.NewContentPopup
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.util.NewContentNotifier
import com.iptv.player.ui.common.autoFitColumns
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.home.CategoryAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
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

    /** Sort orders cycled by the header button, in display order. */
    private val sortCycle = listOf(
        ContentSort.RECENT, ContentSort.NAME, ContentSort.RATING, ContentSort.YEAR
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
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
            progressMap = viewModel.repoAllWatchProgress()
            watchedSet = viewModel.repoWatchedIds()
            vodAdapter.notifyDataSetChanged()
        }
    }

    private fun setupLists() {
        categoryAdapter = CategoryAdapter(
            onFocused = { cat ->
                viewModel.selectCategory(cat.id)
                categoryAdapter.setSelected(cat.id)
                binding.contentTitle.text = cat.name
            },
            onClicked = { focusFirstItem() }
        )
        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter

        vodAdapter = VodAdapter(
            onFocused = { },
            onClicked = { item ->
                PinLockHelper.guard(this, isAdult = item.isAdult()) { openDetail(item.id) }
            }
        )
        vodAdapter.progressProvider = { id -> progressMap[id] ?: 0 }
        vodAdapter.watchedProvider = { id -> id in watchedSet }
        binding.posterGrid.layoutManager = GridLayoutManager(this, 4)
        binding.posterGrid.autoFitColumns(min = 3)
        binding.posterGrid.adapter = vodAdapter

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

    private fun observe() {
        lifecycleScope.launch {
            viewModel.categories.collectLatest { cats ->
                val firstLoad = categoryAdapter.currentList.isEmpty()
                categoryAdapter.submitList(cats) {
                    if (firstLoad && cats.isNotEmpty()) {
                        val first = cats.first()
                        viewModel.selectCategory(first.id)
                        categoryAdapter.setSelected(first.id)
                        binding.contentTitle.text = first.name
                        // Land focus on the first category so the screen is
                        // navigable on entry and the user can D-pad down/right.
                        focusFirstCategory()
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.items.collectLatest { data ->
                vodAdapter.submitData(data)
            }
        }
        lifecycleScope.launch {
            viewModel.sort.collectLatest { order ->
                binding.sortButton.text = sortLabel(order)
            }
        }
        // When a fresh page settles (category switch / new search), jump to the top.
        lifecycleScope.launch {
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
        lifecycleScope.launch {
            vodAdapter.loadStateFlow.collectLatest { state ->
                val empty = state.refresh is LoadState.NotLoading && vodAdapter.itemCount == 0
                binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            }
        }
        // Lightweight spinner while a category's movies are lazily downloading.
        lifecycleScope.launch {
            viewModel.refreshing.collectLatest { loading ->
                binding.loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
                if (loading) binding.emptyState.visibility = View.GONE
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
        val intent = Intent(this, VodDetailActivity::class.java).apply {
            putExtra(VodDetailActivity.EXTRA_VOD_ID, id)
        }
        startActivity(intent)
    }
}
