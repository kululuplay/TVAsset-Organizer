/*
 * HeartbeatReporter.kt
 * Sends a lightweight "I'm alive" ping to the crash-receiver every minute while
 * the app is in the FOREGROUND, so the ops panel can show which devices are
 * watching right now (count, IP/city, model, app/Android version, and what's
 * playing). The server upserts one row per stable device id, so heartbeats never
 * accumulate. The 200 response carries the active operator announcement (if any),
 * which we hand to AnnouncementCenter.
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

object HeartbeatReporter {

    private const val TAG = "HeartbeatReporter"

    /** How often to ping while foregrounded. */
    private const val INTERVAL_MS = 60_000L

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
            put("deviceId", DeviceId.get(context))
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
            NowPlaying.title?.let { put("nowPlaying", it) }
            NowPlaying.kind?.let { put("nowPlayingKind", it) }
            // The portal login this box is connected with, so the ops panel can tie
            // a live device to an account. Blank for M3U-URL sources (no login).
            runCatching { ServiceLocator.settings.getSourceConfig()?.username }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { put("username", it) }
        }
        val body = json.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(Telemetry.HEARTBEAT_ENDPOINT)
            .header("X-Kululu-Key", Telemetry.INGEST_KEY)
            .post(body)
            .build()
        ServiceLocator.httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return
            // Newer servers return 200 with {announcement:{id,message}|null}; older
            // ones return 204 (no body). Parse defensively so neither breaks the loop.
            val text = runCatching { resp.body?.string() }.getOrNull()
            if (text.isNullOrBlank()) return
            runCatching {
                val obj = JSONObject(text).optJSONObject("announcement")
                if (obj == null) {
                    AnnouncementCenter.clear()
                } else {
                    AnnouncementCenter.update(obj.optLong("id"), obj.optString("message"))
                }
            }
        }
    }
}
