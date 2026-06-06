/*
 * PlayerViewModel.kt
 * Loads the channel to play, the current player mode, and lets the player list /
 * up-down zapping resolve the next/previous channel within the same list.
 */
package com.iptv.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.ui.home.HomeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    /** Flat list of channels used for up/down zapping (scoped to the source category). */
    private var playlist: List<Channel> = emptyList()
    private var currentIndex: Int = -1

    /**
     * Loads the zapping playlist for the category the player was opened from, so
     * up/down stays within that filtered list (e.g. Favorites) instead of jumping
     * to the next channel in the global list. A null category means "all live".
     */
    suspend fun loadPlaylist(categoryId: String?) {
        playlist = when (categoryId) {
            null -> repo.observeChannels(ContentType.LIVE)
            HomeViewModel.CAT_FAVORITES -> repo.observeFavorites()
            HomeViewModel.CAT_RECENT -> repo.observeRecent()
            else -> repo.observeChannelsByCategory(ContentType.LIVE, categoryId)
        }.first()
    }

    suspend fun playerMode(): PlayerMode = settings.playerMode.first()

    suspend fun resolveChannel(channelId: String, categoryId: String?): Channel? {
        if (playlist.isEmpty()) loadPlaylist(categoryId)
        currentIndex = playlist.indexOfFirst { it.id == channelId }
        return repo.getChannel(channelId)?.also { markWatched(channelId) }
    }

    fun next(): Channel? = stepBy(+1)
    fun previous(): Channel? = stepBy(-1)

    private fun stepBy(delta: Int): Channel? {
        if (playlist.isEmpty()) return null
        currentIndex = (currentIndex + delta + playlist.size) % playlist.size
        val channel = playlist[currentIndex]
        markWatched(channel.id)
        return channel
    }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { repo.toggleFavorite(channelId) }
    }

    private fun markWatched(channelId: String) {
        viewModelScope.launch {
            repo.markWatched(channelId)
            settings.setLastChannel(channelId)
        }
    }
}
