package com.iptv.player.playback.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.iptv.player.cast.ProviderConnectionSafety
import com.iptv.player.player.DiagnosticSwitches
import com.iptv.player.player.VlcOps
import com.iptv.player.playback.core.PlaybackResourceGovernor
import com.iptv.player.playback.core.StaleProcessTracker
import com.iptv.player.ui.login.LoginActivity
import com.iptv.player.ui.recovery.CrashRecoveryActivity
import com.iptv.player.ui.splash.SplashActivity
import com.iptv.player.ui.wizard.WizardActivity
import com.iptv.player.util.PlaybackLog
import com.iptv.player.work.backgroundSyncBusy
import java.util.Locale

/**
 * Restarts the app process cleanly when the user comes back after a long
 * absence, the in-app equivalent of the power cycle customers used to do.
 *
 * Field report (28 Sep 2026, 1.5.98, Fire TV class sticks): after 15-16 hours
 * channels no longer opened and stayed on "Wird geladen…" until the stick was
 * unplugged, while other players worked with the same account. Server logs over
 * 21 days showed no failed or rejected requests from those devices, i.e. the
 * stuck app never reached the network: the state lived inside the long-running
 * process. Every screen keeps the screen on, so a stick left on an idle screen
 * or the in-app screensaver never sleeps and the process can live for days.
 *
 * The recycle reuses the controlled playback process recovery (separate
 * recovery process, verified kill, clean-stop marker, relaunch), so no login or
 * setting is lost. Players relaunch their own screen; every other screen starts
 * at the splash. Before the recycle a compact state snapshot goes to stability
 * telemetry (type `stale_process_recycle`) to find the real stuck state.
 */
object StaleProcessGuard {

    const val DEFAULT_THRESHOLD_MS = 4L * 60L * 60L * 1000L
    const val TELEMETRY_TYPE = "stale_process_recycle"
    private const val TAG = "StaleProcess"

    @Volatile
    private var appContext: Context? = null
    private val processStartMs = SystemClock.elapsedRealtime()
    private val tracker = StaleProcessTracker(::thresholdMs)

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun thresholdMs(): Long =
        appContext?.let(DiagnosticSwitches::staleRecycleThresholdMs) ?: DEFAULT_THRESHOLD_MS

    /** First started Activity after none. Main thread. */
    fun onForeground() = tracker.onForeground(SystemClock.elapsedRealtime())

    /** Last started Activity stopped. Main thread. */
    fun onBackground() = tracker.onBackground(SystemClock.elapsedRealtime())

    /** Returns true when a recycle was launched from this resumed screen. */
    fun onResumed(activity: Activity): Boolean {
        // A launcher return can open a new splash on top of the old process. The
        // splash routes onward at once, so judge the absence on the next screen.
        if (activity is SplashActivity && !DiagnosticSwitches.staleRecycleAnyScreen(activity)) {
            return false
        }
        val away = tracker.onResumed(SystemClock.elapsedRealtime()) ?: return false
        return recycle(activity, "background_return", away)
    }

    /**
     * Called for every input event before the screen sees it. [isPress] is true
     * only for a key down without repeat or a touch down. Returns true when the
     * event must not reach the (stale) UI.
     */
    fun onInput(activity: Activity, isPress: Boolean): Boolean {
        if (!isPress) return tracker.recycleRequested
        val result = tracker.onInput(
            nowMs = SystemClock.elapsedRealtime(),
            healthyPlayback = PlaybackQoeRuntime.isAnySessionPlaying(),
        )
        return when (result.decision) {
            StaleProcessTracker.InputDecision.DELIVER -> false
            StaleProcessTracker.InputDecision.SWALLOW -> true
            StaleProcessTracker.InputDecision.RECYCLE -> recycle(activity, "idle_input", result.idleMs)
        }
    }

    private fun recycle(activity: Activity, trigger: String, quietMs: Long): Boolean {
        if (isFreshStartScreen(activity) && !DiagnosticSwitches.staleRecycleAnyScreen(activity)) {
            // Splash, login and recovery screens already are a fresh start.
            tracker.recycleNotLaunched(SystemClock.elapsedRealtime())
            return false
        }
        val safety = ProviderConnectionSafety.snapshot()
        if (safety.remoteUncertain || safety.activeRemoteOwnerCount > 0) {
            // A Cast receiver may own playback; killing the process would forget it.
            PlaybackLog.log(activity, TAG, "stale process kept: Cast owns playback ($trigger)")
            tracker.recycleNotLaunched(SystemClock.elapsedRealtime())
            return false
        }
        val detail = snapshot(activity, trigger, quietMs, safety)
        tracker.markRecycleRequested()
        val target = (activity as? PlaybackProcessRecoveryTargetProvider)
            ?.playbackProcessRecoveryIntent()
            ?: launcherIntent(activity)
        val launched = PlaybackProcessRecovery.requestStaleRecycle(activity, detail, target) {
            // Still alive: the recycle failed, give the remote back to the user.
            tracker.recycleNotLaunched(SystemClock.elapsedRealtime())
        }
        if (!launched) {
            PlaybackLog.log(activity, TAG, "stale process recycle not launched: $detail")
            tracker.recycleNotLaunched(SystemClock.elapsedRealtime())
        }
        return launched
    }

    /**
     * The exact intent the TV launcher uses, so the relaunched task's base intent
     * matches it and a later launcher press resumes the task instead of stacking
     * a new splash on top.
     */
    private fun launcherIntent(activity: Activity): Intent =
        activity.packageManager.getLeanbackLaunchIntentForPackage(activity.packageName)
            ?: activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?: Intent(activity, SplashActivity::class.java)

    private fun isFreshStartScreen(activity: Activity): Boolean =
        activity is SplashActivity ||
            activity is WizardActivity ||
            activity is LoginActivity ||
            activity is CrashRecoveryActivity

    private fun snapshot(
        activity: Activity,
        trigger: String,
        quietMs: Long,
        safety: ProviderConnectionSafety.Snapshot,
    ): String {
        val now = SystemClock.elapsedRealtime()
        return buildString {
            append("trigger=").append(trigger)
            append(" quiet_min=").append(quietMs / 60_000L)
            append(" uptime_h=")
                .append(String.format(Locale.US, "%.1f", (now - processStartMs) / 3_600_000.0))
            append(" screen=").append(activity.javaClass.simpleName)
            append(" gate=").append(if (safety.newConnectionAllowed) "open" else "blocked")
            append(" local_pending=").append(safety.localPendingCount)
            append(" local_recovery=").append(safety.localProcessRecoveryRequired)
            append(" owners=").append(PlaybackResourceGovernor.activeOwnerCount.value)
            append(" vlc=").append(runCatching { VlcOps.debugSnapshot() }.getOrDefault("?"))
            append(" qoe=").append(PlaybackQoeRuntime.stateSummary())
            append(" sync_busy=").append(runCatching { backgroundSyncBusy() }.getOrDefault(false))
        }
    }
}
