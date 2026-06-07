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

        seriesAdapter = SeriesAdapter(
            onFocused = { },
            onClicked = { item ->
                PinLockHelper.guard(this, isAdult = item.isAdult()) { openDetail(item.id) }
            }
        )
        binding.posterGrid.layoutManager = GridLayoutManager(this, 4)
        binding.posterGrid.autoFitColumns(min = 3)
        binding.posterGrid.adapter = seriesAdapter
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
                seriesAdapter.submitData(data)
            }
        }
        // When a fresh page settles (category switch / new search), jump to the top.
        lifecycleScope.launch {
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
        lifecycleScope.launch {
            seriesAdapter.loadStateFlow.collectLatest { state ->
                val empty = state.refresh is LoadState.NotLoading && seriesAdapter.itemCount == 0
                binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            }
        }
        // Lightweight spinner while a category's series are lazily downloading.
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
        val intent = Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, id)
        }
        startActivity(intent)
    }
}
