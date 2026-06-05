/*
 * SettingsViewModel.kt
 * Exposes the persisted settings as Flows and wraps the suspend setters so the
 * Activity stays free of coroutine plumbing. Backed by ServiceLocator.settings.
 */
package com.iptv.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.PlayerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val settings = ServiceLocator.settings

    val playerMode: Flow<PlayerMode> = settings.playerMode
    val showClock: Flow<Boolean> = settings.showClock
    val screensaverMinutes: Flow<Int> = settings.screensaverMinutes
    val languageTag: Flow<String> = settings.languageTag
    val tmdbKey: Flow<String> = settings.tmdbKey
    val lockAdult: Flow<Boolean> = settings.lockAdult
    val autoSyncEnabled: Flow<Boolean> = settings.autoSyncEnabled
    val autoSyncHours: Flow<Int> = settings.autoSyncHours

    fun setPlayerMode(mode: PlayerMode) = viewModelScope.launch { settings.setPlayerMode(mode) }

    fun setShowClock(enabled: Boolean) = viewModelScope.launch { settings.setShowClock(enabled) }

    fun setScreensaverMinutes(minutes: Int) =
        viewModelScope.launch { settings.setScreensaverMinutes(minutes) }

    fun setLanguageTag(tag: String) = viewModelScope.launch { settings.setLanguageTag(tag) }

    fun setTmdbKey(key: String) = viewModelScope.launch { settings.setTmdbKey(key) }

    fun setLockAdult(enabled: Boolean) = viewModelScope.launch { settings.setLockAdult(enabled) }

    fun setPin(pin: String) = viewModelScope.launch { settings.setPin(pin) }

    fun setAutoSyncEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setAutoSyncEnabled(enabled) }

    fun setAutoSyncHours(hours: Int) =
        viewModelScope.launch { settings.setAutoSyncHours(hours) }

    fun clearSource() = viewModelScope.launch { settings.clearSource() }
}
