/*
 * LaunchCrashGuard.kt
 * Detects a launch that started but never reached a stable home screen — i.e. a
 * crash on the login -> splash -> dashboard path. Uses a tiny dedicated
 * SharedPreferences flag (committed synchronously) so it survives a hard process
 * kill: markLaunchStarted() is called right before the risky Dashboard launch and
 * markLaunchSucceeded() only once the Dashboard has drawn its first frame. If the
 * flag is still set on the next launch the previous attempt crashed, so we route
 * to the recovery screen instead of crash-looping with no way to read the trace.
 */
package com.iptv.player.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure classification of an OS-reported process exit for the launch guard: only
 * exits the app itself could not have prevented count against the crash streak.
 * A user swipe/force-stop, a low-memory kill, a package update or a routine
 * background reclaim while the splash was up must never wipe playback settings.
 */
internal object LaunchExitPolicy {
    fun isCrash(reason: Int?): Boolean = when (reason) {
        null -> true // No record: keep the conservative pre-API-30 behaviour.
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> false
        else -> true
    }
}

object LaunchCrashGuard {

    private const val PREFS = "launch_guard"
    private const val KEY_IN_PROGRESS = "launch_in_progress"
    private const val KEY_STREAK = "crash_streak"
    private const val KEY_ARMED_AT = "launch_armed_at"

    /** Exit records may be stamped slightly before the arm commit reached disk. */
    private const val EXIT_CLOCK_SLOP_MS = 60_000L

    /**
     * Consecutive launch crashes at which safe mode kicks in: the recovery screen
     * resets the risky player settings to their defaults so a bad setting (or a
     * remembered decode route) can't keep the app in a boot-crash loop.
     */
    const val SAFE_MODE_THRESHOLD = 2

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True when a previous launch armed the guard but never confirmed success.
     *
     * SharedPreferences may synchronously load its XML on the first access. Keep
     * that read off the UI thread so the splash animation and first frame are not
     * delayed on slower TV storage.
     */
    suspend fun previousLaunchCrashed(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = prefs(context)
                if (!p.getBoolean(KEY_IN_PROGRESS, false)) return@runCatching false
                // The guard is armed, but the OS may know the process left for a
                // benign reason (swipe-kill, low memory, package update). Treat
                // such an exit as clean instead of counting it toward safe mode.
                if (osReportsBenignExit(context, p.getLong(KEY_ARMED_AT, 0L))) {
                    p.edit().putBoolean(KEY_IN_PROGRESS, false).commit()
                    return@runCatching false
                }
                true
            }.getOrDefault(false)
        }

    private fun osReportsBenignExit(context: Context, armedAtMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching { latestExitReasonApi30(context, armedAtMs) }
            .getOrNull()
            ?.let { !LaunchExitPolicy.isCrash(it) }
            ?: false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun latestExitReasonApi30(context: Context, armedAtMs: Long): Int? {
        val activityManager = context.applicationContext
            .getSystemService(ActivityManager::class.java) ?: return null
        return activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
            // Only an exit that happened after this arm is evidence about it.
            ?.takeIf { armedAtMs > 0L && it.timestamp >= armedAtMs - EXIT_CLOCK_SLOP_MS }
            ?.reason
    }

    /**
     * Consume the armed guard after a crash was detected: disarm it (so an exit
     * from the recovery screen doesn't count the SAME crash again on the next
     * launch) and increment the consecutive-crash streak. Committed synchronously.
     * Returns the new streak (>= 1).
     */
    suspend fun consumeCrashAndCountStreak(context: Context): Int =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = prefs(context)
                val streak = p.getInt(KEY_STREAK, 0) + 1
                p.edit()
                    .putBoolean(KEY_IN_PROGRESS, false)
                    .putInt(KEY_STREAK, streak)
                    .commit()
                streak
            }.getOrDefault(1)
        }

    /** Current consecutive launch-crash streak (0 = last launch was healthy). */
    suspend fun crashStreak(context: Context): Int =
        withContext(Dispatchers.IO) {
            runCatching { prefs(context).getInt(KEY_STREAK, 0) }.getOrDefault(0)
        }

    /**
     * Arm the guard right before the risky Dashboard launch.
     *
     * Await the durable commit, but perform it on Dispatchers.IO. Replacing this
     * with apply() would hide the main-thread violation at the cost of losing the
     * guard when the process crashes before the asynchronous write reaches disk.
     */
    suspend fun markLaunchStarted(context: Context) =
        withContext(Dispatchers.IO) {
            runCatching {
                prefs(context).edit()
                    .putBoolean(KEY_IN_PROGRESS, true)
                    .putLong(KEY_ARMED_AT, System.currentTimeMillis())
                    .commit()
            }
            Unit
        }

    /**
     * Disarm the guard because the launch is ending for a reason that is not a
     * crash (user pressed Back/Home on the splash, the activity is finishing
     * without routing to the home, a configuration change). Unlike
     * [markLaunchSucceeded] this does NOT reset the streak: nothing has proven
     * the home screen healthy, it only proves this exit was not a crash.
     */
    suspend fun markCleanExit(context: Context) =
        withContext(Dispatchers.IO) {
            runCatching {
                prefs(context).edit()
                    .putBoolean(KEY_IN_PROGRESS, false)
                    .commit()
            }
            Unit
        }

    /**
     * Clear the guard once the home screen has drawn its first frame successfully.
     * Also resets the crash streak — the loop (if any) is broken.
     */
    suspend fun markLaunchSucceeded(context: Context) =
        withContext(Dispatchers.IO) {
            runCatching {
                prefs(context).edit()
                    .putBoolean(KEY_IN_PROGRESS, false)
                    .putInt(KEY_STREAK, 0)
                    .commit()
            }
            Unit
        }
}
