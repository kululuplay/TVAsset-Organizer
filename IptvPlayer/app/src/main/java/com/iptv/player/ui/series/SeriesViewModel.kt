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
import com.iptv.player.ui.common.CatalogLoadState
import com.iptv.player.util.Outcome
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
                        // The "Recently added" grid is the whole cache sorted newest
                        // first (unbounded), so the badge must show the real total —
                        // capping it to a fixed number made the count contradict the grid.
                        count = cats.sumOf { it.count ?: 0 }
                    )
                )
                // "You may like" — top-rated series across the whole catalog, so
                // every poster is guaranteed playable (no TMDB key / dead links).
                add(
                    Category(
                        CAT_POPULAR,
                        getApplication<Application>().getString(R.string.cat_for_you),
                        ContentType.SERIES,
                        count = cats.sumOf { it.count ?: 0 }
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
        savedStateHandle[STATE_CATEGORY] = categoryId
        scheduleSelectedCategoryLoad()
    }

    fun setQuery(text: String) {
        val normalized = text.trim()
        if (query.value == normalized) return
        savedStateHandle[STATE_QUERY] = normalized
        selectionLoadJob?.cancel()
        if (normalized.isNotEmpty()) {
            ensureFullCatalog()
        } else {
            scheduleSelectedCategoryLoad()
        }
    }

    fun retryLoad() {
        val categoryId = selectedCategory.value
        if (query.value.isNotEmpty() || categoryId == null ||
            categoryId == CAT_ALL || categoryId == CAT_POPULAR
        ) {
            ensureFullCatalog()
            return
        }
        selectionLoadJob?.cancel()
        selectionLoadJob = viewModelScope.launch {
            catalogLoadJob?.cancelAndJoin()
            loadSingleCategory(categoryId, force = true)
        }
    }

    fun refreshCatalog() {
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
                loadSingleCategory(categoryId)
            }
        }
    }

    private fun ensureFullCatalog() {
        if (catalogLoadJob?.isActive == true) return
        catalogLoadJob = viewModelScope.launch {
            loadFullCatalog(forceAll = false)
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
            return
        }
        _loadState.value = CatalogLoadState(loading = true, total = 1)
        when (val result = repo.refreshSeriesCategory(config, categoryId, force)) {
            is Outcome.Success -> _loadState.value = CatalogLoadState()
            is Outcome.Failure -> {
                _loadState.value = CatalogLoadState(errorRes = result.error.messageRes)
            }
        }
    }

    private suspend fun loadFullCatalog(forceAll: Boolean) {
        val config = settings.getSourceConfig()
        if (config == null) {
            _loadState.value = CatalogLoadState(errorRes = R.string.error_unknown)
            return
        }
        if (forceAll) {
            when (val categories = repo.refreshSeriesCategories(config)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> {
                    _loadState.value =
                        CatalogLoadState(errorRes = categories.error.messageRes)
                    return
                }
            }
        }
        val hidden = settings.hiddenCategories(ContentType.SERIES).first()
        var categoryIds = if (forceAll) {
            repo.seriesCategoryIds(hidden)
        } else {
            repo.unloadedSeriesCategoryIds(hidden)
        }
        if (categoryIds.isEmpty() && categories.value.size <= 2) {
            when (val categoriesResult = repo.refreshSeriesCategories(config)) {
                is Outcome.Success -> {
                    categoryIds = if (forceAll) repo.seriesCategoryIds(hidden)
                    else repo.unloadedSeriesCategoryIds(hidden)
                }
                is Outcome.Failure -> {
                    _loadState.value =
                        CatalogLoadState(errorRes = categoriesResult.error.messageRes)
                    return
                }
            }
        }
        if (categoryIds.isEmpty()) {
            _loadState.value = CatalogLoadState()
            return
        }
        _loadState.value = CatalogLoadState(loading = true, total = categoryIds.size)
        categoryIds.forEachIndexed { index, categoryId ->
            when (val result = repo.refreshSeriesCategory(config, categoryId, force = forceAll)) {
                is Outcome.Success -> {
                    _loadState.value = CatalogLoadState(
                        loading = true,
                        completed = index + 1,
                        total = categoryIds.size,
                    )
                }
                is Outcome.Failure -> {
                    _loadState.value = CatalogLoadState(
                        completed = index,
                        total = categoryIds.size,
                        errorRes = result.error.messageRes,
                    )
                    return
                }
            }
        }
        _loadState.value = CatalogLoadState()
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
                    // "You may like" always shows highest-rated first, regardless of
                    // the grid's current sort selection.
                    catId == CAT_POPULAR -> repo.pagingSeriesAll(ContentSort.RATING, hiddenList)
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
