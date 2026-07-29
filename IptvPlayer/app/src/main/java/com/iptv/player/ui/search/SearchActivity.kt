/*
 * SearchActivity.kt
 * Global TV-first search across Live channels, Movies and Series.
 */
package com.iptv.player.ui.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.databinding.ActivitySearchBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.SafeGridLayoutManager
import com.iptv.player.ui.common.autoFitColumns
import com.iptv.player.ui.common.hideSoftKeyboard
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.common.requestFocusAt
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

    companion object {
        private const val STATE_FILTER = "search_filter"
        private const val STATE_FOCUS_REGION = "search_focus_region"
        private const val STATE_LIVE_ID = "search_live_id"
        private const val STATE_LIVE_POSITION = "search_live_position"
        private const val STATE_MOVIE_ID = "search_movie_id"
        private const val STATE_MOVIE_POSITION = "search_movie_position"
        private const val STATE_SERIES_ID = "search_series_id"
        private const val STATE_SERIES_POSITION = "search_series_position"

        private const val FOCUS_INPUT = "input"
        private const val FOCUS_CLEAR = "clear"
        private const val FOCUS_FILTER_ALL = "filter_all"
        private const val FOCUS_FILTER_LIVE = "filter_live"
        private const val FOCUS_FILTER_MOVIES = "filter_movies"
        private const val FOCUS_FILTER_SERIES = "filter_series"
        private const val FOCUS_LIVE = "live"
        private const val FOCUS_MOVIES = "movies"
        private const val FOCUS_SERIES = "series"
        private const val FOCUS_CATALOG_RETRY = "catalog_retry"
        private const val FOCUS_RESULT_RETRY = "result_retry"
        private const val NO_QUERY_GENERATION = "\u0000"
    }

    private enum class ResultFilter {
        ALL,
        LIVE,
        MOVIES,
        SERIES,
    }

    private lateinit var binding: ActivitySearchBinding
    private val viewModel: SearchViewModel by lazy {
        ViewModelProvider(this)[SearchViewModel::class.java]
    }

    private lateinit var liveAdapter: SearchChannelAdapter
    private lateinit var movieAdapter: VodAdapter
    private lateinit var seriesAdapter: SeriesAdapter

    private var activeFilter = ResultFilter.ALL
    private var normalizedQuery = ""

    private var hasLive = false
    private var hasMovies = false
    private var hasSeries = false
    private var liveSettled = true
    private var movieSettled = true
    private var seriesSettled = true
    private var liveFailed = false
    private var movieFailed = false
    private var seriesFailed = false
    private var submittedMovieQuery = ""
    private var submittedSeriesQuery = ""
    private var submittedLiveGeneration: SearchViewModel.SearchGeneration? = null
    private var submittedMovieGeneration: SearchViewModel.SearchGeneration? = null
    private var submittedSeriesGeneration: SearchViewModel.SearchGeneration? = null
    private var presentedLiveGeneration: SearchViewModel.SearchGeneration? = null
    private var presentedMovieGeneration: SearchViewModel.SearchGeneration? = null
    private var presentedSeriesGeneration: SearchViewModel.SearchGeneration? = null
    // Fail closed until DataStore confirms whether parental masking is disabled.
    private var adultLocked = true

    private var restoredFocusRegion: String? = null
    private var lastKnownFocusRegion = FOCUS_INPUT
    private var initialFocusApplied = false
    private var restoreResultOnResume = false
    private var pendingResultRestore = false
    private var navigationInFlight = false

    private var lastLiveId: String? = null
    private var lastLivePosition = 0
    private var lastMovieId: String? = null
    private var lastMoviePosition = 0
    private var lastSeriesId: String? = null
    private var lastSeriesPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeFilter = savedInstanceState
            ?.getString(STATE_FILTER)
            ?.let { runCatching { ResultFilter.valueOf(it) }.getOrNull() }
            ?: ResultFilter.ALL
        restoredFocusRegion = savedInstanceState?.getString(STATE_FOCUS_REGION)
        lastKnownFocusRegion = restoredFocusRegion ?: FOCUS_INPUT
        lastLiveId = savedInstanceState?.getString(STATE_LIVE_ID)
        lastLivePosition = savedInstanceState?.getInt(STATE_LIVE_POSITION, 0) ?: 0
        lastMovieId = savedInstanceState?.getString(STATE_MOVIE_ID)
        lastMoviePosition = savedInstanceState?.getInt(STATE_MOVIE_POSITION, 0) ?: 0
        lastSeriesId = savedInstanceState?.getString(STATE_SERIES_ID)
        lastSeriesPosition = savedInstanceState?.getInt(STATE_SERIES_POSITION, 0) ?: 0

        setupAccessibility()
        setupLists()
        setupSearch()
        setupFilters()
        setupActions()
        observe()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (normalizedQuery.isNotEmpty()) {
                    clearSearch(focusInput = false)
                    binding.searchInput.hideSoftKeyboard()
                    selectedFilterView().requestFocus()
                } else {
                    finish()
                }
            }
        })

        renderSearchState()
        binding.root.post { maybeApplyInitialFocus() }
    }

    override fun onResume() {
        super.onResume()
        navigationInFlight = false
        binding.dateText.text = DateFormat
            .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date())
        if (restoreResultOnResume) {
            restoreResultOnResume = false
            pendingResultRestore = true
            binding.root.post { maybeRestoreFocus() }
        }
    }

    override fun onStop() {
        // Cached RecyclerView children can be drawn immediately on a later
        // resume. Mask them while off-screen, then unlock only after the fresh
        // parental setting is read in the STARTED collector.
        applyAdultMask(true)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FILTER, activeFilter.name)
        outState.putString(STATE_FOCUS_REGION, focusedRegion())
        outState.putString(STATE_LIVE_ID, lastLiveId)
        outState.putInt(STATE_LIVE_POSITION, lastLivePosition)
        outState.putString(STATE_MOVIE_ID, lastMovieId)
        outState.putInt(STATE_MOVIE_POSITION, lastMoviePosition)
        outState.putString(STATE_SERIES_ID, lastSeriesId)
        outState.putInt(STATE_SERIES_POSITION, lastSeriesPosition)
        super.onSaveInstanceState(outState)
    }

    private fun setupAccessibility() {
        ViewCompat.setAccessibilityHeading(binding.searchTitle, true)
        ViewCompat.setAccessibilityHeading(binding.liveHeading, true)
        ViewCompat.setAccessibilityHeading(binding.movieHeading, true)
        ViewCompat.setAccessibilityHeading(binding.seriesHeading, true)
        ViewCompat.setAccessibilityPaneTitle(
            binding.searchBody,
            getString(R.string.search_results_title),
        )
        ViewCompat.setAccessibilityPaneTitle(
            binding.searchFilters,
            getString(R.string.search_filters),
        )
    }

    private fun setupLists() {
        liveAdapter = SearchChannelAdapter(
            onFocused = { channel, position ->
                lastKnownFocusRegion = FOCUS_LIVE
                lastLiveId = channel.id
                lastLivePosition = position
            },
            onClicked = { channel ->
                guardResultNavigation(isAdult = channel.isAdult()) {
                    openLive(channel)
                }
            },
        )
        liveAdapter.adultLocked = true
        binding.liveList.layoutManager = LinearLayoutManager(this)
        binding.liveList.adapter = liveAdapter
        binding.liveList.setItemViewCacheSize(8)

        movieAdapter = VodAdapter(
            onFocused = { },
            onClicked = { item ->
                guardResultNavigation(isAdult = item.isAdult()) {
                    openMovie(item.id)
                }
            },
        )
        movieAdapter.adultLocked = true
        movieAdapter.onFocusedAt = { item, position ->
            lastKnownFocusRegion = FOCUS_MOVIES
            lastMovieId = item.id
            lastMoviePosition = position
        }
        binding.movieGrid.layoutManager = SafeGridLayoutManager(this, 2)
        binding.movieGrid.adapter = movieAdapter
        binding.movieGrid.setItemViewCacheSize(8)

        seriesAdapter = SeriesAdapter(
            onFocused = { item ->
                val position =
                    seriesAdapter.snapshot().items.indexOfFirst { it.id == item.id }
                        .takeIf { it >= 0 } ?: lastSeriesPosition
                lastKnownFocusRegion = FOCUS_SERIES
                lastSeriesId = item.id
                lastSeriesPosition = position
            },
            onClicked = { item ->
                guardResultNavigation(isAdult = item.isAdult()) {
                    openSeries(item.id)
                }
            },
        )
        seriesAdapter.adultLocked = true
        seriesAdapter.stateRestorationPolicy =
            RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.seriesGrid.layoutManager = SafeGridLayoutManager(this, 2)
        binding.seriesGrid.adapter = seriesAdapter
        binding.seriesGrid.setItemViewCacheSize(8)

        listOf(binding.liveList, binding.movieGrid, binding.seriesGrid).forEach { list ->
            (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        resizePosterGrids()
    }

    private fun setupSearch() {
        normalizedQuery = viewModel.query.value
        if (normalizedQuery.isNotEmpty()) {
            binding.searchInput.setText(normalizedQuery)
            binding.searchInput.setSelection(normalizedQuery.length)
        }
        binding.searchClearButton.visibility =
            if (normalizedQuery.isEmpty()) View.INVISIBLE else View.VISIBLE
        resetResultState(resetAnchors = false)

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                value: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                value: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) {
                val next = value?.toString().orEmpty().trim()
                if (next == normalizedQuery) return
                normalizedQuery = next
                resetResultState(resetAnchors = true)
                viewModel.setQuery(value?.toString().orEmpty())
                binding.searchClearButton.visibility =
                    if (next.isEmpty()) View.INVISIBLE else View.VISIBLE
                renderSearchState()
            }

            override fun afterTextChanged(value: Editable?) = Unit
        })

        binding.searchInput.setOnFocusChangeListener { _, focused ->
            if (focused) {
                lastKnownFocusRegion = FOCUS_INPUT
                showKeyboard()
            } else {
                binding.searchInput.hideSoftKeyboard()
            }
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            val submitted =
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (
                        event?.action == KeyEvent.ACTION_DOWN &&
                            event.keyCode == KeyEvent.KEYCODE_ENTER
                        )
            if (submitted) {
                binding.searchInput.hideSoftKeyboard()
                focusFirstVisibleResultOrFilter()
            }
            submitted
        }
    }

    private fun setupFilters() {
        val filters = mapOf(
            ResultFilter.ALL to binding.filterAll,
            ResultFilter.LIVE to binding.filterLive,
            ResultFilter.MOVIES to binding.filterMovies,
            ResultFilter.SERIES to binding.filterSeries,
        )
        filters.forEach { (filter, view) ->
            view.setOnClickListener { selectFilter(filter) }
            view.setOnFocusChangeListener { _, focused ->
                if (focused) lastKnownFocusRegion = filterFocusRegion(filter)
            }
        }
        updateFilterSelection()
    }

    private fun setupActions() {
        binding.searchClearButton.setOnClickListener {
            clearSearch(focusInput = true)
        }
        binding.searchClearButton.setOnFocusChangeListener { _, focused ->
            if (focused) lastKnownFocusRegion = FOCUS_CLEAR
        }
        binding.catalogRetryButton.setOnClickListener {
            selectedFilterView().requestFocus()
            retryFailedSearches()
        }
        binding.catalogRetryButton.setOnFocusChangeListener { _, focused ->
            if (focused) lastKnownFocusRegion = FOCUS_CATALOG_RETRY
        }
        binding.resultRetryButton.setOnClickListener {
            selectedFilterView().requestFocus()
            retryFailedSearches()
        }
        binding.resultRetryButton.setOnFocusChangeListener { _, focused ->
            if (focused) lastKnownFocusRegion = FOCUS_RESULT_RETRY
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Adult masking is applied before any result collector starts so
                // cached metadata cannot flash for one frame on screen entry.
                val locked = ServiceLocator.settings.lockAdult.first() &&
                    ServiceLocator.settings.hasPin()
                applyAdultMask(locked)

                launch {
                    viewModel.liveResults.collectLatest { result ->
                        if (
                            result.query != normalizedQuery ||
                            result.generation.requestGeneration !=
                            viewModel.currentRequestGeneration
                        ) {
                            return@collectLatest
                        }
                        submittedLiveGeneration = result.generation
                        liveFailed = result.failed
                        if (submittedLiveGeneration != presentedLiveGeneration) {
                            liveSettled = false
                            hasLive = false
                            binding.liveCount.text = "0"
                            updateHeadingCount(binding.liveHeading, R.string.nav_live, 0)
                            renderSearchState()
                        }
                        liveAdapter.submitList(result.items) {
                            if (
                                result.query != normalizedQuery ||
                                result.generation.requestGeneration !=
                                viewModel.currentRequestGeneration
                            ) {
                                return@submitList
                            }
                            presentedLiveGeneration = result.generation
                            hasLive = result.items.isNotEmpty()
                            liveSettled = true
                            binding.liveCount.text = result.items.size.toString()
                            updateHeadingCount(
                                binding.liveHeading,
                                R.string.nav_live,
                                result.items.size,
                            )
                            renderSearchState()
                            maybeRestoreFocus()
                        }
                    }
                }
                launch {
                    viewModel.movies.collectLatest { result ->
                        if (
                            result.generation.requestGeneration !=
                            viewModel.currentRequestGeneration
                        ) {
                            return@collectLatest
                        }
                        val replacesPresentedGeneration =
                            presentedMovieGeneration != result.generation
                        submittedMovieQuery = result.query
                        submittedMovieGeneration = result.generation
                        if (result.query == normalizedQuery) {
                            movieSettled = result.query.isEmpty()
                            movieFailed = false
                            if (replacesPresentedGeneration) hasMovies = false
                            renderSearchState()
                        }
                        movieAdapter.submitData(result.data)
                    }
                }
                launch {
                    viewModel.series.collectLatest { result ->
                        if (
                            result.generation.requestGeneration !=
                            viewModel.currentRequestGeneration
                        ) {
                            return@collectLatest
                        }
                        val replacesPresentedGeneration =
                            presentedSeriesGeneration != result.generation
                        submittedSeriesQuery = result.query
                        submittedSeriesGeneration = result.generation
                        if (result.query == normalizedQuery) {
                            seriesSettled = result.query.isEmpty()
                            seriesFailed = false
                            if (replacesPresentedGeneration) hasSeries = false
                            renderSearchState()
                        }
                        seriesAdapter.submitData(result.data)
                    }
                }
                launch {
                    movieAdapter.loadStateFlow.collectLatest { states ->
                        if (submittedMovieQuery != normalizedQuery) return@collectLatest
                        updateMovieLoadState(states)
                    }
                }
                launch {
                    seriesAdapter.loadStateFlow.collectLatest { states ->
                        if (submittedSeriesQuery != normalizedQuery) return@collectLatest
                        updateSeriesLoadState(states)
                    }
                }
                launch {
                    viewModel.effectiveQuery.collectLatest {
                        renderSearchState()
                    }
                }
                launch {
                    viewModel.catalogState.collectLatest {
                        renderCatalogStatus()
                        renderSearchState()
                    }
                }
            }
        }
    }

    private fun updateMovieLoadState(states: CombinedLoadStates) {
        movieFailed = states.refresh is LoadState.Error ||
            states.append is LoadState.Error ||
            states.prepend is LoadState.Error
        movieSettled = states.refresh !is LoadState.Loading
        when (states.refresh) {
            is LoadState.NotLoading -> {
                presentedMovieGeneration = submittedMovieGeneration
                hasMovies = movieAdapter.itemCount > 0
            }
            is LoadState.Error -> {
                // Paging intentionally keeps the previous generation visible
                // when a new refresh fails. Never label those old posters as
                // matches for the newly submitted query.
                hasMovies =
                    presentedMovieGeneration == submittedMovieGeneration &&
                    movieAdapter.itemCount > 0
            }
            is LoadState.Loading -> Unit
        }
        val visibleCount =
            if (
                presentedMovieGeneration == submittedMovieGeneration
            ) movieAdapter.itemCount else 0
        binding.movieCount.text = visibleCount.toString()
        updateHeadingCount(
            binding.movieHeading,
            R.string.nav_movies,
            visibleCount,
        )
        renderCatalogStatus()
        renderSearchState()
        maybeRestoreFocus()
    }

    private fun updateSeriesLoadState(states: CombinedLoadStates) {
        seriesFailed = states.refresh is LoadState.Error ||
            states.append is LoadState.Error ||
            states.prepend is LoadState.Error
        seriesSettled = states.refresh !is LoadState.Loading
        when (states.refresh) {
            is LoadState.NotLoading -> {
                presentedSeriesGeneration = submittedSeriesGeneration
                hasSeries = seriesAdapter.itemCount > 0
            }
            is LoadState.Error -> {
                hasSeries =
                    presentedSeriesGeneration == submittedSeriesGeneration &&
                    seriesAdapter.itemCount > 0
            }
            is LoadState.Loading -> Unit
        }
        val visibleCount =
            if (
                presentedSeriesGeneration == submittedSeriesGeneration
            ) seriesAdapter.itemCount else 0
        binding.seriesCount.text = visibleCount.toString()
        updateHeadingCount(
            binding.seriesHeading,
            R.string.nav_series,
            visibleCount,
        )
        renderCatalogStatus()
        renderSearchState()
        maybeRestoreFocus()
    }

    private fun resetResultState(resetAnchors: Boolean) {
        hasLive = false
        hasMovies = false
        hasSeries = false
        liveFailed = false
        movieFailed = false
        seriesFailed = false
        submittedMovieQuery = NO_QUERY_GENERATION
        submittedSeriesQuery = NO_QUERY_GENERATION
        submittedLiveGeneration = null
        submittedMovieGeneration = null
        submittedSeriesGeneration = null
        val empty = normalizedQuery.isEmpty()
        liveSettled = empty
        movieSettled = empty
        seriesSettled = empty
        binding.liveCount.text = "0"
        binding.movieCount.text = "0"
        binding.seriesCount.text = "0"
        updateHeadingCount(binding.liveHeading, R.string.nav_live, 0)
        updateHeadingCount(binding.movieHeading, R.string.nav_movies, 0)
        updateHeadingCount(binding.seriesHeading, R.string.nav_series, 0)
        if (resetAnchors) resetResultAnchors()
    }

    private fun retryFailedSearches() {
        if (liveFailed) {
            liveFailed = false
            liveSettled = false
            viewModel.retryLiveSearch()
        }
        if (movieFailed) {
            movieFailed = false
            movieSettled = false
            movieAdapter.retry()
        }
        if (seriesFailed) {
            seriesFailed = false
            seriesSettled = false
            seriesAdapter.retry()
        }
        if (isCatalogRelevant() && viewModel.catalogState.value.errorRes != null) {
            viewModel.retryCatalog()
        }
        renderCatalogStatus()
        renderSearchState()
    }

    private fun renderCatalogStatus() {
        val catalog = viewModel.catalogState.value
        val resultFailed = relevantResultFailed()
        val catalogRelevant = isCatalogRelevant()
        val catalogLoading = catalogRelevant && catalog.loading
        val errorRes = catalog.errorRes.takeIf { catalogRelevant }
        val resultPending = normalizedQuery.isNotEmpty() && relevantResultPending()
        // The centered loading/error card owns cold states. This strip is only
        // for partial results, avoiding two Retry buttons in the focus graph.
        val visible = relevantHasResults() &&
            (catalogLoading || errorRes != null || resultFailed || resultPending)
        binding.catalogStatusRow.visibility = if (visible) View.VISIBLE else View.GONE
        binding.catalogProgress.visibility =
            if (
                (catalogLoading || resultPending) &&
                errorRes == null &&
                !resultFailed
            ) View.VISIBLE
            else View.GONE
        binding.catalogRetryButton.visibility =
            if (errorRes != null || resultFailed) View.VISIBLE else View.GONE
        binding.catalogStatusText.text = when {
            errorRes != null -> getString(errorRes)
            resultFailed -> getString(R.string.search_results_error)
            catalogLoading && catalog.total > 0 -> getString(
                R.string.catalog_loading_progress,
                catalog.completed,
                catalog.total,
            )
            catalogLoading -> getString(R.string.search_catalog_preparing)
            resultPending -> getString(R.string.search_loading)
            else -> ""
        }
    }

    private fun renderSearchState() {
        val hasQuery = normalizedQuery.isNotEmpty()
        val hasResults = relevantHasResults()
        val pending = hasQuery && relevantSearchPending()
        val failed = hasQuery && relevantSearchFailed()

        binding.searchIdleState.visibility = if (!hasQuery) View.VISIBLE else View.GONE
        binding.resultsRow.visibility =
            if (hasQuery && hasResults) View.VISIBLE else View.GONE
        binding.searchLoadingState.visibility =
            if (hasQuery && !hasResults && pending) View.VISIBLE else View.GONE
        binding.searchErrorState.visibility =
            if (hasQuery && !hasResults && !pending && failed) View.VISIBLE else View.GONE
        binding.emptyState.visibility =
            if (hasQuery && !hasResults && !pending && !failed) View.VISIBLE else View.GONE

        updatePanelVisibility(pending)
        renderCatalogStatus()
    }

    private fun updatePanelVisibility(pending: Boolean) {
        val all = activeFilter == ResultFilter.ALL
        binding.livePanel.visibility = if (
            activeFilter == ResultFilter.LIVE || (all && (pending || hasLive))
        ) View.VISIBLE else View.GONE
        binding.moviePanel.visibility = if (
            activeFilter == ResultFilter.MOVIES || (all && (pending || hasMovies))
        ) View.VISIBLE else View.GONE
        binding.seriesPanel.visibility = if (
            activeFilter == ResultFilter.SERIES || (all && (pending || hasSeries))
        ) View.VISIBLE else View.GONE
        binding.liveList.visibility =
            if (
                submittedLiveGeneration != null &&
                submittedLiveGeneration == presentedLiveGeneration
            ) View.VISIBLE else View.INVISIBLE
        binding.movieGrid.visibility =
            if (
                submittedMovieGeneration != null &&
                submittedMovieGeneration == presentedMovieGeneration
            ) View.VISIBLE else View.INVISIBLE
        binding.seriesGrid.visibility =
            if (
                submittedSeriesGeneration != null &&
                submittedSeriesGeneration == presentedSeriesGeneration
            ) View.VISIBLE else View.INVISIBLE
        resizePosterGrids()
    }

    private fun resizePosterGrids() {
        binding.movieGrid.autoFitColumns(min = 2)
        binding.seriesGrid.autoFitColumns(min = 2)
    }

    private fun relevantHasResults(): Boolean = when (activeFilter) {
        ResultFilter.ALL -> hasLive || hasMovies || hasSeries
        ResultFilter.LIVE -> hasLive
        ResultFilter.MOVIES -> hasMovies
        ResultFilter.SERIES -> hasSeries
    }

    private fun relevantSearchPending(): Boolean {
        if (viewModel.effectiveQuery.value != normalizedQuery) return true
        if (isCatalogRelevant() && viewModel.catalogState.value.loading) return true
        return relevantResultPending()
    }

    private fun relevantResultPending(): Boolean = when (activeFilter) {
        ResultFilter.ALL -> !liveSettled || !movieSettled || !seriesSettled
        ResultFilter.LIVE -> !liveSettled
        ResultFilter.MOVIES -> !movieSettled
        ResultFilter.SERIES -> !seriesSettled
    }

    private fun relevantResultFailed(): Boolean = when (activeFilter) {
        ResultFilter.ALL -> liveFailed || movieFailed || seriesFailed
        ResultFilter.LIVE -> liveFailed
        ResultFilter.MOVIES -> movieFailed
        ResultFilter.SERIES -> seriesFailed
    }

    private fun relevantSearchFailed(): Boolean {
        val catalogFailed =
            isCatalogRelevant() && viewModel.catalogState.value.errorRes != null
        return catalogFailed || relevantResultFailed()
    }

    private fun isCatalogRelevant(): Boolean = activeFilter != ResultFilter.LIVE

    private fun selectFilter(filter: ResultFilter) {
        if (activeFilter == filter) return
        activeFilter = filter
        updateFilterSelection()
        renderSearchState()
        maybeRestoreFocus()
    }

    private fun updateFilterSelection() {
        binding.filterAll.isSelected = activeFilter == ResultFilter.ALL
        binding.filterLive.isSelected = activeFilter == ResultFilter.LIVE
        binding.filterMovies.isSelected = activeFilter == ResultFilter.MOVIES
        binding.filterSeries.isSelected = activeFilter == ResultFilter.SERIES
    }

    private fun updateHeadingCount(
        heading: TextView,
        titleRes: Int,
        count: Int,
    ) {
        heading.contentDescription = "${getString(titleRes)}, $count"
    }

    private fun selectedFilterView(): View = when (activeFilter) {
        ResultFilter.ALL -> binding.filterAll
        ResultFilter.LIVE -> binding.filterLive
        ResultFilter.MOVIES -> binding.filterMovies
        ResultFilter.SERIES -> binding.filterSeries
    }

    private fun filterFocusRegion(filter: ResultFilter): String = when (filter) {
        ResultFilter.ALL -> FOCUS_FILTER_ALL
        ResultFilter.LIVE -> FOCUS_FILTER_LIVE
        ResultFilter.MOVIES -> FOCUS_FILTER_MOVIES
        ResultFilter.SERIES -> FOCUS_FILTER_SERIES
    }

    /** Applies parental masking without invalidating Paging adapters or TV focus. */
    private fun applyAdultMask(locked: Boolean) {
        if (
            adultLocked == locked &&
            liveAdapter.adultLocked == locked &&
            movieAdapter.adultLocked == locked &&
            seriesAdapter.adultLocked == locked
        ) {
            return
        }
        adultLocked = locked
        liveAdapter.adultLocked = locked
        movieAdapter.adultLocked = locked
        seriesAdapter.adultLocked = locked
        liveAdapter.refreshVisible(binding.liveList)
        movieAdapter.refreshVisible(binding.movieGrid)
        seriesAdapter.refreshVisible(binding.seriesGrid)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val rtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val towardEnd =
            if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val towardStart =
            if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT

        when {
            binding.searchInput.hasFocus() -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.searchInput.hideSoftKeyboard()
                        selectedFilterView().requestFocus()
                        return true
                    }
                    towardEnd -> {
                        val atEnd = binding.searchInput.selectionStart >=
                            (binding.searchInput.text?.length ?: 0)
                        if (
                            atEnd &&
                            binding.searchClearButton.visibility == View.VISIBLE
                        ) {
                            binding.searchInput.hideSoftKeyboard()
                            binding.searchClearButton.requestFocus()
                            return true
                        }
                    }
                }
            }
            binding.searchClearButton.hasFocus() -> {
                when (event.keyCode) {
                    towardStart -> {
                        focusSearchInput()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        selectedFilterView().requestFocus()
                        return true
                    }
                }
            }
            filterRowHasFocus() -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        focusSearchInput()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (binding.catalogRetryButton.visibility == View.VISIBLE) {
                            binding.catalogRetryButton.requestFocus()
                        } else {
                            focusFirstVisibleResultOrFilter()
                        }
                        return true
                    }
                }
            }
            binding.liveList.hasFocus() -> {
                val position = focusedAdapterPosition(binding.liveList)
                when {
                    event.keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0 -> {
                        focusStatusOrFilter(ResultFilter.LIVE)
                        return true
                    }
                    activeFilter != ResultFilter.ALL &&
                        (event.keyCode == towardStart || event.keyCode == towardEnd) -> {
                        return true
                    }
                    event.keyCode == towardStart -> return true
                    event.keyCode == towardEnd -> {
                        focusMovieOrSeries()
                        return true
                    }
                }
            }
            binding.movieGrid.hasFocus() -> {
                val position = focusedAdapterPosition(binding.movieGrid)
                val span = gridSpan(binding.movieGrid)
                when {
                    event.keyCode == KeyEvent.KEYCODE_DPAD_UP && position in 0 until span -> {
                        focusStatusOrFilter(ResultFilter.MOVIES)
                        return true
                    }
                    event.keyCode == towardStart && position % span == 0 -> {
                        if (activeFilter == ResultFilter.ALL && hasLive) {
                            focusLiveResult()
                        }
                        return true
                    }
                    event.keyCode == towardEnd &&
                        (position % span == span - 1 ||
                            position == movieAdapter.itemCount - 1) -> {
                        if (activeFilter == ResultFilter.ALL && hasSeries) {
                            focusSeriesResult()
                        }
                        return true
                    }
                }
            }
            binding.seriesGrid.hasFocus() -> {
                val position = focusedAdapterPosition(binding.seriesGrid)
                val span = gridSpan(binding.seriesGrid)
                when {
                    event.keyCode == KeyEvent.KEYCODE_DPAD_UP && position in 0 until span -> {
                        focusStatusOrFilter(ResultFilter.SERIES)
                        return true
                    }
                    event.keyCode == towardStart && position % span == 0 -> {
                        if (activeFilter == ResultFilter.ALL) {
                            when {
                                hasMovies -> focusMovieResult()
                                hasLive -> focusLiveResult()
                            }
                        }
                        return true
                    }
                    event.keyCode == towardEnd &&
                        (position % span == span - 1 ||
                            position == seriesAdapter.itemCount - 1) -> {
                        return true
                    }
                }
            }
            binding.resultRetryButton.hasFocus() &&
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                selectedFilterView().requestFocus()
                return true
            }
            binding.catalogRetryButton.hasFocus() -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        selectedFilterView().requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusFirstVisibleResultOrFilter()
                        return true
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun filterRowHasFocus(): Boolean {
        return binding.filterAll.hasFocus() ||
            binding.filterLive.hasFocus() ||
            binding.filterMovies.hasFocus() ||
            binding.filterSeries.hasFocus()
    }

    private fun filterForResult(type: ResultFilter): View {
        return if (activeFilter == ResultFilter.ALL) {
            when (type) {
                ResultFilter.LIVE -> binding.filterLive
                ResultFilter.MOVIES -> binding.filterMovies
                ResultFilter.SERIES -> binding.filterSeries
                ResultFilter.ALL -> binding.filterAll
            }
        } else {
            selectedFilterView()
        }
    }

    private fun focusStatusOrFilter(type: ResultFilter) {
        if (binding.catalogRetryButton.visibility == View.VISIBLE) {
            binding.catalogRetryButton.requestFocus()
        } else {
            filterForResult(type).requestFocus()
        }
    }

    private fun focusFirstVisibleResultOrFilter() {
        binding.searchInput.hideSoftKeyboard()
        when (activeFilter) {
            ResultFilter.ALL -> when {
                hasLive -> focusLiveResult()
                hasMovies -> focusMovieResult()
                hasSeries -> focusSeriesResult()
                binding.resultRetryButton.visibility == View.VISIBLE ->
                    binding.resultRetryButton.requestFocus()
                binding.catalogRetryButton.visibility == View.VISIBLE ->
                    binding.catalogRetryButton.requestFocus()
                else -> binding.filterAll.requestFocus()
            }
            ResultFilter.LIVE -> if (hasLive) {
                focusLiveResult()
            } else {
                focusRetryOrSelectedFilter()
            }
            ResultFilter.MOVIES -> if (hasMovies) {
                focusMovieResult()
            } else {
                focusRetryOrSelectedFilter()
            }
            ResultFilter.SERIES -> if (hasSeries) {
                focusSeriesResult()
            } else {
                focusRetryOrSelectedFilter()
            }
        }
    }

    private fun focusRetryOrSelectedFilter() {
        when {
            binding.resultRetryButton.visibility == View.VISIBLE ->
                binding.resultRetryButton.requestFocus()
            binding.catalogRetryButton.visibility == View.VISIBLE ->
                binding.catalogRetryButton.requestFocus()
            else -> selectedFilterView().requestFocus()
        }
    }

    private fun focusMovieOrSeries() {
        when {
            hasMovies -> focusMovieResult()
            hasSeries -> focusSeriesResult()
        }
    }

    private fun focusLiveResult(): Boolean {
        if (
            !hasLive ||
            liveAdapter.itemCount == 0 ||
            binding.livePanel.visibility != View.VISIBLE ||
            binding.liveList.visibility != View.VISIBLE
        ) {
            return false
        }
        val exact = lastLiveId?.let { id ->
            liveAdapter.currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }
        binding.liveList.requestFocusAt(exact ?: lastLivePosition)
        return true
    }

    private fun focusMovieResult(): Boolean {
        if (
            !hasMovies ||
            movieAdapter.itemCount == 0 ||
            binding.moviePanel.visibility != View.VISIBLE ||
            binding.movieGrid.visibility != View.VISIBLE
        ) {
            return false
        }
        val exact = lastMovieId?.let { id ->
            movieAdapter.snapshot().items.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }
        binding.movieGrid.requestFocusAt(exact ?: lastMoviePosition)
        return true
    }

    private fun focusSeriesResult(): Boolean {
        if (
            !hasSeries ||
            seriesAdapter.itemCount == 0 ||
            binding.seriesPanel.visibility != View.VISIBLE ||
            binding.seriesGrid.visibility != View.VISIBLE
        ) {
            return false
        }
        val exact = lastSeriesId?.let { id ->
            seriesAdapter.snapshot().items.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }
        binding.seriesGrid.requestFocusAt(exact ?: lastSeriesPosition)
        return true
    }

    private fun maybeApplyInitialFocus() {
        if (initialFocusApplied) return
        when (restoredFocusRegion) {
            FOCUS_LIVE -> {
                if (!liveSettled) return
                if (!focusLiveResult()) focusRetryOrSelectedFilter()
            }
            FOCUS_MOVIES -> {
                if (!movieSettled) return
                if (!focusMovieResult()) focusRetryOrSelectedFilter()
            }
            FOCUS_SERIES -> {
                if (!seriesSettled) return
                if (!focusSeriesResult()) focusRetryOrSelectedFilter()
            }
            FOCUS_CLEAR -> if (binding.searchClearButton.visibility == View.VISIBLE) {
                binding.searchClearButton.requestFocus()
            } else {
                focusSearchInput()
            }
            FOCUS_FILTER_ALL -> binding.filterAll.requestFocus()
            FOCUS_FILTER_LIVE -> binding.filterLive.requestFocus()
            FOCUS_FILTER_MOVIES -> binding.filterMovies.requestFocus()
            FOCUS_FILTER_SERIES -> binding.filterSeries.requestFocus()
            FOCUS_CATALOG_RETRY -> if (
                binding.catalogRetryButton.visibility == View.VISIBLE
            ) {
                binding.catalogRetryButton.requestFocus()
            } else {
                selectedFilterView().requestFocus()
            }
            FOCUS_RESULT_RETRY -> if (
                binding.resultRetryButton.visibility == View.VISIBLE
            ) {
                binding.resultRetryButton.requestFocus()
            } else {
                selectedFilterView().requestFocus()
            }
            else -> focusSearchInput()
        }
        initialFocusApplied = true
        restoredFocusRegion = null
    }

    private fun maybeRestoreFocus() {
        if (!initialFocusApplied) {
            maybeApplyInitialFocus()
        }
        if (!initialFocusApplied || !pendingResultRestore) return

        val restored = restoreLastResultFocus()
        if (restored) {
            pendingResultRestore = false
            return
        }
        if (lastResultRegionSettled()) {
            pendingResultRestore = false
            focusFirstVisibleResultOrFilter()
        }
    }

    private fun restoreLastResultFocus(): Boolean {
        return when (lastKnownFocusRegion) {
            FOCUS_LIVE -> focusLiveResult()
            FOCUS_MOVIES -> focusMovieResult()
            FOCUS_SERIES -> focusSeriesResult()
            else -> false
        }
    }

    private fun lastResultRegionSettled(): Boolean = when (lastKnownFocusRegion) {
        FOCUS_LIVE -> liveSettled
        FOCUS_MOVIES -> movieSettled
        FOCUS_SERIES -> seriesSettled
        else -> true
    }

    private fun focusedRegion(): String = when {
        binding.searchInput.hasFocus() -> FOCUS_INPUT
        binding.searchClearButton.hasFocus() -> FOCUS_CLEAR
        binding.filterAll.hasFocus() -> FOCUS_FILTER_ALL
        binding.filterLive.hasFocus() -> FOCUS_FILTER_LIVE
        binding.filterMovies.hasFocus() -> FOCUS_FILTER_MOVIES
        binding.filterSeries.hasFocus() -> FOCUS_FILTER_SERIES
        binding.liveList.hasFocus() -> FOCUS_LIVE
        binding.movieGrid.hasFocus() -> FOCUS_MOVIES
        binding.seriesGrid.hasFocus() -> FOCUS_SERIES
        binding.catalogRetryButton.hasFocus() -> FOCUS_CATALOG_RETRY
        binding.resultRetryButton.hasFocus() -> FOCUS_RESULT_RETRY
        else -> restoredFocusRegion ?: lastKnownFocusRegion
    }

    private fun focusedAdapterPosition(recyclerView: RecyclerView): Int {
        val focused = currentFocus ?: return RecyclerView.NO_POSITION
        return recyclerView.findContainingViewHolder(focused)?.bindingAdapterPosition
            ?: RecyclerView.NO_POSITION
    }

    private fun gridSpan(recyclerView: RecyclerView): Int {
        return (recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 1
    }

    private fun focusSearchInput() {
        binding.searchInput.requestFocus()
        binding.searchInput.setSelection(binding.searchInput.text?.length ?: 0)
        showKeyboard()
    }

    private fun showKeyboard() {
        binding.searchInput.post {
            if (!binding.searchInput.hasFocus()) return@post
            val input = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            input?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun clearSearch(focusInput: Boolean) {
        binding.searchInput.text?.clear()
        if (focusInput) focusSearchInput()
    }

    private fun resetResultAnchors() {
        lastLiveId = null
        lastLivePosition = 0
        lastMovieId = null
        lastMoviePosition = 0
        lastSeriesId = null
        lastSeriesPosition = 0
    }

    /**
     * Key-repeat on TV remotes can deliver multiple OK events before the
     * asynchronous parental check completes. Own one request/navigation at a
     * time so it cannot stack PIN dialogs or detail/player Activities.
     */
    private fun guardResultNavigation(
        isAdult: Boolean,
        onAllowed: () -> Unit,
    ) {
        if (navigationInFlight) return
        navigationInFlight = true
        PinLockHelper.guard(
            activity = this,
            isAdult = isAdult,
            onDenied = { navigationInFlight = false },
            onAllowed = {
                if (navigationInFlight) onAllowed()
            },
        )
    }

    private fun openLive(channel: Channel) {
        lastLiveId = channel.id
        restoreResultOnResume = true
        startActivitySafely(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(PlayerActivity.EXTRA_CATEGORY_ID, channel.categoryId)
                .putExtra(PlayerActivity.EXTRA_PARENTAL_AUTHORIZED, true),
        )
    }

    private fun openMovie(id: String) {
        lastMovieId = id
        restoreResultOnResume = true
        startActivitySafely(
            Intent(this, VodDetailActivity::class.java)
                .putExtra(VodDetailActivity.EXTRA_VOD_ID, id),
        )
    }

    private fun openSeries(id: String) {
        lastSeriesId = id
        restoreResultOnResume = true
        startActivitySafely(
            Intent(this, SeriesDetailActivity::class.java)
                .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, id),
        )
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            navigationInFlight = false
            restoreResultOnResume = false
            Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show()
        }
    }
}
