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
import com.iptv.player.ui.common.CatalogLoadState
import com.iptv.player.util.Outcome
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val _catalogState = MutableStateFlow(CatalogLoadState())
    val catalogState: StateFlow<CatalogLoadState> = _catalogState
    private var catalogJob: Job? = null

    // Debounced query shared by all three result streams so typing doesn't fire a
    // query per keystroke across live/movies/series at once.
    private val debouncedQuery = query.debounce(250).distinctUntilChanged()

    fun setQuery(text: String) {
        val normalized = text.trim()
        val startsSearch = query.value.isEmpty() && normalized.isNotEmpty()
        query.value = normalized
        if (startsSearch) hydrateCatalog()
    }

    fun retryCatalog() = hydrateCatalog()

    /**
     * Global search must not silently search only previously opened categories.
     * Complete both visible catalogs once, sequentially, while cached matches
     * remain immediately usable.
     */
    private fun hydrateCatalog() {
        if (catalogJob?.isActive == true) return
        catalogJob = viewModelScope.launch {
            val config = settings.getSourceConfig()
            if (config == null) {
                _catalogState.value =
                    CatalogLoadState(errorRes = com.iptv.player.R.string.error_unknown)
                return@launch
            }
            when (val vodCategories = repo.refreshVodCategories(config)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> {
                    _catalogState.value =
                        CatalogLoadState(errorRes = vodCategories.error.messageRes)
                    return@launch
                }
            }
            when (val seriesCategories = repo.refreshSeriesCategories(config)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> {
                    _catalogState.value =
                        CatalogLoadState(errorRes = seriesCategories.error.messageRes)
                    return@launch
                }
            }
            val hiddenVod = settings.hiddenCategories(ContentType.VOD).first()
            val hiddenSeries = settings.hiddenCategories(ContentType.SERIES).first()
            val vodIds = repo.unloadedVodCategoryIds(hiddenVod)
            val seriesIds = repo.unloadedSeriesCategoryIds(hiddenSeries)
            val total = vodIds.size + seriesIds.size
            if (total == 0) {
                _catalogState.value = CatalogLoadState()
                return@launch
            }
            _catalogState.value = CatalogLoadState(loading = true, total = total)
            var completed = 0
            for (id in vodIds) {
                when (val result = repo.refreshVodCategory(config, id)) {
                    is Outcome.Success -> {
                        completed += 1
                        _catalogState.value =
                            CatalogLoadState(true, completed, total)
                    }
                    is Outcome.Failure -> {
                        _catalogState.value =
                            CatalogLoadState(false, completed, total, result.error.messageRes)
                        return@launch
                    }
                }
            }
            for (id in seriesIds) {
                when (val result = repo.refreshSeriesCategory(config, id)) {
                    is Outcome.Success -> {
                        completed += 1
                        _catalogState.value =
                            CatalogLoadState(true, completed, total)
                    }
                    is Outcome.Failure -> {
                        _catalogState.value =
                            CatalogLoadState(false, completed, total, result.error.messageRes)
                        return@launch
                    }
                }
            }
            _catalogState.value = CatalogLoadState()
        }
    }

    // Live stays a plain list (FTS): live counts are bounded and the UI shows them
    // all, so paging would add complexity without benefit here.
    val channels: StateFlow<List<Channel>> =
        combine(
            debouncedQuery,
            settings.hiddenCategories(ContentType.LIVE),
        ) { q, hidden -> q to hidden }
            .flatMapLatest { (q, hidden) ->
            if (q.isEmpty()) flowOf(emptyList())
            else repo.search(q, ContentType.LIVE, hidden.toList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val movies: Flow<PagingData<VodItem>> =
        combine(
            debouncedQuery,
            settings.hiddenCategories(ContentType.VOD),
        ) { q, hidden -> q to hidden }
            .flatMapLatest { (q, hidden) ->
            if (q.isEmpty()) flowOf(PagingData.empty())
            else repo.pagingVodSearch(q, hidden.toList())
        }.cachedIn(viewModelScope)

    val series: Flow<PagingData<Series>> =
        combine(
            debouncedQuery,
            settings.hiddenCategories(ContentType.SERIES),
        ) { q, hidden -> q to hidden }
            .flatMapLatest { (q, hidden) ->
            if (q.isEmpty()) flowOf(PagingData.empty())
            else repo.pagingSeriesSearch(q, hidden.toList())
        }.cachedIn(viewModelScope)
}
