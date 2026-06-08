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

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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

    /** Idle loops kept so they can be cancelled when we route onward. */
    private val animators = mutableListOf<ValueAnimator>()

    /** Delayed kick-off for the idle loops; removed if we leave early. */
    private val idleLoopsRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        startBreathingPulse()
        startGlowPulse()
        startDotPulse(binding.dot1, 0)
        startDotPulse(binding.dot2, 160)
        startDotPulse(binding.dot3, 320)
    }

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

        playEntranceAnimations()
        lifecycleScope.launch { route() }
    }

    /**
     * Cinematic entrance: the halo + badge fade and scale in with a slight
     * overshoot, the wordmark and tagline rise into place, then the badge keeps
     * a gentle breathing pulse, the halo glows in and out, and the three dots
     * bounce in a staggered loop while content prefetches.
     */
    private fun playEntranceAnimations() {
        // Badge pops in with an overshoot.
        binding.logoBadge.apply {
            scaleX = 0.7f
            scaleY = 0.7f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(120).setDuration(620)
                .setInterpolator(OvershootInterpolator(1.6f))
                .start()
        }

        // Halo fades in behind the badge.
        binding.logoGlow.animate()
            .alpha(1f).setStartDelay(120).setDuration(720)
            .setInterpolator(DecelerateInterpolator()).start()

        // Wordmark and tagline rise into place, staggered.
        riseIn(binding.appName, delay = 320)
        riseIn(binding.tagline, delay = 440)
        binding.splashStatus.animate()
            .alpha(1f).setStartDelay(560).setDuration(500).start()

        // Idle loops once the entrance has settled.
        binding.root.postDelayed(idleLoopsRunnable, 760)
    }

    private fun riseIn(view: View, delay: Long) {
        view.translationY = dp(16f)
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(delay).setDuration(560)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Slow scale breathing on the badge, looping until we leave. */
    private fun startBreathingPulse() {
        val pulse = ValueAnimator.ofFloat(1f, 1.06f).apply {
            duration = 1600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                binding.logoBadge.scaleX = v
                binding.logoBadge.scaleY = v
            }
            start()
        }
        animators += pulse
    }

    /** Halo brightness drifts up and down out of phase with the badge. */
    private fun startGlowPulse() {
        val glow = ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 1900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { binding.logoGlow.alpha = it.animatedValue as Float }
            start()
        }
        animators += glow
    }

    /** A single dot bounces (scale + fade) on a staggered, looping schedule. */
    private fun startDotPulse(dot: View, startDelay: Long) {
        val anim = ObjectAnimator.ofPropertyValuesHolder(
            dot,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.35f, 1f)
        ).apply {
            duration = 520
            this.startDelay = startDelay
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        animators += anim
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDestroy() {
        binding.root.removeCallbacks(idleLoopsRunnable)
        animators.forEach { it.cancel() }
        animators.clear()
        super.onDestroy()
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
