/*
 * VodViewModel.kt
 * Drives the Movies (VOD) screen: categories (left) + poster grid (center).
 * The grid reactively follows either the selected category or the search query.
 */
package com.iptv.player.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.VodItem
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
class VodViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    companion object {
        const val CAT_ALL = "__all__"
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    init {
        // Populate the VOD cache from the network on first open (best-effort).
        viewModelScope.launch {
            val config = settings.getSourceConfig() ?: return@launch
            _refreshing.value = true
            repo.refreshVod(config)
            _refreshing.value = false
        }
    }

    /** Categories with an "All" entry pinned to the top. */
    val categories: StateFlow<List<Category>> =
        repo.observeVodCategories().map { cats ->
            buildList {
                add(Category(CAT_ALL, "All", ContentType.VOD))
                addAll(cats)
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

    /** Movies for the current query, or selected category when the query is empty. */
    val items: StateFlow<List<VodItem>> =
        combine(selectedCategory, query) { catId, q -> catId to q }
            .flatMapLatest { (catId, q) ->
                val source: Flow<List<VodItem>> = when {
                    q.isNotEmpty() -> repo.searchVod(q)
                    catId == null || catId == CAT_ALL -> repo.observeVod()
                    else -> repo.observeVodByCategory(catId)
                }
                source
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
