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
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.Series
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    companion object {
        const val CAT_ALL = "__all__"
        const val RECENT_LIMIT = 20
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    init {
        // Populate the Series cache from the network on first open (best-effort).
        viewModelScope.launch {
            val config = settings.getSourceConfig() ?: return@launch
            _refreshing.value = true
            repo.refreshSeries(config)
            _refreshing.value = false
        }
    }

    /** Categories with a "Recently added" entry pinned to the top, each with a count. */
    val categories: StateFlow<List<Category>> =
        combine(
            repo.observeSeriesCategories(),
            repo.observeSeriesCategoryCounts()
        ) { cats, counts ->
            buildList {
                add(
                    Category(
                        CAT_ALL,
                        getApplication<Application>().getString(R.string.cat_recently_added),
                        ContentType.SERIES,
                        count = counts.values.sum().coerceAtMost(RECENT_LIMIT)
                    )
                )
                addAll(cats.map { it.copy(count = counts[it.id]) })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedCategory = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

    fun selectCategory(categoryId: String) {
        selectedCategory.value = categoryId
    }

    fun setQuery(text: String) {
        query.value = text.trim()
    }

    /** Series for the current query, or selected category when the query is empty. */
    val items: StateFlow<List<Series>> =
        combine(selectedCategory, query) { catId, q -> catId to q }
            .flatMapLatest { (catId, q) ->
                val source: Flow<List<Series>> = when {
                    q.isNotEmpty() -> repo.searchSeries(q)
                    catId == null || catId == CAT_ALL -> repo.observeRecentSeries(RECENT_LIMIT)
                    else -> repo.observeSeriesByCategory(catId)
                }
                source
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
