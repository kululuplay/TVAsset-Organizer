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
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityLoginBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.splash.SplashActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[LoginViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auto-route to Home if a source was previously saved.
        lifecycleScope.launch {
            if (ServiceLocator.settings.hasSource.first()) {
                goHome()
                return@launch
            }
            setupUi()
        }
    }

    private fun setupUi() {
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener { onConnect() }
        binding.inputUsername.requestFocus()

        observeState()
    }

    private fun onConnect() {
        viewModel.loginXtream(
            serverUrl = FIXED_SERVER_URL,
            username = binding.inputUsername.text.toString(),
            password = binding.inputPassword.text.toString(),
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is LoginViewModel.State.Idle -> setLoading(false)
                    is LoginViewModel.State.Loading -> setLoading(true)
                    is LoginViewModel.State.Success -> goHome()
                    is LoginViewModel.State.Error -> {
                        setLoading(false)
                        Toast.makeText(
                            this@LoginActivity,
                            getString(state.error.messageRes),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !loading
    }

    private fun goHome() {
        // Route through the branded splash so the prefetch (live/EPG/series/movie
        // categories) runs before the dashboard, instead of going straight there.
        startActivity(Intent(this, SplashActivity::class.java))
        finish()
    }

    companion object {
        private const val FIXED_SERVER_URL = "http://kululu.live:8080"
    }
}
