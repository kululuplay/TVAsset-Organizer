/*
 * HomeViewModel.kt
 * Drives the 3-pane TV home: categories (left), channels (center), info (right).
 * Categories include synthetic "Favorites" and "Recently Watched" entries on top.
 * Channel list reactively follows the selected category.
 */
package com.iptv.player.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.NowNext
import com.iptv.player.data.model.Program
import com.iptv.player.ui.common.CatalogLoadState
import com.iptv.player.util.Outcome
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    app: Application,
    private val radio: Boolean = false,
) : AndroidViewModel(app) {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    companion object {
        const val CAT_FAVORITES = "__favorites__"
        const val CAT_RECENT = "__recent__"
    }

    /** Builds a [HomeViewModel] in either Live TV ([radio] = false) or Radio mode. */
    class Factory(
        private val app: Application,
        private val radio: Boolean,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(app, radio) as T
        }
    }

    /**
     * Categories with Favorites + Recent pinned to the top, each with a count.
     * Hidden categories are filtered out and the user's custom order applied
     * (see the Content Manager); the synthetic groups always stay on top.
     */
    val categories: StateFlow<List<Category>> =
        combine(
            repo.observeVisibleCategories(ContentType.LIVE, radio = radio),
            repo.observeFavorites(),
            settings.hiddenCategories(ContentType.LIVE),
        ) { cats, favs, hidden ->
            buildList {
                // Radio mode is a clean folder of stations only — the synthetic
                // Favorites/Recent rows stay on the Live TV page.
                if (!radio) {
                    add(
                        Category(
                            CAT_FAVORITES,
                            getApplication<Application>().getString(R.string.section_favorites),
                            ContentType.LIVE,
                            count = favs.count {
                                !it.isRadio && it.categoryId !in hidden
                            },
                        ),
                    )
                    add(
                        Category(
                            CAT_RECENT,
                            getApplication<Application>().getString(R.string.section_recent),
                            ContentType.LIVE,
                        ),
                    )
                }
                addAll(cats)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedCategory = MutableStateFlow<String?>(null)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _loadState = MutableStateFlow(CatalogLoadState())
    val loadState: StateFlow<CatalogLoadState> = _loadState
    private var refreshJob: Job? = null

    init {
        // Splash normally fills this cache. If it could not, the Live page gets
        // one visible retry attempt instead of silently showing an empty screen.
        viewModelScope.launch {
            if (repo.liveChannelCount() == 0) refresh()
        }
    }

    fun selectCategory(categoryId: String) {
        selectedCategory.value = categoryId
    }

    fun setQuery(text: String) {
        _query.value = text.trim()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val config = settings.getSourceConfig()
            if (config == null) {
                _loadState.value = CatalogLoadState(errorRes = R.string.error_unknown)
                return@launch
            }
            _loadState.value = CatalogLoadState(loading = true, total = 1)
            when (val result = repo.refreshLive(config)) {
                is Outcome.Success -> _loadState.value = CatalogLoadState()
                is Outcome.Failure -> {
                    _loadState.value = CatalogLoadState(errorRes = result.error.messageRes)
                }
            }
        }
    }

    /** Channels for the currently selected category (or favorites/recent). */
    val channels: StateFlow<ChannelSnapshot> =
        combine(
            selectedCategory,
            repo.observeFavorites(),
            _query.debounce(250).distinctUntilChanged(),
            settings.hiddenCategories(ContentType.LIVE),
        ) { catId, favIds, query, hidden ->
            ChannelParams(catId, favIds.map { it.id }.toSet(), query, hidden)
        }
            .flatMapLatest { (catId, favSet, query, hidden) ->
                val source: Flow<List<Channel>> = when {
                    query.isNotEmpty() -> repo.search(
                        query,
                        ContentType.LIVE,
                        hidden.toList(),
                        radio = if (radio) 1 else 0,
                    )
                    // Pre-selection startup state: emit nothing rather than loading the
                    // entire LIVE catalog, which the initial category selection replaces
                    // a moment later anyway (avoids a big throwaway list on big catalogs).
                    catId == null -> flowOf(emptyList())
                    // Favorites/Recent are cross-section; keep radios off the Live
                    // TV page (these synthetic rows only ever show in live mode).
                    catId == CAT_FAVORITES -> repo.observeFavorites().map {
                        it.filter { c -> !c.isRadio && c.categoryId !in hidden }
                    }
                    catId == CAT_RECENT -> repo.observeRecent().map {
                        it.filter { c -> !c.isRadio && c.categoryId !in hidden }
                    }
                    else -> repo.observeChannelsByCategory(ContentType.LIVE, catId, radio = radio)
                }
                // Annotate each channel with its current favorite state.
                source.map { list ->
                    ChannelSnapshot(
                        categoryId = catId,
                        query = query,
                        channels = list.map { it.copy(isFavorite = favSet.contains(it.id)) },
                        loaded = true,
                    )
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                ChannelSnapshot(),
            )

    private data class ChannelParams(
        val categoryId: String?,
        val favoriteIds: Set<String>,
        val query: String,
        val hiddenCategories: Set<String>,
    )

    data class ChannelSnapshot(
        val categoryId: String? = null,
        val query: String = "",
        val channels: List<Channel> = emptyList(),
        val loaded: Boolean = false,
    )

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { repo.toggleFavorite(channelId) }
    }

    /** Now/next EPG for the focused channel (best-effort; empty if no guide). */
    suspend fun nowNext(channel: Channel): NowNext = repo.getNowNext(channel)

    /**
     * EPG schedule for the focused channel: a window from a few hours back to
     * roughly a day ahead, used to populate the right-pane program list.
     */
    suspend fun programs(channel: Channel): List<Program> {
        val now = System.currentTimeMillis()
        val from = now - 3 * 60 * 60 * 1000L
        val to = now + 24 * 60 * 60 * 1000L
        return repo.getProgramsWindow(channel, from, to)
    }
}
