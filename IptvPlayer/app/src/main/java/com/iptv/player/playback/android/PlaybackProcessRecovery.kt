package com.iptv.player.playback.android

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StrictMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.iptv.player.cast.ProviderConnectionSafety
import com.iptv.player.ui.recovery.PlaybackProcessRecoveryActivity
import com.iptv.player.util.AbnormalExitDetector
import com.iptv.player.util.PlaybackLog
import com.iptv.player.util.StabilityTelemetry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Framework-free rate-limit policy for the rare native-process recycle. */
internal object PlaybackProcessRecoveryPolicy {
    const val MIN_RECOVERY_INTERVAL_MS = 2 * 60 * 1000L

    fun mayRecover(nowMs: Long, lastRecoveryMs: Long): Boolean =
        lastRecoveryMs <= 0L ||
            nowMs < lastRecoveryMs ||
            nowMs - lastRecoveryMs >= MIN_RECOVERY_INTERVAL_MS
}

/** Supplies current (post-zap/post-auto-next) state to a clean process relaunch. */
interface PlaybackProcessRecoveryTargetProvider {
    fun playbackProcessRecoveryIntent(): Intent
}

/**
 * Recycles only the app's main process after an unkillable vendor JNI teardown.
 * Kernel process death is the only definitive way to close that process's native
 * sockets/codecs; a separate recovery Activity survives long enough to relaunch
 * the exact live/VOD screen. Remote Cast uncertainty always disables this path.
 */
internal object PlaybackProcessRecovery {
    const val EXTRA_RECOVERED_PLAYBACK =
        "com.iptv.player.extra.RECOVERED_PLAYBACK_PROCESS"

    private const val RECOVERY_PROCESS_SUFFIX = ":playback_recovery"
    private const val PREFS = "playback_process_recovery"
    private const val KEY_LAST_RECOVERY_MS = "last_recovery_ms"

    private val requestInFlight = AtomicBoolean(false)

    fun isRecoveryProcess(context: Context): Boolean =
        currentProcessName(context)?.endsWith(RECOVERY_PROCESS_SUFFIX) == true

    /** Returns true when recovery was launched or is already being launched. */
    fun requestIfRequired(
        activity: Activity,
        reason: String,
        resumeIntent: Intent? = null,
    ): Boolean {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        if (
            activity.isFinishing ||
            activity.isDestroyed ||
            lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true
        ) {
            // Background timeout callbacks leave only the provider recovery bit;
            // the next foreground Activity callback launches the recovery UI.
            return false
        }
        val safety = ProviderConnectionSafety.snapshot()
        if (!safety.canRecoverLocalProcess) return false
        if (!requestInFlight.compareAndSet(false, true)) return true

        val now = System.currentTimeMillis()
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_RECOVERY_MS, 0L)
        if (!PlaybackProcessRecoveryPolicy.mayRecover(now, last)) {
            requestInFlight.set(false)
            PlaybackLog.log(
                activity,
                "ProcessRecovery",
                "automatic playback process recovery rate-limited",
            )
            return false
        }

        val mainStartTicks = readProcessStartTicks(Process.myPid())
        if (mainStartTicks == null) {
            requestInFlight.set(false)
            return false
        }

        val target = Intent(resumeIntent ?: activity.intent).apply {
            component = ComponentName(activity, activity.javaClass)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_RECOVERED_PLAYBACK, true)
            clipData = null
            selector = null
        }
        val recovery = Intent(activity, PlaybackProcessRecoveryActivity::class.java).apply {
            putExtra(PlaybackProcessRecoveryActivity.EXTRA_MAIN_PID, Process.myPid())
            putExtra(PlaybackProcessRecoveryActivity.EXTRA_MAIN_START_TICKS, mainStartTicks)
            putExtra(PlaybackProcessRecoveryActivity.EXTRA_REQUESTED_AT_MS, now)
            putExtra(PlaybackProcessRecoveryActivity.EXTRA_TARGET_INTENT, target)
            putExtra(PlaybackProcessRecoveryActivity.EXTRA_REASON, reason)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        return runCatching {
            StabilityTelemetry.record(
                type = "process_recovery",
                severity = "fatal",
                detail = reason,
            )
            PlaybackLog.log(
                activity,
                "ProcessRecovery",
                "starting controlled playback process recovery reason=$reason",
            )
            activity.startActivity(recovery)
            activity.overridePendingTransition(0, 0)
            // Normally this process is gone within a second. If the vendor OS
            // refuses to launch the recovery Activity, allow a later retry.
            Handler(Looper.getMainLooper()).postDelayed(
                { requestInFlight.set(false) },
                RECOVERY_LAUNCH_WATCHDOG_MS,
            )
            true
        }.getOrElse {
            requestInFlight.set(false)
            false
        }
    }

    /** Called only after the separate recovery Activity is actually alive. */
    fun acknowledgeRecovery(context: Context, requestedAtMs: Long): Boolean {
        if (requestedAtMs <= 0L) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_RECOVERY_MS, 0L)
        if (!PlaybackProcessRecoveryPolicy.mayRecover(requestedAtMs, last)) return false
        val previousPolicy = StrictMode.allowThreadDiskWrites()
        return try {
            val written = prefs.edit()
                .putLong(KEY_LAST_RECOVERY_MS, requestedAtMs)
                .commit()
            if (written) AbnormalExitDetector.markCleanStop(context)
            written
        } finally {
            StrictMode.setThreadPolicy(previousPolicy)
        }
    }

    fun readProcessStartTicks(pid: Int): Long? = runCatching {
        val raw = File("/proc/$pid/stat").readText()
        val commandEnd = raw.lastIndexOf(')')
        if (commandEnd < 0 || commandEnd + 2 >= raw.length) return@runCatching null
        // Remaining field index 0 is proc field 3; starttime is field 22.
        raw.substring(commandEnd + 2)
            .trim()
            .split(Regex("\\s+"))
            .getOrNull(19)
            ?.toLongOrNull()
    }.getOrNull()

    fun readProcessCommandLine(pid: Int): String? = runCatching {
        val bytes = File("/proc/$pid/cmdline").readBytes()
        val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
        bytes.copyOfRange(0, end)
            .toString(Charsets.UTF_8)
            .trim()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        readProcessCommandLine(Process.myPid())?.let { return it }
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pid = Process.myPid()
        return manager?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }

    private const val RECOVERY_LAUNCH_WATCHDOG_MS = 5_000L
}
