/*
 * HeartbeatReporter.kt
 * Sends a lightweight "I'm alive" ping to the crash-receiver every minute while
 * the app is in the FOREGROUND, so the ops panel can show which devices are
 * watching right now (count, IP, model, app/Android version). The server upserts
 * one row per stable device id, so heartbeats never accumulate.
 *
 * Foreground-gated by IptvApp (started-activity count): Android TV keeps idle
 * processes alive for hours, so a bare process-alive loop would inflate the
 * "live" count with boxes nobody is using. Entirely best-effort — it never
 * blocks startup, never throws, and silently no-ops when offline. No Google Play
 * Services and no runtime permission required (works on Fire TV / Sony TV).
 */
package com.iptv.player.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.iptv.player.BuildConfig
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

object HeartbeatReporter {

    private const val TAG = "HeartbeatReporter"

    /** How often to ping while foregrounded. */
    private const val INTERVAL_MS = 60_000L

    // The notorious shared/duplicated ANDROID_ID some cheap clone boxes ship; if
    // we see it (or null/blank) we fall back to a persisted random UUID so those
    // devices don't all collapse into one row.
    private const val BAD_ANDROID_ID = "9774d56d682e549c"

    @Volatile private var loopJob: Job? = null

    /** Begin (or keep) the heartbeat loop. Safe to call repeatedly. */
    fun start(context: Context) {
        if (loopJob?.isActive == true) return
        val app = context.applicationContext
        loopJob = ServiceLocator.appScope.launch {
            while (isActive) {
                runCatching { sendOnce(app) }
                    .onFailure { Logger.w(TAG, "heartbeat failed: ${it.message}") }
                delay(INTERVAL_MS)
            }
        }
    }

    /** Stop the loop when the app goes to the background. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun sendOnce(context: Context) {
        val json = JSONObject().apply {
            put("deviceId", deviceId(context))
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
        }
        val body = json.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(Telemetry.HEARTBEAT_ENDPOINT)
            .header("X-Kululu-Key", Telemetry.INGEST_KEY)
            .post(body)
            .build()
        ServiceLocator.httpClient.newCall(request).execute().use { /* fire-and-forget */ }
    }

    /**
     * Stable per-install id: ANDROID_ID when usable, otherwise a random UUID
     * persisted in settings so it survives restarts (a factory reset producing a
     * new id is acceptable).
     */
    private suspend fun deviceId(context: Context): String =
        ServiceLocator.settings.getOrCreateDeviceId {
            val androidId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
            if (androidId.isNullOrBlank() || androidId == BAD_ANDROID_ID) {
                UUID.randomUUID().toString()
            } else {
                androidId
            }
        }
}
