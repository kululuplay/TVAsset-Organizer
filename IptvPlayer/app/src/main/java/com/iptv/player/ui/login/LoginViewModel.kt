/*
 * LoginViewModel.kt
 * Backs the login screen. Validates + tests the chosen source (Xtream or M3U),
 * saves it on success, then triggers the first channel refresh.
 */
package com.iptv.player.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.util.AppError
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {

    sealed class State {
        data object Idle : State()
        data object Loading : State()
        data object Success : State()
        data class Error(val error: AppError) : State()
    }

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun loginXtream(serverUrl: String, username: String, password: String) {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            _state.value = State.Error(AppError.BAD_CREDENTIALS)
            return
        }
        val config = SourceConfig(
            type = SourceType.XTREAM,
            serverUrl = normalizeUrl(serverUrl),
            username = username.trim(),
            password = password.trim()
        )
        submit(config)
    }

    fun loginM3u(m3uUrl: String) {
        if (m3uUrl.isBlank()) {
            _state.value = State.Error(AppError.EMPTY_PLAYLIST)
            return
        }
        val config = SourceConfig(
            type = SourceType.M3U_URL,
            m3uUrl = m3uUrl.trim()
        )
        submit(config)
    }

    private fun submit(config: SourceConfig) {
        _state.value = State.Loading
        viewModelScope.launch {
            // Final safety net: nothing here may escape and crash the app. The repo
            // already maps known failures to AppError, but saveSource and any other
            // unexpected Throwable (e.g. OutOfMemoryError on huge accounts) are caught
            // here so the user always sees an error instead of the app closing.
            try {
                when (val test = repo.testSource(config)) {
                    is Outcome.Failure -> _state.value = State.Error(test.error)
                    is Outcome.Success -> {
                        settings.saveSource(config)
                        // Mirror the logged-in account into the Profiles list (reusing an
                        // existing match so re-logins don't duplicate) and make it active,
                        // so the Profiles screen isn't empty after a successful login.
                        settings.setActiveProfileId(repo.ensureProfile(config))
                        when (val refresh = repo.refreshLive(config)) {
                            is Outcome.Failure -> _state.value = State.Error(refresh.error)
                            is Outcome.Success -> _state.value = State.Success
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e // never swallow coroutine cancellation
                _state.value = State.Error(AppError.UNKNOWN)
            }
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }
}
