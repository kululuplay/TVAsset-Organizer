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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CrashReporter {

    private const val TAG = "CrashReporter"

    // Crash-receiver endpoint + shared ingest key live in one place (Telemetry) so
    // the base URL / key rotate in lockstep with the heartbeat reporter.
    private val ENDPOINT = Telemetry.CRASH_ENDPOINT
    private val INGEST_KEY = Telemetry.INGEST_KEY

    /**
     * If the previous run crashed, upload the captured report in the background
     * and clear the marker so it is sent only once. Safe to call on every start.
     */
    fun uploadPendingIfAny(context: Context) {
        if (!Telemetry.isEnabled || ENDPOINT.contains("REPLACE_AFTER_DEPLOY")) return
        if (!Logger.hasPendingCrash()) return
        val app = context.applicationContext
        Thread {
            runCatching { upload(app) }
                .onFailure { Logger.w(TAG, "crash upload failed: ${it.message}") }
        }.apply {
            isDaemon = true
            name = "crash-upload"
            start()
        }
    }

    private fun upload(context: Context) {
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
        }

        val body = json.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("X-Kululu-Key", INGEST_KEY)
            .post(body)
            .build()

        ServiceLocator.httpClient.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) {
                Logger.clearPendingCrash()
                Logger.i(TAG, "crash report uploaded")
            } else {
                Logger.w(TAG, "crash upload rejected: HTTP ${resp.code}")
            }
        }
    }

    private fun iso(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }
}
