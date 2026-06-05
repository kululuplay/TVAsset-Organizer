/*
 * LoginActivity.kt
 * Launcher screen. On start, if a source is already saved it routes straight to
 * Home. Otherwise it shows a D-pad friendly login with two modes: Xtream Codes
 * and M3U URL. All controls are focusable and usable with a remote.
 */
package com.iptv.player.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityLoginBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.home.HomeActivity
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

        // Toggle between Xtream and M3U input groups.
        binding.tabXtream.setOnClickListener { showXtream(true) }
        binding.tabM3u.setOnClickListener { showXtream(false) }
        showXtream(true)
        binding.tabXtream.requestFocus()

        binding.btnConnect.setOnClickListener { onConnect() }

        observeState()
    }

    private fun showXtream(xtream: Boolean) {
        binding.groupXtream.visibility = if (xtream) View.VISIBLE else View.GONE
        binding.groupM3u.visibility = if (xtream) View.GONE else View.VISIBLE
        binding.tabXtream.isSelected = xtream
        binding.tabM3u.isSelected = !xtream
    }

    private fun onConnect() {
        if (binding.groupXtream.visibility == View.VISIBLE) {
            viewModel.loginXtream(
                serverUrl = binding.inputServer.text.toString(),
                username = binding.inputUsername.text.toString(),
                password = binding.inputPassword.text.toString()
            )
        } else {
            viewModel.loginM3u(binding.inputM3u.text.toString())
        }
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
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
