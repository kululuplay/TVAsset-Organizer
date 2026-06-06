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
        const val RECENT_LIMIT = 20
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    // Movies are loaded lazily per category (see [selectCategory]); the full
    // catalog is never downloaded at once. The category rail comes from the
    // category cache, which the splash prefetch fills right after login.

    /** Categories with a "Recently added" entry pinned to the top, each with a count. */
    val categories: StateFlow<List<Category>> =
        combine(
            repo.observeVodCategories(),
            repo.observeVodCategoryCounts()
        ) { cats, counts ->
            buildList {
                add(
                    Category(
                        CAT_ALL,
                        getApplication<Application>().getString(R.string.cat_recently_added),
                        ContentType.VOD,
                        count = counts.values.sum().coerceAtMost(RECENT_LIMIT)
                    )
                )
                addAll(cats.map { it.copy(count = counts[it.id]) })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedCategory = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

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
        if (categoryId == CAT_ALL) return
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
    val items: Flow<PagingData<VodItem>> =
        combine(
            selectedCategory,
            query.debounce(250).distinctUntilChanged()
        ) { catId, q -> catId to q }
            .flatMapLatest { (catId, q) ->
                when {
                    q.isNotEmpty() -> repo.pagingVodSearch(q)
                    catId == null || catId == CAT_ALL -> repo.pagingRecentVod()
                    else -> repo.pagingVodByCategory(catId)
                }
            }
            .cachedIn(viewModelScope)
}
