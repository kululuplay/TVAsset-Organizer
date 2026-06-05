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
class HomeViewModel : ViewModel() {

    private val repo = ServiceLocator.repository

    companion object {
        const val CAT_FAVORITES = "__favorites__"
        const val CAT_RECENT = "__recent__"
    }

    /** Categories with Favorites + Recent pinned to the top. */
    val categories: StateFlow<List<Category>> =
        combine(
            repo.observeCategories(ContentType.LIVE),
            repo.observeFavorites()
        ) { cats, _ ->
            buildList {
                add(Category(CAT_FAVORITES, "Favorites", ContentType.LIVE))
                add(Category(CAT_RECENT, "Recently Watched", ContentType.LIVE))
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
                    null -> repo.observeChannels(ContentType.LIVE)
                    CAT_FAVORITES -> repo.observeFavorites()
                    CAT_RECENT -> repo.observeRecent()
                    else -> repo.observeChannelsByCategory(ContentType.LIVE, catId)
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
}
