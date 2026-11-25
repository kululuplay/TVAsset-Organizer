/*
 * ProgramReminders.kt
 * Reminders for programmes picked in the guide panel of the live screen (the
 * programme info dialog). The guide reaches a few hours
 * ahead, so a reminder is a short-lived note: a small list in preferences and
 * a text notice shortly before the start, shown while the app is in front. A
 * toast takes no focus, so it is safe over the players. An app that is closed
 * shows nothing: TV launchers do not surface app notifications.
 */
package com.iptv.player.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ProgramReminder(
    val epgChannelId: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
) {
    fun sameProgram(other: ProgramReminder) =
        epgChannelId == other.epgChannelId && startMs == other.startMs
}

/** Pure list rules, so they can be tested without Android. */
internal object ProgramReminderPolicy {
    const val MAX_REMINDERS = 50

    /** Tell the viewer this long before the start. */
    const val LEAD_MS = 60_000L

    /** A reminder whose start is this far in the past was missed (app closed) and is dropped. */
    const val LATE_MS = 10L * 60L * 1_000L

    /** Adds the reminder, or removes it when it is already set. */
    fun toggle(list: List<ProgramReminder>, reminder: ProgramReminder): List<ProgramReminder> =
        if (list.any { it.sameProgram(reminder) }) {
            list.filterNot { it.sameProgram(reminder) }
        } else {
            (list + reminder).sortedBy { it.startMs }.takeLast(MAX_REMINDERS)
        }

    fun due(list: List<ProgramReminder>, nowMs: Long): List<ProgramReminder> =
        list.filter { nowMs >= it.startMs - LEAD_MS && nowMs <= it.startMs + LATE_MS }

    /** What stays after a check: everything that is neither due now nor missed. */
    fun pending(list: List<ProgramReminder>, nowMs: Long): List<ProgramReminder> =
        list.filter { nowMs < it.startMs - LEAD_MS }
}

object ProgramReminders {

    private const val PREFS = "program_reminders"
    private const val KEY_LIST = "list"
    private const val CHECK_MS = 20_000L

    private val lock = Any()
    @Volatile private var loopJob: Job? = null

    fun isSet(context: Context, epgChannelId: String, startMs: Long): Boolean =
        load(context).any { it.epgChannelId == epgChannelId && it.startMs == startMs }

    /** Returns true when the reminder is set after the call, false when it was removed. */
    fun toggle(context: Context, reminder: ProgramReminder): Boolean = synchronized(lock) {
        val next = ProgramReminderPolicy.toggle(load(context), reminder)
        save(context, next)
        next.any { it.sameProgram(reminder) }
    }

    /** Check while the app is in front. Safe to call repeatedly. */
    fun start(context: Context) {
        if (loopJob?.isActive == true) return
        val app = context.applicationContext
        loopJob = ServiceLocator.appScope.launch {
            while (isActive) {
                runCatching { announceDue(app) }
                delay(CHECK_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun announceDue(context: Context) {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            val all = load(context)
            if (all.isEmpty()) return
            val pending = ProgramReminderPolicy.pending(all, now)
            if (pending.size != all.size) save(context, pending)
            ProgramReminderPolicy.due(all, now)
        }
        if (due.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            due.forEach {
                Toast.makeText(
                    context,
                    context.getString(R.string.reminder_due, it.title, it.channelName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): List<ProgramReminder> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_LIST, "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ProgramReminder(
                epgChannelId = item.getString("channel"),
                channelName = item.optString("name"),
                title = item.optString("title"),
                startMs = item.getLong("start"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, list: List<ProgramReminder>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject().put("channel", it.epgChannelId).put("name", it.channelName)
                    .put("title", it.title).put("start", it.startMs),
            )
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }
}
