/*
 * SearchViewModel.kt
 * Drives the global search screen: one saved/debounced query fans out into Live,
 * Movies and Series while a bounded catalog hydration job fills categories that
 * the user has not opened before.
 */
package com.iptv.player.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.VodItem
import com.iptv.player.ui.common.CatalogLoadState
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val STATE_QUERY = "global_search_query"
        private const val QUERY_DEBOUNCE_MS = 300L
    }

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    /** Normalized query survives both configuration changes and process death. */
    val query: StateFlow<String> =
        savedState.getStateFlow(STATE_QUERY, "")

    private data class QueryRequest(
        val query: String,
        val generation: Long,
    )

    private var queryGeneration = 0L
    private val queryRequests =
        MutableStateFlow(QueryRequest(query.value, queryGeneration))

    val currentRequestGeneration: Long
        get() = queryRequests.value.generation

    /**
     * One shared debounce clock prevents three independent searches being fired
     * for every key press. The generation is retained through the debounce: a
     * quick clear → retype of the same text must restart the search even if the
     * transient empty value never reaches the debounced output.
     */
    private val effectiveRequest: StateFlow<QueryRequest> =
        queryRequests
            .debounce(QUERY_DEBOUNCE_MS)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                queryRequests.value,
            )

    val effectiveQuery: StateFlow<String> =
        effectiveRequest
            .map(QueryRequest::query)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                effectiveRequest.value.query,
            )

    private val _catalogState = MutableStateFlow(CatalogLoadState())
    val catalogState: StateFlow<CatalogLoadState> = _catalogState

    data class SearchGeneration(
        val query: String,
        val requestGeneration: Long,
        val hiddenCategories: List<String>,
        val retryAttempt: Long = 0L,
    )

    data class LiveSearchResult(
        val generation: SearchGeneration,
        val items: List<Channel>,
        val failed: Boolean = false,
    ) {
        val query: String
            get() = generation.query
    }

    /**
     * Carries the complete search generation alongside PagingData. Reading
     * [effectiveQuery] later in the Activity is racy: it may already contain the
     * next query while the previous PagingData emission is queued.
     */
    data class PagingSearchResult<T>(
        val generation: SearchGeneration,
        val data: PagingData<T>,
    ) {
        val query: String
            get() = generation.query
    }

    private val liveRetryNonce = MutableStateFlow(0L)

    private var catalogJob: Job? = null
    private var catalogGeneration = 0L

    init {
        if (query.value.isNotEmpty()) hydrateCatalog()
    }

    fun setQuery(text: String) {
        val normalized = text.trim()
        val previous = query.value
        if (previous == normalized) return

        savedState[STATE_QUERY] = normalized
        queryGeneration += 1L
        queryRequests.value = QueryRequest(normalized, queryGeneration)

        when {
            normalized.isEmpty() -> cancelCatalogHydration()
            previous.isEmpty() -> hydrateCatalog()
        }
    }

    fun retryCatalog() {
        if (query.value.isNotEmpty()) hydrateCatalog()
    }

    fun retryLiveSearch() {
        if (query.value.isNotEmpty()) liveRetryNonce.value += 1L
    }

    /**
     * Global search must not silently search only categories opened previously.
     * Hydration is independent of query wording, so it continues while a non-empty
     * query changes, but is cancelled as soon as the query is cleared.
     */
    private fun hydrateCatalog() {
        if (query.value.isEmpty() || catalogJob?.isActive == true) return

        val generation = ++catalogGeneration
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                publishCatalogState(generation, CatalogLoadState(loading = true))
                val config = settings.getSourceConfig()
                if (config == null) {
                    publishCatalogState(
                        generation,
                        CatalogLoadState(errorRes = R.string.error_unknown),
                    )
                    return@launch
                }

                when (val result = repo.refreshVodCategories(config)) {
                    is Outcome.Success -> Unit
                    is Outcome.Failure -> {
                        publishCatalogState(
                            generation,
                            CatalogLoadState(errorRes = result.error.messageRes),
                        )
                        return@launch
                    }
                }
                when (val result = repo.refreshSeriesCategories(config)) {
                    is Outcome.Success -> Unit
                    is Outcome.Failure -> {
                        publishCatalogState(
                            generation,
                            CatalogLoadState(errorRes = result.error.messageRes),
                        )
                        return@launch
                    }
                }

                val hiddenVod = settings.hiddenCategories(ContentType.VOD).first()
                val hiddenSeries = settings.hiddenCategories(ContentType.SERIES).first()
                val vodIds = repo.unloadedVodCategoryIds(hiddenVod)
                val seriesIds = repo.unloadedSeriesCategoryIds(hiddenSeries)
                val total = vodIds.size + seriesIds.size
                if (total == 0) {
                    publishCatalogState(generation, CatalogLoadState())
                    return@launch
                }

                publishCatalogState(
                    generation,
                    CatalogLoadState(loading = true, total = total),
                )
                var completed = 0

                for (id in vodIds) {
                    when (val result = repo.refreshVodCategory(config, id)) {
                        is Outcome.Success -> {
                            completed += 1
                            publishCatalogState(
                                generation,
                                CatalogLoadState(true, completed, total),
                            )
                        }
                        is Outcome.Failure -> {
                            publishCatalogState(
                                generation,
                                CatalogLoadState(
                                    loading = false,
                                    completed = completed,
                                    total = total,
                                    errorRes = result.error.messageRes,
                                ),
                            )
                            return@launch
                        }
                    }
                }

                for (id in seriesIds) {
                    when (val result = repo.refreshSeriesCategory(config, id)) {
                        is Outcome.Success -> {
                            completed += 1
                            publishCatalogState(
                                generation,
                                CatalogLoadState(true, completed, total),
                            )
                        }
                        is Outcome.Failure -> {
                            publishCatalogState(
                                generation,
                                CatalogLoadState(
                                    loading = false,
                                    completed = completed,
                                    total = total,
                                    errorRes = result.error.messageRes,
                                ),
                            )
                            return@launch
                        }
                    }
                }
                publishCatalogState(generation, CatalogLoadState())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                publishCatalogState(
                    generation,
                    CatalogLoadState(errorRes = R.string.error_unknown),
                )
            }
        }
        catalogJob = job
        job.invokeOnCompletion {
            if (catalogJob === job) catalogJob = null
        }
        job.start()
    }

    private fun cancelCatalogHydration() {
        catalogGeneration += 1
        catalogJob?.cancel()
        catalogJob = null
        _catalogState.value = CatalogLoadState()
    }

    private fun publishCatalogState(
        generation: Long,
        state: CatalogLoadState,
    ) {
        if (generation == catalogGeneration && query.value.isNotEmpty()) {
            _catalogState.value = state
        }
    }

    // Live stays a plain Room/FTS list. Pairing it with the shared effective query
    // ensures an old query is cancelled before a new database flow is collected.
    val liveResults: StateFlow<LiveSearchResult> =
        combine(
            effectiveRequest,
            settings.hiddenCategories(ContentType.LIVE),
            liveRetryNonce,
        ) { request, hidden, retryAttempt ->
            Triple(request, hidden, retryAttempt)
        }
            .flatMapLatest { (request, hidden, retryAttempt) ->
                val q = request.query
                val generation = SearchGeneration(
                    q,
                    request.generation,
                    hidden.sorted(),
                    retryAttempt,
                )
                if (q.isEmpty()) {
                    flowOf(LiveSearchResult(generation, emptyList()))
                } else {
                    repo.search(q, ContentType.LIVE, generation.hiddenCategories)
                        .map { LiveSearchResult(generation, it) }
                        .catch {
                            emit(
                                LiveSearchResult(
                                    generation,
                                    emptyList(),
                                    failed = true,
                                ),
                            )
                        }
                }
            }
            .stateIn(
                viewModelScope,
                // Keep the Room + hidden-category projection current while the
                // Search ViewModel exists. Replaying a pre-Settings result on
                // resume could otherwise expose a newly hidden channel briefly.
                SharingStarted.Eagerly,
                LiveSearchResult(
                    SearchGeneration("", 0L, emptyList()),
                    emptyList(),
                ),
            )

    val movies: Flow<PagingSearchResult<VodItem>> =
        combine(
            effectiveRequest,
            settings.hiddenCategories(ContentType.VOD),
        ) { request, hidden -> request to hidden }
            .flatMapLatest { (request, hidden) ->
                val q = request.query
                val generation =
                    SearchGeneration(q, request.generation, hidden.sorted())
                if (q.isEmpty()) {
                    flowOf(PagingSearchResult(generation, PagingData.empty()))
                } else {
                    repo.pagingVodSearch(q, generation.hiddenCategories)
                        .map { PagingSearchResult(generation, it) }
                }
            }

    val series: Flow<PagingSearchResult<Series>> =
        combine(
            effectiveRequest,
            settings.hiddenCategories(ContentType.SERIES),
        ) { request, hidden -> request to hidden }
            .flatMapLatest { (request, hidden) ->
                val q = request.query
                val generation =
                    SearchGeneration(q, request.generation, hidden.sorted())
                if (q.isEmpty()) {
                    flowOf(PagingSearchResult(generation, PagingData.empty()))
                } else {
                    repo.pagingSeriesSearch(q, generation.hiddenCategories)
                        .map { PagingSearchResult(generation, it) }
                }
            }
}
