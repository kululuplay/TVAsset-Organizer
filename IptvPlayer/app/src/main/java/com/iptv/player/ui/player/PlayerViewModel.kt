/*
 * PlayerViewModel.kt
 * Loads the channel to play, the current player mode, and lets the player list /
 * up-down zapping resolve the next/previous channel within the same list.
 */
package com.iptv.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.ui.home.HomeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    /** Flat list of channels used for up/down zapping. */
    private var playlist: List<Channel> = emptyList()
    private var currentIndex: Int = -1

    /** When true, zapping stays within the radio list (Radio folder playback). */
    var radioMode: Boolean = false

    /**
     * Category scope for up/down zapping. When set to [HomeViewModel.CAT_FAVORITES]
     * (the Favorites screen entry point), zapping cycles only through the favorite
     * live channels instead of the full live list. Null = full list.
     */
    var categoryId: String? = null

    suspend fun loadPlaylist() {
        playlist = if (categoryId == HomeViewModel.CAT_FAVORITES) {
            // Favorite live channels only: observeFavorites() INNER JOINs channels
            // (so movies/series are excluded already); drop radio like the Home rail.
            repo.observeFavorites().first().filterNot { it.isRadio }
        } else {
            repo.observeChannels(ContentType.LIVE, radio = radioMode).first()
        }
    }

    suspend fun playerMode(): PlayerMode = settings.playerMode.first()

    suspend fun decoderMode(): DecoderMode = settings.getDecoderMode()

    suspend fun streamFormat(): StreamFormat = settings.getStreamFormat()

    suspend fun bufferMode(): BufferMode = settings.getBufferMode()

    suspend fun audioPassthrough(): Boolean = settings.getAudioPassthrough()

    suspend fun resolveChannel(channelId: String): Channel? {
        if (playlist.isEmpty()) loadPlaylist()
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
