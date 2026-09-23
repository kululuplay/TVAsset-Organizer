/*
 * UpdatePromptPolicy.kt
 * Pure rules for the launch-time update dialog plus the tiny SharedPreferences
 * marker behind them. The dialog is a courtesy, not the update path: the About
 * screen always allows a manual check, so the rules err on the side of silence.
 */
package com.iptv.player.update

import android.content.Context

internal object UpdatePromptPolicy {
    /** A version dismissed with "Later" is announced again after a day at most. */
    const val REPROMPT_INTERVAL_MS = 24L * 60L * 60L * 1_000L

    /** The last "Later" (or Back) choice: which version, and when. */
    data class Dismissal(val versionName: String, val dismissedAtMs: Long)

    /**
     * Whether the dashboard may announce [info] now. A release the device cannot
     * run is never announced; the version last dismissed stays quiet for
     * [REPROMPT_INTERVAL_MS]. A marker stamped more than a day in the future
     * came from a wrong clock and is ignored rather than silencing the prompt
     * until the clock catches up.
     */
    fun shouldPrompt(
        info: UpdateInfo,
        deviceSdkInt: Int,
        dismissal: Dismissal?,
        nowMs: Long,
    ): Boolean {
        if (info.minAndroidApi > deviceSdkInt) return false
        if (dismissal == null || dismissal.versionName != info.versionName) return true
        val elapsed = nowMs - dismissal.dismissedAtMs
        return elapsed >= REPROMPT_INTERVAL_MS || elapsed <= -REPROMPT_INTERVAL_MS
    }
}

/** Persists the single most recent "Later" choice across process restarts. */
internal class UpdatePromptDismissals(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** May block on the first preference load: call it off the main thread. */
    fun current(): UpdatePromptPolicy.Dismissal? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        return UpdatePromptPolicy.Dismissal(version, prefs.getLong(KEY_AT, 0L))
    }

    fun record(versionName: String, nowMs: Long) {
        prefs.edit()
            .putString(KEY_VERSION, versionName)
            .putLong(KEY_AT, nowMs)
            .apply()
    }

    private companion object {
        const val PREFS = "update_prompt"
        const val KEY_VERSION = "dismissed_version"
        const val KEY_AT = "dismissed_at"
    }
}
