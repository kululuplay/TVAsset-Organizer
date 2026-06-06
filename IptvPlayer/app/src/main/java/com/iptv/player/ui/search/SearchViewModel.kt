/*
 * SearchViewModel.kt
 * Drives the global search screen: one query string fans out into three
 * reactive result lists — live channels, movies and series — backed by the
 * repository's per-type search queries. Empty query yields empty results.
 */
package com.iptv.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.VodItem
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private val query = MutableStateFlow("")

    // Debounced query shared by all three result streams so typing doesn't fire a
    // query per keystroke across live/movies/series at once.
    private val debouncedQuery = query.debounce(250).distinctUntilChanged()

    init {
        // Both movies and series are lazy per-category by design, so global search
        // only covers categories the user has already opened/downloaded (an
        // accepted tradeoff to avoid pulling the whole catalog). We refresh just
        // the cheap category lists here.
        viewModelScope.launch {
            val config = settings.getSourceConfig() ?: return@launch
            repo.refreshVodCategories(config)
            repo.refreshSeriesCategories(config)
        }
    }

    fun setQuery(text: String) {
        query.value = text.trim()
    }

    // Live stays a plain list (FTS): live counts are bounded and the UI shows them
    // all, so paging would add complexity without benefit here.
    val channels: StateFlow<List<Channel>> =
        debouncedQuery.flatMapLatest { q ->
            if (q.isEmpty()) flowOf(emptyList()) else repo.search(q, ContentType.LIVE)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val movies: Flow<PagingData<VodItem>> =
        debouncedQuery.flatMapLatest { q ->
            if (q.isEmpty()) flowOf(PagingData.empty()) else repo.pagingVodSearch(q)
        }.cachedIn(viewModelScope)

    val series: Flow<PagingData<Series>> =
        debouncedQuery.flatMapLatest { q ->
            if (q.isEmpty()) flowOf(PagingData.empty()) else repo.pagingSeriesSearch(q)
        }.cachedIn(viewModelScope)
}
