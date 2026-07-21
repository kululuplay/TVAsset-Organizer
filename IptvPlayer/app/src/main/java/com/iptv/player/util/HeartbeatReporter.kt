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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object HeartbeatReporter {

    private const val TAG = "HeartbeatReporter"

    /** How often to ping while foregrounded. */
    private const val INTERVAL_MS = 60_000L

    /** Max stability events drained per beat (server also caps; keeps the body small). */
    private const val MAX_EVENTS_PER_BEAT = 20

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
        // Refresh the "session alive" marker each beat so the next launch can tell a
        // clean exit from an abnormal one (native crash / OOM-kill mid-playback).
        AbnormalExitDetector.markAlive(context, NowPlaying.title)
        // Drain a bounded batch of spooled stability events onto this beat; keep the
        // references so we only clear them after the server confirms (200).
        val pendingEvents = StabilityTelemetry.snapshot(MAX_EVENTS_PER_BEAT)
        // How many events the spool had to drop to overflow before this beat — the
        // server records one synthetic marker so a failure storm stays visible.
        val droppedBefore = StabilityTelemetry.snapshotDropped()
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
            // Player audio/engine settings snapshot so the ops panel can remotely
            // spot risky configs (e.g. HDMI passthrough ON silences projector/TV
            // speakers that cannot decode Dolby bitstreams). Best-effort.
            runCatching {
                val s = ServiceLocator.settings
                put("audioPassthrough", s.getAudioPassthrough())
                val summary = "engine=" + s.playerMode.first().name +
                    " decoder=" + s.getDecoderMode().name +
                    " buffer=" + s.getBufferMode().name +
                    " format=" + s.getStreamFormat().name
                put("playerSettings", summary)
            }
            if (pendingEvents.isNotEmpty()) {
                put("events", JSONArray().apply { pendingEvents.forEach { put(it) } })
            }
            if (droppedBefore > 0) put("eventsDropped", droppedBefore)
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
            // The beat landed: drop the events we just shipped (older servers simply
            // ignore the unknown field, so this stays backward compatible).
            if (pendingEvents.isNotEmpty()) StabilityTelemetry.confirmUploaded(pendingEvents)
            if (droppedBefore > 0) StabilityTelemetry.confirmDropped(droppedBefore)
            // Newer servers return 200 with {announcement:{id,message}|null}; older
            // ones return 204 (no body). Parse defensively so neither breaks the loop.
            val text = runCatching { resp.body?.string() }.getOrNull()
            if (text.isNullOrBlank()) return
            runCatching {
                val root = JSONObject(text)
                val obj = root.optJSONObject("announcement")
                if (obj == null) {
                    AnnouncementCenter.clear()
                } else {
                    AnnouncementCenter.update(obj.optLong("id"), obj.optString("message"))
                }
                // Requests the operator just marked "done" for this device. The
                // server keeps re-sending each until we ACK it, so an un-shown one
                // survives suppression/process-death; ResolvedRequestCenter dedups.
                val arr = root.optJSONArray("resolvedRequests")
                if (arr == null || arr.length() == 0) {
                    ResolvedRequestCenter.clear()
                } else {
                    val list = buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            add(
                                ResolvedRequestCenter.ResolvedRequest(
                                    id = o.optLong("id"),
                                    type = o.optString("type"),
                                    message = o.optString("message"),
                                )
                            )
                        }
                    }
                    ResolvedRequestCenter.update(list)
                }
            }
            // Retry the ACK for resolutions already shown this process whose ack
            // didn't land (server still lists them) so it stops re-sending.
            val retry = ResolvedRequestCenter.shownButPending()
            if (retry.isNotEmpty()) runCatching { RequestReporter.ack(context, retry) }
        }
    }
}
