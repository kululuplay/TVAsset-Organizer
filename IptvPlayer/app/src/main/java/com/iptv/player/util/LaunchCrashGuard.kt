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

object LaunchCrashGuard {

    private const val PREFS = "launch_guard"
    private const val KEY_IN_PROGRESS = "launch_in_progress"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when a previous launch armed the guard but never confirmed success. */
    fun previousLaunchCrashed(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_IN_PROGRESS, false) }.getOrDefault(false)

    /** Arm the guard right before the risky Dashboard launch. Committed synchronously. */
    fun markLaunchStarted(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_IN_PROGRESS, true).commit() }
    }

    /** Clear the guard once the home screen has drawn its first frame successfully. */
    fun markLaunchSucceeded(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_IN_PROGRESS, false).apply() }
    }
}
