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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LaunchCrashGuard {

    private const val PREFS = "launch_guard"
    private const val KEY_IN_PROGRESS = "launch_in_progress"
    private const val KEY_STREAK = "crash_streak"

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
                prefs(context).getBoolean(KEY_IN_PROGRESS, false)
            }.getOrDefault(false)
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
