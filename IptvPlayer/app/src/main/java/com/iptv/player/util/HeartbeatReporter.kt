/*
 * HeartbeatReporter.kt
 * Sends a lightweight "I'm alive" ping to the crash-receiver every minute while
 * the app is in the FOREGROUND, so the ops panel can show which devices are
 * watching right now (count, IP/city, model, app/Android version, and what's
 * playing). The server upserts one row per stable device id, so heartbeats never
 * accumulate. The 200 response carries the active operator announcement (if any),
 * which we hand to AnnouncementCenter.
 *
 * Also the upload path for finished playback QoE summaries ("qoe", <= 20 per
 * beat, removed from the spool only after a 2xx) and the delivery path for the
 * remote playback policy: when the build carries POLICY_PUBLIC_KEY_PEM only a
 * correctly signed `playbackPolicySigned` is applied, never the legacy object.
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
import com.iptv.player.playback.android.PlaybackQoeRuntime
import com.iptv.player.security.PolicyPublicKey
import com.iptv.player.security.PolicyRejectionGate
import com.iptv.player.security.PolicySignature
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

    /** Max finished QoE session summaries drained per beat (server caps at 20). */
    private const val MAX_QOE_PER_BEAT = 20

    @Volatile private var loopJob: Job? = null
    private val policyRejections = PolicyRejectionGate()

    /** Begin (or keep) the heartbeat loop. Safe to call repeatedly. */
    fun start(context: Context) {
        if (!Telemetry.isEnabled) return
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
        // Finished playback sessions; same keep-until-2xx rule, keyed by session id.
        val pendingQoe = PlaybackQoeRuntime.pendingUploads(MAX_QOE_PER_BEAT)
        val qoeDroppedBefore = PlaybackQoeRuntime.pendingDroppedCount()
        val json = JSONObject().apply {
            put("deviceId", DeviceId.get(context))
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
            // Device-class facts the operator writes playbackPolicy.deviceOverrides
            // rules against (see PlaybackRemotePolicy / docs/playback-policy.md).
            // Static per device, so the panel can show exactly what a rule sees.
            runCatching {
                val facts = PlaybackRemotePolicy.deviceFacts(context)
                put("hardware", facts.hardware)
                put("board", facts.board)
                facts.socModel?.let { put("socModel", it) }
                put("sdk", facts.sdk)
                facts.lowRam?.let { put("lowRam", it) }
                facts.totalRamMb?.let { put("totalRamMb", it) }
            }
            NowPlaying.title?.let { put("nowPlaying", it) }
            NowPlaying.kind?.let { put("nowPlayingKind", it) }
            // An opaque token for the portal login this box is connected with, so
            // the ops panel can tell that two live devices share one account. The
            // clear-text username never leaves the device (see AccountToken); the
            // field keeps its historical name because the server compares it for
            // equality only. Absent for M3U-URL sources (no login).
            runCatching { ServiceLocator.settings.getSourceConfig()?.username }
                .getOrNull()
                ?.let { AccountToken.of(it) }
                ?.let { put("username", it) }
            // Player audio/engine settings snapshot so the ops panel can remotely
            // spot risky configs (e.g. HDMI passthrough ON silences projector/TV
            // speakers that cannot decode Dolby bitstreams). Best-effort.
            runCatching {
                val s = ServiceLocator.settings
                put("audioPassthrough", s.getAudioPassthrough())
                val playback = s.getPlaybackSelection()
                val summary = "engine=" + playback.player.name +
                    " decoder=" + playback.decoder.name +
                    " buffer=" + s.getBufferMode().name +
                    " format=" + s.getStreamFormat().name
                put("playerSettings", summary)
            }
            if (pendingEvents.isNotEmpty()) {
                put("events", JSONArray().apply { pendingEvents.forEach { put(it) } })
            }
            if (droppedBefore > 0) put("eventsDropped", droppedBefore)
            if (pendingQoe.isNotEmpty()) {
                val arr = JSONArray()
                pendingQoe.forEach { entry ->
                    runCatching { JSONObject(entry.json) }.getOrNull()?.let { arr.put(it) }
                }
                if (arr.length() > 0) put("qoe", arr)
            }
            if (qoeDroppedBefore > 0) put("qoeDropped", qoeDroppedBefore)
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
            // QoE summaries have no per-row ack in the protocol: a 2xx for the
            // beat that carried them is the server's acceptance.
            if (pendingQoe.isNotEmpty()) PlaybackQoeRuntime.confirmUploaded(pendingQoe.map { it.id })
            // Newer servers return 200 with {announcement:{id,message}|null}; older
            // ones return 204 (no body). Parse defensively so neither breaks the loop.
            val text = runCatching { resp.body?.string() }.getOrNull()
            if (text.isNullOrBlank()) return
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return

            // Success of the heartbeat itself is not proof that every telemetry
            // row was stored. Remove only IDs explicitly acknowledged by the new
            // server protocol, intersected with this exact batch. Older servers
            // omit the field, so events remain bounded on disk and retry later.
            val sentIds = pendingEvents.mapNotNull { event ->
                event.optString("event_id").takeIf { it.isNotBlank() }
            }.toSet()
            val ackedIds = buildSet {
                val acked = root.optJSONArray("ackedEventIds") ?: return@buildSet
                for (i in 0 until acked.length()) {
                    acked.optString(i).takeIf { it in sentIds }?.let(::add)
                }
            }
            if (ackedIds.isNotEmpty()) StabilityTelemetry.confirmUploadedIds(ackedIds)
            if (droppedBefore > 0 && root.optBoolean("eventsDroppedAccepted", false)) {
                StabilityTelemetry.confirmDropped(droppedBefore)
            }
            // The overflow counter is cleared only once the server recorded it.
            if (qoeDroppedBefore > 0 && root.optBoolean("qoeDroppedAccepted", false)) {
                PlaybackQoeRuntime.confirmDropped(qoeDroppedBefore)
            }

            runCatching { applyPlaybackPolicy(context, root) }
            runCatching {
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

    /**
     * With a public key in the build, only `playbackPolicySigned` whose `sig`
     * verifies over the exact `payload` text is applied; a missing or bad
     * signature applies nothing (never the unsigned `playbackPolicy`), logs once
     * per process and spools one `policy_signature_rejected` event per hour.
     * Without a key (dev/local builds) the legacy unsigned object is applied.
     */
    private fun applyPlaybackPolicy(context: Context, root: JSONObject) {
        val legacy = root.optJSONObject("playbackPolicy")
        val signed = root.optJSONObject("playbackPolicySigned")
        val payload = signed?.optString("payload")?.takeIf { it.isNotEmpty() }
        val sig = signed?.optString("sig")?.takeIf { it.isNotBlank() }
        if (legacy == null && signed == null) return
        val accepted = PolicySignature.acceptedPayload(
            publicKeyPem = PolicyPublicKey.pem,
            payload = payload,
            sigBase64 = sig,
            unsignedFallback = legacy?.toString(),
        )
        if (accepted == null) {
            val why = when {
                signed == null -> "unsigned"
                payload == null || sig == null -> "incomplete"
                else -> "bad_signature"
            }
            val kid = signed?.optString("kid").orEmpty().take(32)
            if (policyRejections.shouldLog()) {
                Logger.w(TAG, "playback policy rejected ($why, kid=$kid); key configured, not applied")
                PlaybackLog.log(context, TAG, "remote policy rejected: $why")
            }
            if (policyRejections.shouldRecordEvent(System.currentTimeMillis())) {
                StabilityTelemetry.record(
                    type = PolicyRejectionGate.EVENT_TYPE,
                    channel = null,
                    kind = null,
                    severity = "warn",
                    detail = if (kid.isEmpty()) why else "$why kid=$kid",
                )
            }
            return
        }
        val policy = runCatching { JSONObject(accepted) }.getOrNull() ?: return
        PlaybackRemotePolicy.apply(context, policy)
    }
}
