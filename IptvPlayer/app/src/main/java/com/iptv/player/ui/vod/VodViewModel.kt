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
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    // Movies are loaded lazily per category (see [selectCategory]); the full
    // catalog is never downloaded at once. The category rail comes from the
    // category cache, which the splash prefetch fills right after login.

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
    private val query = MutableStateFlow("")

    /** The currently selected category id (survives config changes). */
    val selectedCategoryId: String? get() = selectedCategory.value

    /** Current grid ordering; cycled from the UI. Restored from settings. */
    private val _sort = MutableStateFlow(ContentSort.RECENT)
    val sort: StateFlow<ContentSort> = _sort

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

    // Categories whose movies are currently being lazily downloaded. Tracked so
    // overlapping selections don't fetch the same category twice and so the
    // spinner only hides once *all* in-flight fetches finish.
    private val inFlight = mutableSetOf<String>()

    /**
     * Selects a category and, for real categories (not "Recently added"), lazily
     * downloads that category's movies if they aren't cached yet. The repository
     * merges into the cache and skips the network when already loaded, so
     * re-opening a category is instant.
     */
    fun selectCategory(categoryId: String) {
        selectedCategory.value = categoryId
        // Synthetic rails ("Recently added", "You may like") are computed from the
        // local cache — never trigger a per-category network fetch for them.
        if (categoryId == CAT_ALL || categoryId == CAT_POPULAR) return
        if (!inFlight.add(categoryId)) return // already fetching this category
        viewModelScope.launch {
            try {
                val config = settings.getSourceConfig() ?: return@launch
                if (repo.isVodCategoryLoaded(categoryId)) return@launch
                _refreshing.value = true
                repo.refreshVodCategory(config, categoryId)
            } finally {
                inFlight.remove(categoryId)
                if (inFlight.isEmpty()) _refreshing.value = false
            }
        }
    }

    fun setQuery(text: String) {
        query.value = text.trim()
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
            query.debounce(250).distinctUntilChanged(),
            _sort,
            settings.hiddenCategories(ContentType.VOD)
        ) { catId, q, sort, hidden -> GridParams(catId, q, sort, hidden) }
            .flatMapLatest { (catId, q, sort, hidden) ->
                val hiddenList = hidden.toList()
                when {
                    q.isNotEmpty() -> repo.pagingVodSearch(q, hiddenList)
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
