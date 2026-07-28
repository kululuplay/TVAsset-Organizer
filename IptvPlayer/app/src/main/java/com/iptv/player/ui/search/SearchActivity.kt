/*
 * SearchActivity.kt
 * Global search across Live channels, Movies and Series. A single query field
 * fans out to three result columns; clicking a result opens the right screen
 * (live player, movie detail, or series detail). Fully D-pad driven for TV.
 */
package com.iptv.player.ui.search

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivitySearchBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.SafeGridLayoutManager
import com.iptv.player.ui.common.autoFitColumns
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.home.ChannelAdapter
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.ui.series.SeriesAdapter
import com.iptv.player.ui.series.SeriesDetailActivity
import com.iptv.player.ui.vod.VodAdapter
import com.iptv.player.ui.vod.VodDetailActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SearchActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val viewModel: SearchViewModel by lazy {
        ViewModelProvider(this)[SearchViewModel::class.java]
    }

    private lateinit var liveAdapter: ChannelAdapter
    private lateinit var movieAdapter: VodAdapter
    private lateinit var seriesAdapter: SeriesAdapter

    private var hasLive = false
    private var hasMovies = false
    private var hasSeries = false
    private var hasQuery = false
    private var adultLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        setupSearch()
        binding.catalogRetryButton.setOnClickListener { viewModel.retryCatalog() }
        observe()
    }

    override fun onResume() {
        super.onResume()
        binding.dateText.text = DateFormat
            .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date())
        // Re-sync adult masking in case the parental lock was toggled in Settings
        // while we were away. Paging-safe rebind only (never notify* a Paging adapter).
        lifecycleScope.launch {
            val locked = ServiceLocator.settings.lockAdult.first() &&
                ServiceLocator.settings.hasPin()
            applyAdultMask(locked)
        }
    }

    private fun setupLists() {
        liveAdapter = ChannelAdapter(
            onFocused = { },
            onClicked = { ch ->
                PinLockHelper.guard(this, isAdult = ch.isAdult()) { openLive(ch) }
            },
            onToggleFavorite = { /* favorite toggling isn't offered from search */ }
        )
        liveAdapter.lockedProvider = { adultLocked && it.isAdult() }
        binding.liveList.layoutManager = LinearLayoutManager(this)
        binding.liveList.adapter = liveAdapter

        movieAdapter = VodAdapter(
            onFocused = { },
            onClicked = { item ->
                PinLockHelper.guard(this, isAdult = item.isAdult()) { openMovie(item.id) }
            }
        )
        binding.movieGrid.layoutManager = SafeGridLayoutManager(this, 2)
        binding.movieGrid.autoFitColumns(min = 2)
        binding.movieGrid.adapter = movieAdapter

        seriesAdapter = SeriesAdapter(
            onFocused = { },
            onClicked = { item ->
                PinLockHelper.guard(this, isAdult = item.isAdult()) { openSeries(item.id) }
            }
        )
        binding.seriesGrid.layoutManager = SafeGridLayoutManager(this, 2)
        binding.seriesGrid.autoFitColumns(min = 2)
        binding.seriesGrid.adapter = seriesAdapter
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString().orEmpty()
                hasQuery = text.isNotBlank()
                viewModel.setQuery(text)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observe() {
        // Mask adult posters/titles from the very first paging bind: read the
        // parental-lock setting BEFORE starting any result collector, in the SAME
        // coroutine, so the DataStore read can't race the first live/poster bind
        // and leak adult metadata. Changes while away are handled in onResume.
        lifecycleScope.launch {
            val locked = ServiceLocator.settings.lockAdult.first() &&
                ServiceLocator.settings.hasPin()
            applyAdultMask(locked)
            launch {
                viewModel.channels.collectLatest { list ->
                    liveAdapter.submitList(list)
                    hasLive = list.isNotEmpty()
                    updateEmptyState()
                }
            }
            launch {
                viewModel.movies.collectLatest { data -> movieAdapter.submitData(data) }
            }
            launch {
                viewModel.series.collectLatest { data -> seriesAdapter.submitData(data) }
            }
        }
        lifecycleScope.launch {
            movieAdapter.loadStateFlow.collectLatest { state ->
                if (state.refresh is LoadState.NotLoading) {
                    hasMovies = movieAdapter.itemCount > 0
                    updateEmptyState()
                }
            }
        }
        lifecycleScope.launch {
            seriesAdapter.loadStateFlow.collectLatest { state ->
                if (state.refresh is LoadState.NotLoading) {
                    hasSeries = seriesAdapter.itemCount > 0
                    updateEmptyState()
                }
            }
        }
        lifecycleScope.launch {
            viewModel.catalogState.collectLatest { state ->
                val visible = state.loading || state.errorRes != null
                binding.catalogStatusRow.visibility = if (visible) View.VISIBLE else View.GONE
                binding.catalogProgress.visibility = if (state.loading) View.VISIBLE else View.GONE
                binding.catalogRetryButton.visibility =
                    if (state.errorRes != null) View.VISIBLE else View.GONE
                binding.catalogStatusText.text = when {
                    state.loading -> getString(
                        R.string.catalog_loading_progress,
                        state.completed,
                        state.total,
                    )
                    state.errorRes != null -> getString(state.errorRes)
                    else -> ""
                }
                updateEmptyState()
            }
        }
    }

    /** Applies parental masking without invalidating Paging adapters or TV focus. */
    private fun applyAdultMask(locked: Boolean) {
        if (adultLocked == locked &&
            movieAdapter.adultLocked == locked &&
            seriesAdapter.adultLocked == locked
        ) {
            return
        }
        adultLocked = locked
        movieAdapter.adultLocked = locked
        seriesAdapter.adultLocked = locked
        liveAdapter.refreshVisible(binding.liveList)
        movieAdapter.refreshVisible(binding.movieGrid)
        seriesAdapter.refreshVisible(binding.seriesGrid)
    }

    /** Show the "no results" hint only once a query is entered and nothing matched. */
    private fun updateEmptyState() {
        val catalogState = viewModel.catalogState.value
        val nothing = hasQuery && !hasLive && !hasMovies && !hasSeries &&
            !catalogState.loading && catalogState.errorRes == null
        binding.emptyState.visibility = if (nothing) View.VISIBLE else View.GONE
        binding.resultsRow.visibility = if (nothing) View.GONE else View.VISIBLE
    }

    private fun openLive(channel: com.iptv.player.data.model.Channel) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(PlayerActivity.EXTRA_CATEGORY_ID, channel.categoryId)
                .putExtra(PlayerActivity.EXTRA_PARENTAL_AUTHORIZED, true)
        )
    }

    private fun openMovie(id: String) {
        startActivity(
            Intent(this, VodDetailActivity::class.java)
                .putExtra(VodDetailActivity.EXTRA_VOD_ID, id)
        )
    }

    private fun openSeries(id: String) {
        startActivity(
            Intent(this, SeriesDetailActivity::class.java)
                .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, id)
        )
    }
}
