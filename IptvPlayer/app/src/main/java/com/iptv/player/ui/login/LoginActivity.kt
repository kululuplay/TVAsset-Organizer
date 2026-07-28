/*
 * LoginActivity.kt
 * Launcher screen. On start, if a source is already saved it routes straight to
 * Home. Otherwise it shows a D-pad friendly login asking only for a username and
 * password; the Xtream server is fixed (http://kululu.live:8080) and the app
 * connects to it automatically. All controls are focusable and remote-usable.
 */
package com.iptv.player.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityLoginBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.hideSoftKeyboard
import com.iptv.player.ui.splash.SplashActivity
import com.iptv.player.util.AppError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[LoginViewModel::class.java]
    }
    private var routingToSplash = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()

        // Auto-route to Home if a source was previously saved.
        lifecycleScope.launch {
            if (ServiceLocator.settings.hasSource.first()) {
                goHome()
                return@launch
            }
            binding.inputUsername.post { binding.inputUsername.requestFocus() }
        }
    }

    private fun setupUi() {
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener { onConnect() }
        binding.inputUsername.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.inputPassword.requestFocus()
                true
            } else {
                false
            }
        }
        binding.inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onConnect()
                true
            } else {
                false
            }
        }
        binding.inputUsername.doAfterTextChanged { hideError() }
        binding.inputPassword.doAfterTextChanged { hideError() }

        observeState()
    }

    private fun onConnect() {
        if (viewModel.state.value is LoginViewModel.State.Loading) return
        val username = binding.inputUsername.text.toString().trim()
        // Preserve the password byte-for-byte; spaces can be part of a valid
        // provider credential even though usernames are safely trimmed.
        val password = binding.inputPassword.text.toString()
        if (username.isEmpty()) {
            showError(AppError.BAD_CREDENTIALS)
            binding.inputUsername.requestFocus()
            return
        }
        if (password.isEmpty()) {
            showError(AppError.BAD_CREDENTIALS)
            binding.inputPassword.requestFocus()
            return
        }
        binding.inputPassword.hideSoftKeyboard()
        binding.btnConnect.requestFocus()
        viewModel.loginXtream(
            serverUrl = FIXED_SERVER_URL,
            username = username,
            password = password,
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is LoginViewModel.State.Idle -> setLoading(false)
                    is LoginViewModel.State.Loading -> {
                        hideError()
                        setLoading(true)
                    }
                    is LoginViewModel.State.Success -> goHome()
                    is LoginViewModel.State.Error -> {
                        setLoading(false)
                        showError(state.error)
                        if (state.error == AppError.BAD_CREDENTIALS) {
                            binding.inputPassword.requestFocus()
                        } else {
                            binding.btnConnect.requestFocus()
                        }
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.inputUsername.isEnabled = !loading
        binding.inputPassword.isEnabled = !loading
        // Keep the CTA focused while connecting; making it disabled would eject
        // D-pad focus to an arbitrary view. Clickability still prevents duplicates.
        binding.btnConnect.isClickable = !loading
        binding.btnConnect.alpha = if (loading) 0.78f else 1f
        binding.btnConnect.setText(
            if (loading) R.string.connecting else R.string.action_connect
        )
    }

    private fun showError(error: AppError) {
        binding.loginError.setText(error.messageRes)
        binding.loginError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.loginError.visibility = View.GONE
    }

    private fun goHome() {
        if (routingToSplash || isFinishing) return
        routingToSplash = true
        // Route through the branded splash so the prefetch (live/EPG/series/movie
        // categories) runs before the dashboard, instead of going straight there.
        startActivity(Intent(this, SplashActivity::class.java))
        finish()
    }

    companion object {
        private const val FIXED_SERVER_URL = "http://kululu.live:8080"
    }
}
