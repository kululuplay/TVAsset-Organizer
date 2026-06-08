/*
 * SeriesViewModel.kt
 * Drives the Series screen: categories (left) + poster grid (center). Mirrors
 * VodViewModel; the grid follows either the selected category or the query.
 */
package com.iptv.player.ui.series

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.ContentSort
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.Series
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SeriesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    companion object {
        const val CAT_ALL = "__all__"
        const val RECENT_LIMIT = 20
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    // Series are loaded lazily per category (see [selectCategory]); the full
    // catalog is never downloaded at once. The category rail comes from the
    // category cache, which the splash prefetch fills right after login.

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
                        count = cats.sumOf { it.count ?: 0 }.coerceAtMost(RECENT_LIMIT)
                    )
                )
                addAll(cats)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedCategory = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

    /** Current grid ordering; cycled from the UI. Defaults to newest-first. */
    private val _sort = MutableStateFlow(ContentSort.RECENT)
    val sort: StateFlow<ContentSort> = _sort

    fun setSort(order: ContentSort) {
        _sort.value = order
    }

    // Categories whose series are currently being lazily downloaded. Tracked so
    // overlapping selections don't fetch the same category twice and so the
    // spinner only hides once *all* in-flight fetches finish.
    private val inFlight = mutableSetOf<String>()

    /**
     * Selects a category and, for real categories (not "Recently added"), lazily
     * downloads that category's series if they aren't cached yet. The repository
     * merges into the cache and skips the network when already loaded, so
     * re-opening a category is instant.
     */
    fun selectCategory(categoryId: String) {
        selectedCategory.value = categoryId
        if (categoryId == CAT_ALL) return
        if (!inFlight.add(categoryId)) return // already fetching this category
        viewModelScope.launch {
            try {
                val config = settings.getSourceConfig() ?: return@launch
                if (repo.isSeriesCategoryLoaded(categoryId)) return@launch
                _refreshing.value = true
                repo.refreshSeriesCategory(config, categoryId)
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
     * Paged series for the current query, or the selected category when the query
     * is empty. The query is debounced so typing doesn't re-query on every
     * keystroke; [cachedIn] keeps the paged stream alive across config changes.
     */
    val items: Flow<PagingData<Series>> =
        combine(
            selectedCategory,
            query.debounce(250).distinctUntilChanged(),
            _sort
        ) { catId, q, sort -> Triple(catId, q, sort) }
            .flatMapLatest { (catId, q, sort) ->
                when {
                    q.isNotEmpty() -> repo.pagingSeriesSearch(q)
                    catId == null || catId == CAT_ALL -> repo.pagingSeriesAll(sort)
                    else -> repo.pagingSeriesByCategory(catId, sort)
                }
            }
            .cachedIn(viewModelScope)
}
