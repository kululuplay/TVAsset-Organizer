/*
 * AbnormalExitDetector.kt
 * Lightweight proxy for native crashes / OOM-kills WITHOUT JNI signal handling.
 * A native libVLC / MediaCodec SIGSEGV or an OS low-memory kill never reaches the
 * Java uncaught-exception handler, so those failures are invisible to the crash
 * reporter (which is why the crash table looks empty despite real field
 * problems). Here we keep a tiny, synchronously-committed "session alive" marker
 * (timestamp + what was playing) refreshed by the foreground heartbeat, and clear
 * it on a CLEAN background stop. If the next launch finds a RECENT marker that was
 * never cleanly closed — and the last run left no Java crash — the previous
 * process most likely died abnormally mid-playback.
 *
 * Deliberately CONSERVATIVE to limit false positives (swipe-kill, force-stop, OS
 * update/reboot): it fires only when a playback was active within the recency
 * window, and the result is reported as a SUSPECTED abnormal exit — never a crash.
 */
package com.iptv.player.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

internal object ExitReasonPolicy {
    data class Result(val type: String, val severity: String)

    fun classify(reason: Int?): Result = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> Result("native_crash", "fatal")
        ApplicationExitInfo.REASON_SIGNALED -> Result("signaled_exit", "warning")
        ApplicationExitInfo.REASON_ANR -> Result("os_anr", "fatal")
        ApplicationExitInfo.REASON_LOW_MEMORY -> Result("low_memory_exit", "warning")
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            Result("resource_exit", "warning")
        else -> Result("suspected_abnormal_exit", "fatal")
    }
}

private data class ExitSnapshot(
    val reason: Int,
    val status: Int,
    val pss: Long,
    val rss: Long,
)

object AbnormalExitDetector {

    private const val PREFS = "abnormal_exit"
    private const val KEY_TS = "alive_ts"
    private const val KEY_CHANNEL = "alive_channel"
    private const val KEY_CLEAN = "clean_stop"

    /** Only treat an uncleared marker as abnormal if refreshed this recently. */
    private const val RECENCY_MS = 30 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Refresh the "alive" marker. Called from the foreground heartbeat (off the
     * main thread). Committed synchronously so it survives a hard process kill.
     */
    fun markAlive(context: Context, channel: String?) {
        runCatching {
            prefs(context).edit()
                .putLong(KEY_TS, System.currentTimeMillis())
                .putString(KEY_CHANNEL, channel)
                .putBoolean(KEY_CLEAN, false)
                .commit()
        }
    }

    /** Mark a clean background stop so the next launch knows the exit was orderly. */
    fun markCleanStop(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_CLEAN, true).commit() }
    }

    /**
     * On launch: if the previous session left a recent, uncleared marker while
     * something was playing and there was no Java crash, record one suspected
     * abnormal-exit event. Consumes the marker so it fires at most once.
     *
     * [hadPendingCrash] must be sampled by the caller BEFORE the crash uploader
     * starts — the uploader clears the marker asynchronously, so reading it here
     * could race and mis-attribute a real Java crash as an abnormal exit.
     */
    fun detectAndReport(context: Context, hadPendingCrash: Boolean) {
        runCatching {
            val p = prefs(context)
            val ts = p.getLong(KEY_TS, 0L)
            if (ts <= 0L) return
            val clean = p.getBoolean(KEY_CLEAN, true)
            val channel = p.getString(KEY_CHANNEL, null)
            // Consume the marker first so a failure while reporting can't re-fire it.
            p.edit().putLong(KEY_TS, 0L).putString(KEY_CHANNEL, null)
                .putBoolean(KEY_CLEAN, true).commit()
            val ageMs = System.currentTimeMillis() - ts
            val recent = ageMs in 0..RECENCY_MS
            // Conservative: only when a playback was active, not cleanly closed, and
            // the last run left no Java crash (that path is already reported).
            if (recent && !clean && !channel.isNullOrBlank() && !hadPendingCrash) {
                val exit = latestExit(context, ts)
                val classified = ExitReasonPolicy.classify(exit?.reason)
                StabilityTelemetry.record(
                    type = classified.type,
                    channel = channel,
                    severity = classified.severity,
                    detail = buildString {
                        append("last alive ${ageMs / 1000}s ago")
                        exit?.let {
                            append(" reason=${it.reason}")
                            append(" status=${it.status}")
                            append(" pssKb=${it.pss}")
                            append(" rssKb=${it.rss}")
                        }
                    },
                )
            }
        }
    }

    private fun latestExit(context: Context, aliveTimestampMs: Long): ExitSnapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return latestExitApi30(context, aliveTimestampMs)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun latestExitApi30(context: Context, aliveTimestampMs: Long): ExitSnapshot? {
        return runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 4)
                .firstOrNull { it.timestamp >= aliveTimestampMs - EXIT_REASON_CLOCK_SLOP_MS }
                ?.let { info ->
                    ExitSnapshot(
                        reason = info.reason,
                        status = info.status,
                        pss = info.pss,
                        rss = info.rss,
                    )
                }
        }.getOrNull()
    }

    private const val EXIT_REASON_CLOCK_SLOP_MS = 60_000L
}
