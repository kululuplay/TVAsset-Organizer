/*
 * CatchupViewModel.kt
 * Drives the catch-up (timeshift) screen: the list of channels that advertise an
 * archive, and the past programs for the focused channel. Building the timeshift
 * URL is delegated to the repository (Xtream live only).
 */
package com.iptv.player.ui.catchup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.Program
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CatchupViewModel : ViewModel() {

    private val repo = ServiceLocator.repository

    val channels: Flow<List<Channel>> = repo.observeCatchupChannels()

    private val _programs = MutableStateFlow<List<Program>>(emptyList())
    val programs: StateFlow<List<Program>> = _programs

    /** Tracks the in-flight program load so rapid focus changes can't race. */
    private var loadJob: Job? = null

    fun selectChannel(channel: Channel) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _programs.value = repo.getCatchupPrograms(channel)
        }
    }

    suspend fun urlFor(channel: Channel, program: Program): String? =
        repo.buildCatchupUrl(channel, program)
}
