/*
 * VodViewModel.kt
 * Drives the Movies (VOD) screen: categories (left) + poster grid (center).
 * The grid reactively follows either the selected category or the search query.
 */
package com.iptv.player.ui.vod

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.ContentSort
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.VodItem
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
class VodViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    companion object {
        const val CAT_ALL = "__all__"
        const val CAT_POPULAR = "__popular__"
    }

    private val _loadState = MutableStateFlow(CatalogLoadState())
    val loadState: StateFlow<CatalogLoadState> = _loadState

    /**
     * Categories with a "Recently added" entry pinned to the top, each with a
     * count. Hidden categories are filtered out and the user's custom order
     * applied (see the Content Manager).
     */
    val categories: StateFlow<List<Category>> =
        repo.observeVisibleCategories(ContentType.VOD).map { cats ->
            buildList {
                add(
                    Category(
                        CAT_ALL,
                        getApplication<Application>().getString(R.string.cat_recently_added),
                        ContentType.VOD,
                        // The "Recently added" grid is the whole cache sorted newest
                        // first (unbounded), so the badge must show the real total —
                        // capping it to a fixed number made the count contradict the grid.
                        count = cats.sumOf { it.count ?: 0 }
                    )
                )
                // "You may like" — top-rated movies across the whole catalog, so
                // every poster is guaranteed playable (no TMDB key / dead links).
                add(
                    Category(
                        CAT_POPULAR,
                        getApplication<Application>().getString(R.string.cat_for_you),
                        ContentType.VOD,
                        count = cats.sumOf { it.count ?: 0 }
                    )
                )
                addAll(cats)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedCategory = MutableStateFlow<String?>(null)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** The currently selected category id (survives config changes). */
    val selectedCategoryId: String? get() = selectedCategory.value

    /** Current grid ordering; cycled from the UI. Restored from settings. */
    private val _sort = MutableStateFlow(ContentSort.RECENT)
    val sort: StateFlow<ContentSort> = _sort

    private var selectionLoadJob: Job? = null
    private var catalogLoadJob: Job? = null

    init {
        // Restore the user's last-used sort so it survives process death.
        viewModelScope.launch { _sort.value = settings.contentSort(ContentType.VOD).first() }
    }

    fun setSort(order: ContentSort) {
        _sort.value = order
        viewModelScope.launch { settings.setContentSort(ContentType.VOD, order) }
    }

    /** Watch-progress percents (0..100) keyed by resume id, for grid bars. */
    suspend fun repoAllWatchProgress(): Map<String, Int> = repo.allWatchProgress()

    /** Watched (finished) content ids, for grid ticks. */
    suspend fun repoWatchedIds(): Set<String> = repo.watchedIds()

    /**
     * Category focus is intentionally delayed. TV remotes emit focus changes for
     * every row traversed; fetching immediately would request every category the
     * user merely passes on the way to the one they want.
     */
    fun selectCategory(categoryId: String) {
        selectedCategory.value = categoryId
        scheduleSelectedCategoryLoad()
    }

    fun setQuery(text: String) {
        val normalized = text.trim()
        if (_query.value == normalized) return
        _query.value = normalized
        selectionLoadJob?.cancel()
        if (normalized.isNotEmpty()) {
            ensureFullCatalog()
        } else {
            scheduleSelectedCategoryLoad()
        }
    }

    /** Retries whichever single-category or whole-catalog request is visible. */
    fun retryLoad() {
        val categoryId = selectedCategory.value
        if (_query.value.isNotEmpty() || categoryId == null ||
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

    /** Explicit user refresh: re-sync category membership and every visible row. */
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
            if (_query.value.isNotEmpty() ||
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
        if (!force && repo.isVodCategoryLoaded(categoryId)) {
            _loadState.value = CatalogLoadState()
            return
        }
        _loadState.value = CatalogLoadState(loading = true, total = 1)
        when (val result = repo.refreshVodCategory(config, categoryId, force)) {
            is Outcome.Success -> _loadState.value = CatalogLoadState()
            is Outcome.Failure -> {
                _loadState.value = CatalogLoadState(errorRes = result.error.messageRes)
            }
        }
    }

    /**
     * All/Recommended/search must represent the complete visible catalog, not
     * only categories visited earlier. Categories load sequentially to avoid
     * overwhelming small providers and low-memory TV devices.
     */
    private suspend fun loadFullCatalog(forceAll: Boolean) {
        val config = settings.getSourceConfig()
        if (config == null) {
            _loadState.value = CatalogLoadState(errorRes = R.string.error_unknown)
            return
        }
        if (forceAll) {
            when (val categories = repo.refreshVodCategories(config)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> {
                    _loadState.value =
                        CatalogLoadState(errorRes = categories.error.messageRes)
                    return
                }
            }
        }
        val hidden = settings.hiddenCategories(ContentType.VOD).first()
        var categoryIds = if (forceAll) {
            repo.vodCategoryIds(hidden)
        } else {
            repo.unloadedVodCategoryIds(hidden)
        }
        // A cold cache can reach this screen before splash completes. Fetch the
        // lightweight category list once, then continue catalog hydration.
        if (categoryIds.isEmpty() && categories.value.size <= 2) {
            when (val categoriesResult = repo.refreshVodCategories(config)) {
                is Outcome.Success -> {
                    categoryIds = if (forceAll) repo.vodCategoryIds(hidden)
                    else repo.unloadedVodCategoryIds(hidden)
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
            when (val result = repo.refreshVodCategory(config, categoryId, force = forceAll)) {
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
     * Paged movies for the current query, or the selected category when the query
     * is empty. The query is debounced so typing doesn't re-query on every
     * keystroke; [cachedIn] keeps the paged stream alive across config changes.
     */
    private data class GridParams(
        val catId: String?,
        val query: String,
        val sort: ContentSort,
        val hidden: Set<String>
    )

    val items: Flow<PagingData<VodItem>> =
        combine(
            selectedCategory,
            _query.debounce(250).distinctUntilChanged(),
            _sort,
            settings.hiddenCategories(ContentType.VOD)
        ) { catId, q, sort, hidden -> GridParams(catId, q, sort, hidden) }
            .flatMapLatest { (catId, q, sort, hidden) ->
                val hiddenList = hidden.toList()
                when {
                    q.isNotEmpty() -> repo.pagingVodSearch(q, hiddenList, sort)
                    // "You may like" always shows highest-rated first, regardless of
                    // the grid's current sort selection.
                    catId == CAT_POPULAR -> repo.pagingVodAll(ContentSort.RATING, hiddenList)
                    catId == null || catId == CAT_ALL -> repo.pagingVodAll(sort, hiddenList)
                    // A selected category that becomes hidden (Content Manager) must
                    // not keep leaking its content through the unfiltered by-category
                    // path; fall back to the filtered "all" grid until reselected.
                    catId in hidden -> repo.pagingVodAll(sort, hiddenList)
                    else -> repo.pagingVodByCategory(catId, sort)
                }
            }
            .cachedIn(viewModelScope)
}
