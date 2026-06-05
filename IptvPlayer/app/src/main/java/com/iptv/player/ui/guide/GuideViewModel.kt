/*
 * GuideViewModel.kt
 * Drives the TV guide. The left column observes all live channels reactively.
 * Programs are loaded lazily for the *focused* channel only (now-30m .. now+3h)
 * to keep memory low on cheap sticks. Also handles the "Refresh guide" action,
 * exposing a small refresh state the Activity turns into progress + Toast.
 */
package com.iptv.player.ui.guide

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.Program
import com.iptv.player.util.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    /** Window around "now" used by the timeline. */
    private val pastMs = 30 * 60_000L          // 30 minutes
    private val futureMs = 3 * 60 * 60_000L    // 3 hours

    /** All live channels for the left column. */
    val channels: StateFlow<List<Channel>> =
        repo.observeChannels(ContentType.LIVE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selected = MutableStateFlow<Channel?>(null)

    /** Bumped to force the focused channel's program window to reload. */
    private val reloadTick = MutableStateFlow(0)

    fun selectChannel(channel: Channel) {
        if (selected.value?.id != channel.id) selected.value = channel
    }

    /** Programs for the currently focused channel only (loaded on demand). */
    val programs: StateFlow<List<Program>> =
        combine(selected, reloadTick) { ch, _ -> ch }
            .flatMapLatest { ch ->
                if (ch == null) {
                    flowOf(emptyList())
                } else {
                    flow {
                        val now = System.currentTimeMillis()
                        emit(repo.getProgramsWindow(ch, now - pastMs, now + futureMs))
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- Refresh guide ---------------------------------------------------

    sealed class RefreshState {
        object Idle : RefreshState()
        object Loading : RefreshState()
        data class Done(val count: Int) : RefreshState()
        data class Failed(@StringRes val messageRes: Int) : RefreshState()
    }

    private val _refresh = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refresh: StateFlow<RefreshState> = _refresh

    fun refreshGuide() {
        if (_refresh.value is RefreshState.Loading) return
        viewModelScope.launch {
            val config = settings.getSourceConfig()
            if (config == null) {
                _refresh.value = RefreshState.Failed(R.string.guide_no_source)
                return@launch
            }
            _refresh.value = RefreshState.Loading
            _refresh.value = when (val r = repo.refreshEpg(config)) {
                is Outcome.Success -> RefreshState.Done(r.data)
                is Outcome.Failure -> RefreshState.Failed(r.error.messageRes)
            }
            // Re-load the focused channel's window so new data shows immediately.
            reloadTick.value += 1
        }
    }

    /** Reset after the Activity has shown the Toast. */
    fun consumeRefresh() {
        _refresh.value = RefreshState.Idle
    }
}
