/*
 * LoginActivity.kt
 * TV-first secure sign-in. The provider endpoint remains fixed to HTTPS while
 * username/password are the only customer inputs. Errors show a safe support
 * code and actionable guidance without exposing URLs, response bodies or secrets.
 */
package com.iptv.player.ui.login

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
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
import com.iptv.player.util.KululuEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[LoginViewModel::class.java]
    }
    private var routingToSplash = false
    private var passwordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()

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
        binding.passwordToggle.setOnClickListener { togglePasswordVisibility() }
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

        updatePasswordToggle()
        observeState()
        animateEntrance()
    }

    private fun onConnect() {
        if (viewModel.state.value is LoginViewModel.State.Loading) return
        val username = binding.inputUsername.text.toString().trim()
        // Provider passwords are opaque values; intentional spaces must survive.
        val password = binding.inputPassword.text.toString()
        if (username.isEmpty()) {
            showError(AppError.MISSING_CREDENTIALS)
            binding.inputUsername.requestFocus()
            return
        }
        if (password.isEmpty()) {
            showError(AppError.MISSING_CREDENTIALS)
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
                        showError(state.error, state.httpStatus)
                        when (state.error) {
                            AppError.BAD_CREDENTIALS,
                            AppError.MISSING_CREDENTIALS,
                            -> binding.inputPassword.requestFocus()
                            else -> binding.btnConnect.requestFocus()
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
        binding.passwordToggle.isClickable = !loading
        // Keep the focused CTA enabled so OEM focus engines do not eject focus;
        // clickability plus the ViewModel guard prevents duplicate submissions.
        binding.btnConnect.isClickable = !loading
        binding.btnConnect.alpha = if (loading) 0.78f else 1f
        binding.btnConnect.setText(if (loading) R.string.connecting else R.string.action_connect)
    }

    private fun togglePasswordVisibility() {
        passwordVisible = !passwordVisible
        val selection = binding.inputPassword.selectionStart.coerceAtLeast(0)
        binding.inputPassword.transformationMethod = if (passwordVisible) {
            HideReturnsTransformationMethod.getInstance()
        } else {
            PasswordTransformationMethod.getInstance()
        }
        binding.inputPassword.setSelection(selection.coerceAtMost(binding.inputPassword.length()))
        updatePasswordToggle()
    }

    private fun updatePasswordToggle() {
        val textRes = if (passwordVisible) {
            R.string.login_hide_password
        } else {
            R.string.login_show_password
        }
        binding.passwordToggle.setText(textRes)
        binding.passwordToggle.contentDescription = getString(textRes)
    }

    private fun showError(error: AppError, httpStatus: Int? = null) {
        binding.loginErrorCode.text = httpStatus?.let {
            getString(R.string.login_error_code_http, it)
        } ?: supportCode(error)
        binding.loginErrorTitle.setText(error.messageRes)
        binding.loginErrorHelp.setText(helpText(error))
        binding.loginErrorCard.visibility = View.VISIBLE

        val announcement = listOf(
            binding.loginErrorCode.text,
            binding.loginErrorTitle.text,
            binding.loginErrorHelp.text,
        ).joinToString(". ")
        binding.loginErrorCard.contentDescription = announcement
        binding.loginErrorCard.announceForAccessibility(announcement)
    }

    private fun supportCode(error: AppError): String = when (error) {
        AppError.MISSING_CREDENTIALS -> "AUTH-INPUT"
        AppError.BAD_CREDENTIALS -> "AUTH-INVALID"
        AppError.ACCESS_DENIED -> "AUTH-DENIED"
        AppError.ACCOUNT_DISABLED -> "ACCOUNT-LOCKED"
        AppError.SUBSCRIPTION_EXPIRED -> "ACCOUNT-EXPIRED"
        AppError.ACCOUNT_IN_USE -> "ACCOUNT-IN-USE"
        AppError.SERVICE_NOT_FOUND -> "SERVICE-NOT-FOUND"
        AppError.REQUEST_TIMEOUT -> "NET-TIMEOUT"
        AppError.TOO_MANY_REQUESTS -> "RATE-LIMIT"
        AppError.SERVER_UNAVAILABLE -> "SERVICE-UNAVAILABLE"
        AppError.SECURE_CONNECTION_FAILED -> "NET-TLS"
        AppError.NO_INTERNET -> "NET-OFFLINE"
        AppError.CANNOT_CONNECT -> "NET-CONNECTION"
        AppError.EMPTY_PLAYLIST -> "CONTENT-EMPTY"
        AppError.STREAM_NOT_RESPONDING -> "STREAM-NO-RESPONSE"
        AppError.UNKNOWN -> "LOGIN-UNKNOWN"
    }

    private fun helpText(error: AppError): Int = when (error) {
        AppError.MISSING_CREDENTIALS -> R.string.login_error_help_input
        AppError.BAD_CREDENTIALS -> R.string.login_error_help_credentials
        AppError.ACCESS_DENIED,
        AppError.ACCOUNT_DISABLED,
        -> R.string.login_error_help_access
        AppError.SUBSCRIPTION_EXPIRED -> R.string.login_error_help_subscription
        AppError.SERVICE_NOT_FOUND -> R.string.login_error_help_service
        AppError.REQUEST_TIMEOUT -> R.string.login_error_help_timeout
        AppError.TOO_MANY_REQUESTS -> R.string.login_error_help_rate_limit
        AppError.SERVER_UNAVAILABLE -> R.string.login_error_help_server
        AppError.SECURE_CONNECTION_FAILED -> R.string.login_error_help_tls
        AppError.NO_INTERNET,
        AppError.CANNOT_CONNECT,
        -> R.string.login_error_help_network
        AppError.ACCOUNT_IN_USE -> R.string.login_error_help_account_in_use
        AppError.STREAM_NOT_RESPONDING,
        AppError.EMPTY_PLAYLIST,
        AppError.UNKNOWN,
        -> R.string.login_error_help_generic
    }

    private fun hideError() {
        binding.loginErrorCard.visibility = View.GONE
        binding.loginErrorCard.contentDescription = null
    }

    private fun animateEntrance() {
        val distance = resources.displayMetrics.density * 24f
        val direction = if (
            resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        ) -1f else 1f
        binding.brandHero.apply {
            alpha = 0f
            translationX = -distance * direction
            animate().alpha(1f).translationX(0f).setDuration(280L).start()
        }
        binding.loginCard.apply {
            alpha = 0f
            translationX = distance * direction
            animate().alpha(1f).translationX(0f).setStartDelay(60L).setDuration(300L).start()
        }
    }

    private fun goHome() {
        if (routingToSplash || isFinishing) return
        routingToSplash = true
        startActivity(Intent(this, SplashActivity::class.java))
        finish()
    }

    companion object {
        private const val FIXED_SERVER_URL = KululuEndpoint.HTTPS_SERVER_URL
    }
}
