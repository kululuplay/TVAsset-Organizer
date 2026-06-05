/*
 * SplashActivity.kt
 * The app's branded entry point (intended as the new LAUNCHER — see report).
 * Shows a short splash, then routes:
 *   !wizardDone        -> WizardActivity (first-run setup)
 *   wizardDone & no src -> LoginActivity (connect a service)
 *   else               -> HomeActivity (straight to content)
 */
package com.iptv.player.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivitySplashBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.dashboard.DashboardActivity
import com.iptv.player.ui.login.LoginActivity
import com.iptv.player.ui.wizard.WizardActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SplashActivity : BaseActivity() {

    private lateinit var binding: ActivitySplashBinding

    companion object {
        private const val MIN_SPLASH_MS = 600L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            // Keep the brand on screen briefly so it doesn't flash.
            delay(MIN_SPLASH_MS)
            route()
        }
    }

    private suspend fun route() {
        val settings = ServiceLocator.settings
        val target = when {
            !settings.wizardDone.first() -> WizardActivity::class.java
            !settings.hasSource.first() -> LoginActivity::class.java
            else -> {
                // Backfill a profile for accounts that connected before profiles were
                // auto-created, so the Profiles screen isn't empty for existing users.
                ServiceLocator.repository.backfillProfileFromSource()
                DashboardActivity::class.java
            }
        }
        startActivity(Intent(this, target))
        finish()
    }
}
