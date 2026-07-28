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
     * Browse scope for up/down zapping. Real category ids stay inside that
     * category; Favorites/Recent stay inside their synthetic list; null uses all
     * visible channels. Hidden channels/categories are excluded in every case.
     */
    var categoryId: String? = null

    suspend fun loadPlaylist() {
        val hiddenCategories = settings.hiddenCategories(ContentType.LIVE).first()
        // observeChannels already excludes channel-level hidden overrides. Apply
        // category visibility once, then intersect synthetic lists with this set
        // so CH+/- cannot jump into content hidden by Content Manager.
        val visible = repo.observeChannels(ContentType.LIVE, radio = radioMode)
            .first()
            .filter { it.categoryId !in hiddenCategories }
        val visibleIds = visible.mapTo(hashSetOf()) { it.id }

        playlist = when (val scope = categoryId) {
            HomeViewModel.CAT_FAVORITES -> repo.observeFavorites()
                .first()
                .filter { it.id in visibleIds }
            HomeViewModel.CAT_RECENT -> repo.observeRecent()
                .first()
                .filter { it.id in visibleIds }
            null -> visible
            else -> visible.filter { it.categoryId == scope }
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
        return repo.getChannel(channelId)
    }

    fun next(): Channel? = stepBy(+1)
    fun previous(): Channel? = stepBy(-1)

    private fun stepBy(delta: Int): Channel? {
        if (playlist.isEmpty()) return null
        currentIndex = (currentIndex + delta + playlist.size) % playlist.size
        return playlist[currentIndex]
    }

    /** Restore the CH+/- cursor after a parental prompt is cancelled. */
    fun selectChannel(channelId: String) {
        val index = playlist.indexOfFirst { it.id == channelId }
        if (index >= 0) currentIndex = index
    }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { repo.toggleFavorite(channelId) }
    }

    fun markWatched(channelId: String) {
        viewModelScope.launch {
            repo.markWatched(channelId)
            settings.setLastChannel(channelId)
        }
    }
}
