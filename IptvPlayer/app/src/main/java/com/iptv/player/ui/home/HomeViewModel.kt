/*
 * HomeViewModel.kt
 * Drives the 3-pane TV home: categories (left), channels (center), info (right).
 * Categories include synthetic "Favorites" and "Recently Watched" entries on top.
 * Channel list reactively follows the selected category.
 */
package com.iptv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.NowNext
import com.iptv.player.data.model.Program
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val radio: Boolean = false) : ViewModel() {

    private val repo = ServiceLocator.repository

    companion object {
        const val CAT_FAVORITES = "__favorites__"
        const val CAT_RECENT = "__recent__"
    }

    /** Builds a [HomeViewModel] in either Live TV ([radio] = false) or Radio mode. */
    class Factory(private val radio: Boolean) : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(radio) as T
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
            repo.observeFavorites()
        ) { cats, favs ->
            buildList {
                // Radio mode is a clean folder of stations only — the synthetic
                // Favorites/Recent rows stay on the Live TV page.
                if (!radio) {
                    add(Category(CAT_FAVORITES, "Favorites", ContentType.LIVE, count = favs.size))
                    add(Category(CAT_RECENT, "Recently Watched", ContentType.LIVE))
                }
                addAll(cats)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedCategory = MutableStateFlow<String?>(null)

    fun selectCategory(categoryId: String) {
        selectedCategory.value = categoryId
    }

    /** Channels for the currently selected category (or favorites/recent). */
    val channels: StateFlow<List<Channel>> =
        combine(
            selectedCategory,
            repo.observeFavorites()
        ) { catId, favIds -> catId to favIds.map { it.id }.toSet() }
            .flatMapLatest { (catId, favSet) ->
                val source: Flow<List<Channel>> = when (catId) {
                    // Pre-selection startup state: emit nothing rather than loading the
                    // entire LIVE catalog, which the initial category selection replaces
                    // a moment later anyway (avoids a big throwaway list on big catalogs).
                    null -> flowOf(emptyList())
                    // Favorites/Recent are cross-section; keep radios off the Live
                    // TV page (these synthetic rows only ever show in live mode).
                    CAT_FAVORITES -> repo.observeFavorites().map { it.filterNot { c -> c.isRadio } }
                    CAT_RECENT -> repo.observeRecent().map { it.filterNot { c -> c.isRadio } }
                    else -> repo.observeChannelsByCategory(ContentType.LIVE, catId, radio = radio)
                }
                // Annotate each channel with its current favorite state.
                source.map { list -> list.map { it.copy(isFavorite = favSet.contains(it.id)) } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
