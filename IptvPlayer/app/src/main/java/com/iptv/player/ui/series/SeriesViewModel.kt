/*
 * SeriesViewModel.kt
 * Drives the Series screen: categories (left) + poster grid (center). Mirrors
 * VodViewModel; the grid follows either the selected category or the query.
 */
package com.iptv.player.ui.series

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.ContentSort
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.Series
import com.iptv.player.data.repository.CategorySync
import com.iptv.player.ui.common.CatalogLoadState
import com.iptv.player.ui.common.CatalogRetry
import com.iptv.player.ui.common.CatalogRetryPolicy
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SeriesViewModel(
    app: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app) {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    companion object {
        const val CAT_ALL = "__all__"
        const val CAT_POPULAR = "__popular__"
        private const val STATE_CATEGORY = "series_vm_category"
        private const val STATE_QUERY = "series_vm_query"
    }

    private val _loadState = MutableStateFlow(CatalogLoadState())
    val loadState: StateFlow<CatalogLoadState> = _loadState
    private val _episodeDatesUpdating = MutableStateFlow(false)
    val episodeDatesUpdating: StateFlow<Boolean> = _episodeDatesUpdating

    /**
     * Categories with a "Recently added" entry pinned to the top, each with a
     * count. Hidden categories are filtered out and the user's custom order
     * applied (see the Content Manager).
     */
    val categories: StateFlow<List<Category>> =
        repo.observeVisibleCategories(ContentType.SERIES).map { cats ->
            buildList {
                add(
                    Category(
                        CAT_ALL,
                        getApplication<Application>().getString(R.string.cat_recently_added),
                        ContentType.SERIES,
                        count = cats.sumOf { it.count ?: 0 }.coerceAtMost(50)
                    )
                )
                // Profile preferences rank playable items from the local catalog.
                add(
                    Category(
                        CAT_POPULAR,
                        getApplication<Application>().getString(R.string.cat_for_you),
                        ContentType.SERIES,
                        count = cats.sumOf { it.count ?: 0 }.coerceAtMost(50)
                    )
                )
                addAll(cats)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedCategory =
        savedStateHandle.getStateFlow<String?>(STATE_CATEGORY, null)
    val query: StateFlow<String> =
        savedStateHandle.getStateFlow(STATE_QUERY, "")

    /** The currently selected category id (survives config changes). */
    val selectedCategoryId: String? get() = selectedCategory.value

    /** Current grid ordering; cycled from the UI. Restored from settings. */
    private val _sort = MutableStateFlow(ContentSort.RECENT)
    val sort: StateFlow<ContentSort> = _sort

    private var selectionLoadJob: Job? = null
    private var catalogLoadJob: Job? = null
    private var episodeDateJob: Job? = null
    private var browseActive = false

    fun onBrowseStarted() {
        browseActive = true
        scheduleSelectedCategoryLoad()
    }

    fun onBrowseStopped() {
        browseActive = false
        episodeDateJob?.cancel()
    }
    private val refreshedCategories = mutableSetOf<String>()
    private var refreshedFullCatalog = false
    private var sortRestoreJob: Job? = null

    init {
        // Restore the user's last-used sort so it survives process death.
        sortRestoreJob = viewModelScope.launch {
            _sort.value = settings.contentSort(ContentType.SERIES).first()
        }
        // SavedStateHandle can restore the browse mode before the category list
        // reattaches. Resume any lazy fetch here; the Activity's later selection
        // callback is intentionally idempotent.
        when {
            query.value.isNotEmpty() -> ensureFullCatalog()
            selectedCategory.value != null -> scheduleSelectedCategoryLoad()
        }
    }

    fun setSort(order: ContentSort) {
        if (_sort.value == order) return
        // A very fast D-pad click during startup must win over the asynchronous
        // DataStore restore instead of being overwritten a frame later.
        sortRestoreJob?.cancel()
        _sort.value = order
        viewModelScope.launch { settings.setContentSort(ContentType.SERIES, order) }
    }

    /** Latest in-progress percent (0..100) keyed by series id, for grid bars. */
    suspend fun repoSeriesWatchProgress(): Map<String, Int> = repo.seriesWatchProgress()

    /**
     * Debounce category focus so fast D-pad traversal does not issue a request
     * for every row the user passes.
     */
    fun selectCategory(categoryId: String) {
        if (selectedCategory.value == categoryId) return
        episodeDateJob?.cancel()
        savedStateHandle[STATE_CATEGORY] = categoryId
        scheduleSelectedCategoryLoad()
    }

    fun setQuery(text: String) {
        val normalized = text.trim()
        if (query.value == normalized) return
        savedStateHandle[STATE_QUERY] = normalized
        episodeDateJob?.cancel()
        selectionLoadJob?.cancel()
        if (normalized.isNotEmpty()) {
            ensureFullCatalog()
        } else {
            scheduleSelectedCategoryLoad()
        }
    }

    /** Retries only what failed; the full forced sweep stays reserved for [refreshCatalog]. */
    fun retryLoad() {
        val retry = CatalogRetryPolicy.decide(
            _loadState.value, query.value, selectedCategory.value, setOf(CAT_ALL, CAT_POPULAR),
        )
        when (retry) {
            is CatalogRetry.Sweep -> {
                episodeDateJob?.cancel()
                selectionLoadJob?.cancel()
                val previousCatalog = catalogLoadJob
                catalogLoadJob = viewModelScope.launch {
                    previousCatalog?.cancelAndJoin()
                    loadFullCatalog(forceAll = retry.forced, only = retry.only)
                }
            }
            CatalogRetry.FullCatalog -> ensureFullCatalog()
            is CatalogRetry.Category -> {
                selectionLoadJob?.cancel()
                selectionLoadJob = viewModelScope.launch {
                    catalogLoadJob?.cancelAndJoin()
                    loadSingleCategory(retry.id, force = true)
                }
            }
        }
    }

    fun refreshCatalog() {
        episodeDateJob?.cancel()
        selectionLoadJob?.cancel()
        catalogLoadJob?.cancel()
        catalogLoadJob = viewModelScope.launch {
            loadFullCatalog(forceAll = true)
        }
    }

    private fun scheduleSelectedCategoryLoad() {
        val categoryId = selectedCategory.value ?: return
        selectionLoadJob?.cancel()
        selectionLoadJob = viewModelScope.launch {
            delay(300)
            if (query.value.isNotEmpty() ||
                categoryId == CAT_ALL || categoryId == CAT_POPULAR
            ) {
                ensureFullCatalog()
            } else {
                catalogLoadJob?.cancelAndJoin()
                loadSingleCategory(categoryId, force = categoryId !in refreshedCategories)
            }
        }
    }

    private fun ensureFullCatalog() {
        if (catalogLoadJob?.isActive == true) return
        catalogLoadJob = viewModelScope.launch {
            loadFullCatalog(forceAll = !refreshedFullCatalog)
        }
    }

    private suspend fun loadSingleCategory(categoryId: String, force: Boolean = false) {
        val config = settings.getSourceConfig()
        if (config == null) {
            _loadState.value = CatalogLoadState(errorRes = R.string.error_unknown)
            return
        }
        if (!force && repo.isSeriesCategoryLoaded(categoryId)) {
            _loadState.value = CatalogLoadState()
            refreshEpisodeDates(categoryId)
            return
        }
        _loadState.value = CatalogLoadState(loading = true, total = 1)
        when (val result = repo.syncSeriesCategory(config, categoryId, force)) {
            is CategorySync.Failed ->
                _loadState.value = CatalogLoadState(errorRes = result.failure.error.messageRes)
            // Fresh rows, a kept cache or a newer request's commit: the category is current.
            else -> {
                refreshedCategories += categoryId
                _loadState.value = CatalogLoadState()
                refreshEpisodeDates(categoryId)
            }
        }
    }

    /**
     * All/Recommended/search must represent the complete visible catalog. The
     * repository sweep continues past a broken category, stops once the panel
     * itself fails, and reports what it did not finish so Retry redoes only
     * that; [only] is that retry subset.
     */
    private suspend fun loadFullCatalog(forceAll: Boolean, only: Set<String>? = null) {
        _loadState.value = CatalogLoadState(loading = true)
        val config = settings.getSourceConfig()
        if (config == null) {
            _loadState.value = CatalogLoadState(errorRes = R.string.error_unknown)
            return
        }
        val report = repo.refreshSeriesCatalog(config, forceAll, only) { completed, total ->
            _loadState.value = CatalogLoadState(loading = true, completed = completed, total = total)
        }
        refreshedCategories += report.refreshed.map { it.id }
        if (forceAll && only == null && report.successful) refreshedFullCatalog = true
        _loadState.value = CatalogLoadState(
            completed = report.completed,
            total = report.total,
            errorRes = if (report.successful) null else R.string.catalog_series_refresh_incomplete,
            sweepReport = report,
        )
        // A panel that is unreachable, refusing or throttling would fail the
        // per-series episode-date requests the same way; do not start them.
        if (report.systemicFailure != null) return
        val swept = report.total > 0
        val category = selectedCategory.value
            ?.takeIf { swept }
            ?.takeUnless { it == CAT_ALL || it == CAT_POPULAR }
        refreshEpisodeDates(category, force = forceAll && swept)
    }

    private fun refreshEpisodeDates(categoryId: String?, force: Boolean = false) {
        val previous = episodeDateJob
        previous?.cancel()
        if (!browseActive) return
        episodeDateJob = viewModelScope.launch {
            previous?.join()
            val config = settings.getSourceConfig() ?: return@launch
            val hidden = settings.hiddenCategories(ContentType.SERIES).first().toList()
            _episodeDatesUpdating.value = true
            try { repo.refreshSeriesEpisodeDates(config, categoryId, hidden, force) }
            finally { _episodeDatesUpdating.value = false }
        }
    }

    /**
     * Paged series for the current query, or the selected category when the query
     * is empty. The query is debounced so typing doesn't re-query on every
     * keystroke; [cachedIn] keeps the paged stream alive across config changes.
     */
    private data class GridParams(
        val catId: String?,
        val query: String,
        val sort: ContentSort,
        val hidden: Set<String>
    )

    val items: Flow<PagingData<Series>> =
        combine(
            selectedCategory,
            query.debounce(250).distinctUntilChanged(),
            _sort,
            settings.hiddenCategories(ContentType.SERIES)
        ) { catId, q, sort, hidden -> GridParams(catId, q, sort, hidden) }
            .flatMapLatest { (catId, q, sort, hidden) ->
                val hiddenList = hidden.toList()
                when {
                    q.isNotEmpty() -> repo.pagingSeriesSearch(q, hiddenList, sort)
                    // Personal recommendations have their own ranking, independent of sort.
                    catId == CAT_POPULAR -> repo.pagingRecommendedSeries(hiddenList)
                    catId == null || catId == CAT_ALL -> repo.pagingSeriesAll(sort, hiddenList)
                    // A selected category that becomes hidden (Content Manager) must
                    // not keep leaking its content through the unfiltered by-category
                    // path; fall back to the filtered "all" grid until reselected.
                    catId in hidden -> repo.pagingSeriesAll(sort, hiddenList)
                    else -> repo.pagingSeriesByCategory(catId, sort)
                }
            }
            .cachedIn(viewModelScope)
}
