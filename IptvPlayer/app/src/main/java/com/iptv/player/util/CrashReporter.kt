/*
 * CrashReporter.kt
 * Silently ships a crash report to the Kululu crash-receiver on the NEXT launch
 * after a crash. Uploading from inside a dying process is unreliable, so the
 * uncaught-exception handler (see Logger) just drops a marker file; on the next
 * start we read the captured log + device info and POST it in the background.
 *
 * Requires no user interaction and no Google Play Services, so it works on the
 * Fire TV sticks and Sony TVs our (often elderly) users run. Entirely
 * best-effort: it never blocks startup and never throws.
 */
package com.iptv.player.util

import android.content.Context
import android.os.Build
import com.iptv.player.BuildConfig
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    /** True when a report that already failed [attempts] times may be sent again. */
    fun shouldAttempt(attempts: Int): Boolean = attempts in 0 until MAX_ATTEMPTS
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
    private const val KEY_ATTEMPTS = "uploadAttempts"

    // Crash-receiver endpoint + shared ingest key live in one place (Telemetry) so
    // the base URL / key rotate in lockstep with the heartbeat reporter.
    private val ENDPOINT = Telemetry.CRASH_ENDPOINT
    private val INGEST_KEY = Telemetry.INGEST_KEY

    /**
     * If the previous run crashed, freeze the captured report, clear the crash
     * marker, and upload the frozen report in the background. Safe to call on
     * every start.
     *
     * The marker is consumed on the first launch after the crash whether or not
     * telemetry is enabled or the upload succeeds: leaving it in place would make
     * [Logger.hasPendingCrash] true forever, which mutes every native-crash /
     * OOM report from [AbnormalExitDetector]. A rejected upload is retried from
     * the frozen report on later launches, at most [CrashReportRetryPolicy.MAX_ATTEMPTS]
     * times, so a receiver outage neither loses the report nor loops on it.
     */
    fun uploadPendingIfAny(context: Context) {
        val app = context.applicationContext
        val enabled = Telemetry.isEnabled && !ENDPOINT.contains("REPLACE_AFTER_DEPLOY")
        val hasMarker = Logger.hasPendingCrash()
        if (!hasMarker && !(enabled && reportFile(app).exists())) return
        Thread {
            if (hasMarker) {
                if (enabled) {
                    runCatching { freezePendingCrash(app) }
                        .onFailure { Logger.w(TAG, "crash report capture failed: ${it.message}") }
                }
                Logger.clearPendingCrash()
            }
            if (enabled) {
                runCatching { uploadFrozen(app) }
                    .onFailure { Logger.w(TAG, "crash upload failed: ${it.message}") }
            }
        }.apply {
            isDaemon = true
            name = "crash-upload"
            start()
        }
    }

    private fun reportFile(context: Context): File = File(context.filesDir, REPORT_FILE)

    /** Capture the report once, right after the crash, into the frozen file. */
    private fun freezePendingCrash(context: Context) {
        val marker = Logger.crashSummary().orEmpty()
        val parts = marker.split('\n', limit = 2)
        val occurredAtMillis = parts.getOrNull(0)?.trim()?.toLongOrNull()
        val message = parts.getOrNull(1)?.trim().orEmpty()
        val log = Logger.recentText(180_000)

        val json = JSONObject().apply {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
            // Tie this crash to the same device id the heartbeat reports so the ops
            // panel can show per-device crash counts. Runs on the crash-upload
            // daemon thread, so a short runBlocking here is acceptable.
            put("deviceId", runBlocking { DeviceId.get(context) })
            if (occurredAtMillis != null) put("occurredAt", iso(occurredAtMillis))
            put("message", message)
            put("log", log)
            put(KEY_ATTEMPTS, 0)
        }
        // A newer crash replaces an older frozen report that never got through.
        writeReport(context, json)
    }

    private fun writeReport(context: Context, json: JSONObject) {
        val target = reportFile(context)
        val tmp = File(target.parentFile, "$REPORT_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(json.toString())
            tmp.delete()
        }
    }

    private fun uploadFrozen(context: Context) {
        val file = reportFile(context)
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
        // Count the attempt BEFORE the network call so a crash mid-upload can't
        // make the counter stand still.
        json.put(KEY_ATTEMPTS, attempts + 1)
        writeReport(context, json)

        val payload = JSONObject(json.toString()).apply { remove(KEY_ATTEMPTS) }
        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("X-Kululu-Key", INGEST_KEY)
            .post(body)
            .build()

        ServiceLocator.httpClient.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) {
                file.delete()
                Logger.i(TAG, "crash report uploaded")
            } else {
                Logger.w(TAG, "crash upload rejected: HTTP ${resp.code} (attempt ${attempts + 1})")
            }
        }
    }

    private fun iso(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }
}
