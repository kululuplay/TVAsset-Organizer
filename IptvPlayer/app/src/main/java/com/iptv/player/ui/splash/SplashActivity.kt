/*
 * SplashActivity.kt
 * The app's branded entry point. Routes:
 *   !wizardDone        -> WizardActivity (first-run setup)
 *   wizardDone & no src -> LoginActivity (connect a service)
 *   else               -> DashboardActivity (straight to content)
 *
 * For logged-in users it shows a modern branded splash briefly while
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
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.lifecycle.lifecycleScope
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivitySplashBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.dashboard.DashboardActivity
import com.iptv.player.ui.login.LoginActivity
import com.iptv.player.ui.recovery.CrashRecoveryActivity
import com.iptv.player.ui.wizard.WizardActivity
import com.iptv.player.util.LaunchCrashGuard
import com.iptv.player.util.Logger
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
        startOrbitSpin()
    }

    companion object {
        /** Short delay for the routing-only (not logged in) path. */
        private const val ROUTE_DELAY_MS = 600L

        /** Minimum time for a readable entrance without making repeat launches drag. */
        private const val MIN_SPLASH_MS = 1_400L

        /** Hard cap before routing onward regardless of prefetch progress. */
        private const val MAX_SPLASH_MS = 3_000L
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
     * a gentle breathing pulse while the halo and orbit move subtly as the real
     * prefetch progress rail advances.
     */
    private fun playEntranceAnimations() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "—"
        binding.splashVersion.text = getString(com.iptv.player.R.string.dash_version, version)

        binding.splashProgressFill.post {
            binding.splashProgressFill.pivotX =
                if (binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    binding.splashProgressFill.width.toFloat()
                } else {
                    0f
                }
        }

        // Badge pops in with an overshoot.
        binding.logoBadge.apply {
            scaleX = 0.78f
            scaleY = 0.78f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(120).setDuration(620)
                .setInterpolator(OvershootInterpolator(1.25f))
                .start()
        }

        // Halo and the thin orbit fade in behind the brand monolith.
        binding.logoGlow.animate()
            .alpha(1f).setStartDelay(120).setDuration(720)
            .setInterpolator(DecelerateInterpolator()).start()
        binding.logoOrbit.animate()
            .alpha(1f).setStartDelay(180).setDuration(720)
            .setInterpolator(DecelerateInterpolator()).start()

        // Wordmark, tagline and the stage panel rise into place, staggered.
        riseIn(binding.appName, delay = 320)
        riseIn(binding.tagline, delay = 440)
        riseIn(binding.statusPanel, delay = 540)
        binding.splashVersion.animate()
            .alpha(1f).setStartDelay(620).setDuration(420).start()

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

    /** Slow orbit adds motion without the busy three-dot loader used previously. */
    private fun startOrbitSpin() {
        val orbit = ObjectAnimator.ofFloat(binding.logoOrbit, View.ROTATION, 45f, 405f).apply {
            duration = 12_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        animators += orbit
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /** Moves the progress rail to the prefetch stage currently visible to the user. */
    private fun renderStage(stage: SplashPrefetch.Stage) {
        binding.splashStatus.setText(stage.labelRes)
        val fraction = when (stage) {
            SplashPrefetch.Stage.STARTING -> 0.08f
            SplashPrefetch.Stage.LIVE -> 0.32f
            SplashPrefetch.Stage.EPG -> 0.58f
            SplashPrefetch.Stage.LIBRARY -> 0.82f
            SplashPrefetch.Stage.READY -> 1f
        }
        binding.splashProgressFill.animate()
            .scaleX(fraction)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(idleLoopsRunnable)
        listOf(
            binding.logoBadge,
            binding.logoGlow,
            binding.logoOrbit,
            binding.appName,
            binding.tagline,
            binding.statusPanel,
            binding.splashVersion,
            binding.splashProgressFill,
        ).forEach { it.animate().cancel() }
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

    /** Logged-in path: kick off prefetch and leave as soon as the short brand beat is done. */
    private suspend fun runBrandedSplash() {
        // If the previous launch armed the guard but never reached a stable home,
        // it crashed on this exact path. Don't crash-loop with no way to read the
        // trace: route to the recovery screen so the captured log can be shared.
        if (LaunchCrashGuard.previousLaunchCrashed(this)) {
            // Disarm the guard and bump the consecutive-crash streak; the recovery
            // screen reads the streak and self-heals (safe mode) past the threshold.
            val streak = LaunchCrashGuard.consumeCrashAndCountStreak(this)
            Logger.w("Splash", "Previous launch crashed before home (streak=$streak) — routing to recovery")
            go(CrashRecoveryActivity::class.java)
            return
        }
        // Arm the guard before any risky startup work; cleared only once the
        // Dashboard has drawn its first frame (DashboardActivity.onCreate).
        LaunchCrashGuard.markLaunchStarted(this)
        Logger.i("Splash", "Branded splash start")

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
                renderStage(stage)
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
