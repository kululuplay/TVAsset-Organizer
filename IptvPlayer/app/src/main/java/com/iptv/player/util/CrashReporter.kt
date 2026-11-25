/*
 * CrashReporter.kt
 * Silently ships a crash report to the Kululu support desk on the NEXT launch
 * after a crash, as a diagnostic report (the channel SupportClient already
 * uses; the old crash receiver is not hosted). Uploading from inside a dying
 * process is unreliable, so the uncaught-exception handler (see Logger) just
 * drops a marker file; on the next start we read the captured log and send it
 * in the background. A native crash or an ANR never reaches that handler, so
 * on Android 11+ the process exit list of the system is read as well.
 *
 * Requires no user interaction and no Google Play Services, so it works on the
 * Fire TV sticks and Sony TVs our (often elderly) users run. Entirely
 * best-effort: it never blocks startup and never throws.
 */
package com.iptv.player.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.iptv.player.BuildConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bounded retry for one frozen crash report: a receiver that keeps rejecting
 * the same report must not be hit on every launch forever.
 */
internal object CrashReportRetryPolicy {
    const val MAX_ATTEMPTS = 3

    /** Uploads per device per day: a crash loop must not spend the desk's shared report budget. */
    const val MAX_UPLOADS_PER_DAY = 4
    const val DAY_MS = 24L * 60L * 60L * 1_000L

    /** True when a report that already failed [attempts] times may be sent again. */
    fun shouldAttempt(attempts: Int): Boolean = attempts in 0 until MAX_ATTEMPTS

    /** Upload times of the last day; a clock set back cannot keep an old entry alive. */
    fun recentUploads(sentAtMs: List<Long>, nowMs: Long): List<Long> =
        sentAtMs.filter { it in (nowMs - DAY_MS + 1)..nowMs }

    /** Only exits the system itself calls a crash; kills by the user or for memory are not reports. */
    fun osExitKind(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        else -> null
    }
}

object CrashReporter {

    private const val TAG = "CrashReporter"

    /**
     * The report as captured on the first launch after the crash (device info,
     * marker summary and the log tail at that moment), plus the attempt count.
     * Freezing it makes every retry send the same bytes instead of a fresh log
     * tail that has meanwhile scrolled past the crash.
     */
    private const val REPORT_FILE = "crash-report-pending.json"

    /** Same shape as [REPORT_FILE], for a native crash / ANR read from the system's exit list. */
    private const val EXIT_REPORT_FILE = "exit-report-pending.json"
    private const val KEY_ATTEMPTS = "uploadAttempts"
    private const val KEY_KIND = "kind"
    private const val PREFS = "crash_reporter"
    private const val KEY_SENT_AT = "sent_at"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"

    /**
     * If the previous run crashed, freeze the captured report, clear the crash
     * marker, and upload the frozen report in the background. Safe to call on
     * every start.
     *
     * The marker is consumed on the first launch after the crash whether or not
     * the upload succeeds: leaving it in place would make
     * [Logger.hasPendingCrash] true forever, which mutes every native-crash /
     * OOM report from [AbnormalExitDetector]. A rejected upload is retried from
     * the frozen report on later launches, at most [CrashReportRetryPolicy.MAX_ATTEMPTS]
     * times, so a support desk outage neither loses the report nor loops on it.
     */
    fun uploadPendingIfAny(context: Context) {
        val app = context.applicationContext
        val hasMarker = Logger.hasPendingCrash()
        Thread {
            if (hasMarker) {
                runCatching { freezePendingCrash(app) }
                    .onFailure { Logger.w(TAG, "crash report capture failed: ${it.message}") }
                Logger.clearPendingCrash()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { freezeSystemExit(app) }
                    .onFailure { Logger.w(TAG, "exit report capture failed: ${it.message}") }
            }
            for (name in listOf(REPORT_FILE, EXIT_REPORT_FILE)) {
                runCatching { uploadFrozen(app, File(app.filesDir, name)) }
                    .onFailure { Logger.w(TAG, "crash upload failed: ${it.message}") }
            }
        }.apply {
            isDaemon = true
            name = "crash-upload"
            start()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the report once, right after the crash, into the frozen file. */
    private fun freezePendingCrash(context: Context) {
        val marker = Logger.crashSummary().orEmpty()
        val parts = marker.split('\n', limit = 2)
        val occurredAtMillis = parts.getOrNull(0)?.trim()?.toLongOrNull()
        val message = parts.getOrNull(1)?.trim().orEmpty()
        // A newer crash replaces an older frozen report that never got through.
        writeReport(File(context.filesDir, REPORT_FILE), report("crash", occurredAtMillis, message))
    }

    /**
     * A native crash (libVLC, MediaCodec) or an ANR of the previous run, as the
     * system recorded it. Each exit is reported once: the newest exit time seen
     * is stored before the upload, so a failure here cannot loop.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun freezeSystemExit(context: Context) {
        val prefs = prefs(context)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        if (newest <= lastSeen) return
        prefs.edit().putLong(KEY_LAST_EXIT_TS, newest).apply()
        // First start with this code: every stored exit is older history, not a new event.
        if (lastSeen == 0L) return
        val exit = exits.filter { it.timestamp > lastSeen }
            .firstOrNull { CrashReportRetryPolicy.osExitKind(it.reason) != null } ?: return
        val message = buildString {
            append(exit.description.orEmpty().take(300))
            append(" process=").append(exit.processName)
            append(" importance=").append(exit.importance)
            append(" status=").append(exit.status)
            append(" pssKb=").append(exit.pss).append(" rssKb=").append(exit.rss)
        }
        writeReport(
            File(context.filesDir, EXIT_REPORT_FILE),
            report(CrashReportRetryPolicy.osExitKind(exit.reason).orEmpty(), exit.timestamp, message),
        )
    }

    private fun report(kind: String, occurredAtMillis: Long?, message: String) = JSONObject().apply {
        put(KEY_KIND, kind)
        put("appVersion", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        if (occurredAtMillis != null) put("occurredAt", iso(occurredAtMillis))
        put("message", message)
        put("log", Logger.recentText(180_000))
        put(KEY_ATTEMPTS, 0)
    }

    private fun writeReport(target: File, json: JSONObject) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(json.toString())
            tmp.delete()
        }
    }

    private fun sentAt(context: Context): List<Long> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_SENT_AT, "[]"))
        List(array.length()) { array.getLong(it) }
    }.getOrDefault(emptyList())

    private fun uploadFrozen(context: Context, file: File) {
        if (!file.exists()) return
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (json == null) {
            file.delete()
            return
        }
        val attempts = json.optInt(KEY_ATTEMPTS, 0)
        if (!CrashReportRetryPolicy.shouldAttempt(attempts)) {
            Logger.w(TAG, "crash report dropped after $attempts failed uploads")
            file.delete()
            return
        }
        val now = System.currentTimeMillis()
        val sent = CrashReportRetryPolicy.recentUploads(sentAt(context), now)
        if (sent.size >= CrashReportRetryPolicy.MAX_UPLOADS_PER_DAY) {
            // Over the day's budget: the report stays frozen for a later start.
            Logger.w(TAG, "crash report held: ${sent.size} reports already sent today")
            return
        }
        // Count the attempt BEFORE the network call so a crash mid-upload can't
        // make the counter stand still.
        json.put(KEY_ATTEMPTS, attempts + 1)
        writeReport(file, json)

        // The desk limits a diagnostic message to 5000 characters; the stack
        // trace is in the log as well, so the tail of a long message can go.
        val message = buildString {
            append("Otomatik rapor: ").append(json.optString(KEY_KIND, "crash"))
            append("\nSürüm ").append(json.optString("appVersion"))
            append(" (").append(json.optInt("versionCode")).append(")")
            json.optString("occurredAt").takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
            append('\n').append(json.optString("message"))
        }.take(4_000)
        // Runs on the crash-upload daemon thread, so a blocking call is acceptable.
        val result = runBlocking { SupportClient.uploadDiagnostic(context, message, json.optString("log")) }
        if (result is SupportResult.Success) {
            file.delete()
            prefs(context).edit().putString(KEY_SENT_AT, JSONArray(sent + now).toString()).apply()
            Logger.i(TAG, "crash report uploaded")
        } else {
            Logger.w(TAG, "crash upload rejected: ${(result as? SupportResult.Failure)?.kind} (attempt ${attempts + 1})")
        }
    }

    private fun iso(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }
}
