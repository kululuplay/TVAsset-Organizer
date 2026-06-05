/*
 * ChannelManagerViewModel.kt
 * Backs the channel editor: exposes all live channels with their hidden state and
 * persists hide toggles + custom ordering through the repository. Overrides
 * survive playlist refreshes (stored in a separate table keyed by channel id).
 */
package com.iptv.player.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.ManagedChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class ChannelManagerViewModel : ViewModel() {

    private val repo = ServiceLocator.repository

    val channels: Flow<List<ManagedChannel>> = repo.observeManagedChannels(ContentType.LIVE)

    fun setHidden(channelId: String, hidden: Boolean) {
        viewModelScope.launch { repo.setChannelHidden(channelId, hidden) }
    }

    fun applyOrder(orderedIds: List<String>) {
        viewModelScope.launch { repo.applyChannelOrder(orderedIds) }
    }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { repo.toggleFavorite(channelId) }
    }
}
