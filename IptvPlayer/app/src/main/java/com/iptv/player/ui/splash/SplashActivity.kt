/*
 * SplashActivity.kt
 * The app's branded entry point. Routes:
 *   !wizardDone        -> WizardActivity (first-run setup)
 *   wizardDone & no src -> LoginActivity (connect a service)
 *   else               -> DashboardActivity (straight to content)
 *
 * For logged-in users it shows a modern branded splash for ~5–6s while
 * SplashPrefetch warms Live channels, EPG, Series and Movie categories in the
 * background (on an app-lifetime scope so it survives this screen). The splash
 * never hangs: it always routes within the hard cap, and any unfinished prefetch
 * keeps running afterwards. Not-logged-in routing keeps the original short delay.
 */
package com.iptv.player.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import kotlinx.coroutines.withTimeoutOrNull

class SplashActivity : BaseActivity() {

    private lateinit var binding: ActivitySplashBinding

    companion object {
        /** Short delay for the routing-only (not logged in) path. */
        private const val ROUTE_DELAY_MS = 600L

        /** Minimum time the branded splash stays up for logged-in users. */
        private const val MIN_SPLASH_MS = 5_000L

        /** Hard cap before routing onward regardless of prefetch progress. */
        private const val MAX_SPLASH_MS = 6_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch { route() }
    }

    private suspend fun route() {
        val settings = ServiceLocator.settings
        when {
            !settings.wizardDone.first() -> {
                delay(ROUTE_DELAY_MS)
                go(WizardActivity::class.java)
            }
            !settings.hasSource.first() -> {
                delay(ROUTE_DELAY_MS)
                go(LoginActivity::class.java)
            }
            else -> runBrandedSplash()
        }
    }

    /** Logged-in path: kick off the prefetch and stay on screen for ~5–6s. */
    private suspend fun runBrandedSplash() {
        // Backfill a profile for accounts that connected before profiles existed.
        ServiceLocator.repository.backfillProfileFromSource()

        val config = ServiceLocator.settings.getSourceConfig()
        if (config == null) {
            // Shouldn't happen (hasSource was true), but never hang: route on.
            go(DashboardActivity::class.java)
            return
        }

        val started = SystemClock.elapsedRealtime()
        SplashPrefetch.start(config)

        // Reflect prefetch progress in the status line while the splash is up.
        val statusJob = lifecycleScope.launch {
            SplashPrefetch.stage.collect { stage ->
                binding.splashStatus.setText(stage.labelRes)
            }
        }

        // Keep the brand on screen for a minimum time, then wait (up to the hard
        // cap) for the essential refreshes — whichever is later — before routing.
        delay(MIN_SPLASH_MS)
        val remaining = MAX_SPLASH_MS - (SystemClock.elapsedRealtime() - started)
        if (remaining > 0) {
            withTimeoutOrNull(remaining) {
                SplashPrefetch.essentialDone.first { it }
            }
        }

        statusJob.cancel()
        go(DashboardActivity::class.java)
    }

    private fun go(target: Class<*>) {
        startActivity(Intent(this, target))
        finish()
    }
}
