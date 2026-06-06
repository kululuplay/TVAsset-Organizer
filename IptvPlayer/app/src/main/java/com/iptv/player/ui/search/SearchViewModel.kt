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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private val query = MutableStateFlow("")

    init {
        // Series is a full refresh so it's fully searchable. Movies are lazy
        // per-category by design, so global movie search only covers categories the
        // user has already opened/downloaded (an accepted tradeoff to avoid pulling
        // the whole ~60k catalog); we refresh just the cheap category list here.
        viewModelScope.launch {
            val config = settings.getSourceConfig() ?: return@launch
            repo.refreshVodCategories(config)
            repo.refreshSeries(config)
        }
    }

    fun setQuery(text: String) {
        query.value = text.trim()
    }

    val channels: StateFlow<List<Channel>> =
        query.flatMapLatest { q ->
            if (q.isEmpty()) flowOf(emptyList()) else repo.search(q, ContentType.LIVE)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val movies: StateFlow<List<VodItem>> =
        query.flatMapLatest { q ->
            if (q.isEmpty()) flowOf(emptyList()) else repo.searchVod(q)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val series: StateFlow<List<Series>> =
        query.flatMapLatest { q ->
            if (q.isEmpty()) flowOf<List<Series>>(emptyList()) else repo.searchSeries(q)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
