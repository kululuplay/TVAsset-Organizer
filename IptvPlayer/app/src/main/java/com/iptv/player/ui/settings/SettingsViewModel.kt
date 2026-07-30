/*
 * SettingsViewModel.kt
 * Exposes the persisted settings as Flows and wraps the suspend setters so the
 * Activity stays free of coroutine plumbing. Backed by ServiceLocator.settings.
 */
package com.iptv.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlaybackSelection
import com.iptv.player.data.model.PlaybackSelectionPolicy
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.StreamFormat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class SettingsViewModel : ViewModel() {

    private val settings = ServiceLocator.settings
    private val playbackWriteMutex = Mutex()

    /** One atomic UI snapshot; player and decoder can never render different revisions. */
    val playbackSelection: Flow<PlaybackSelection> = settings.playbackSelection
    val streamFormat: Flow<StreamFormat> = settings.streamFormat
    val bufferMode: Flow<BufferMode> = settings.bufferMode
    val audioPassthrough: Flow<Boolean> = settings.audioPassthrough
    val debugOverlay: Flow<Boolean> = settings.debugOverlay
    val showClock: Flow<Boolean> = settings.showClock
    val screensaverMinutes: Flow<Int> = settings.screensaverMinutes
    val languageTag: Flow<String> = settings.languageTag
    val lockAdult: Flow<Boolean> = settings.lockAdult
    val autoSyncEnabled: Flow<Boolean> = settings.autoSyncEnabled
    val autoSyncHours: Flow<Int> = settings.autoSyncHours

    /**
     * Queue an engine-axis change. The mutex preserves DPAD click order and each
     * intent re-reads the pair written by the previous intent, so a fast decoder
     * click cannot accidentally restore an older player value.
     */
    fun selectPlayerMode(mode: PlayerMode) = persistPlayback {
        updatePlaybackSelection { PlaybackSelectionPolicy.withPlayer(it, mode) }
    }

    /** Decoder-axis counterpart to [selectPlayerMode]. */
    fun selectDecoderMode(mode: DecoderMode) = persistPlayback {
        updatePlaybackSelection { PlaybackSelectionPolicy.withDecoder(it, mode) }
    }

    private suspend fun updatePlaybackSelection(
        transform: (PlaybackSelection) -> PlaybackSelection,
    ) {
        playbackWriteMutex.lock()
        try {
            val next = transform(settings.getPlaybackSelection())
            settings.setPlaybackSelection(next.player, next.decoder)
        } finally {
            playbackWriteMutex.unlock()
        }
    }

    /**
     * A DPAD selection must reach DataStore even if the user immediately presses
     * BACK and this ViewModel is cleared while the edit is in flight.
     */
    private fun persistPlayback(block: suspend () -> Unit) =
        viewModelScope.launch { withContext(NonCancellable) { block() } }

    fun setStreamFormat(format: StreamFormat) =
        viewModelScope.launch { settings.setStreamFormat(format) }

    fun setBufferMode(mode: BufferMode) = viewModelScope.launch { settings.setBufferMode(mode) }

    fun setAudioPassthrough(enabled: Boolean) =
        viewModelScope.launch { settings.setAudioPassthrough(enabled) }

    fun setDebugOverlay(enabled: Boolean) =
        viewModelScope.launch { settings.setDebugOverlay(enabled) }

    fun setShowClock(enabled: Boolean) = viewModelScope.launch { settings.setShowClock(enabled) }

    fun setScreensaverMinutes(minutes: Int) =
        viewModelScope.launch { settings.setScreensaverMinutes(minutes) }

    fun setLanguageTag(tag: String) = viewModelScope.launch { settings.setLanguageTag(tag) }

    fun setLockAdult(enabled: Boolean) = viewModelScope.launch { settings.setLockAdult(enabled) }

    fun setPin(pin: String) = viewModelScope.launch { settings.setPin(pin) }

    fun setAutoSyncEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setAutoSyncEnabled(enabled) }

    fun setAutoSyncHours(hours: Int) =
        viewModelScope.launch { settings.setAutoSyncHours(hours) }

    fun clearSource() = viewModelScope.launch { settings.clearSource() }
}
