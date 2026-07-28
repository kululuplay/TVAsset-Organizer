/*
 * RequestReporter.kt
 * Fire-and-forget uploader for user requests/complaints submitted from the Home
 * "İstek & Şikayet" dialog. Mirrors HeartbeatReporter: same OkHttp client and
 * shared ingest key, POSTs a small JSON body to the crash-receiver, which stores
 * it and surfaces it in the operator panel. Returns whether the post succeeded so
 * the dialog can show a confirmation or a retry hint; runs on IO and never throws.
 */
package com.iptv.player.util

import android.content.Context
import android.os.Build
import com.iptv.player.BuildConfig
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object RequestReporter {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Posts a single request/complaint. [type] is one of channel/movie/series/
     * complaint. Runs on IO and swallows all errors, returning false on any
     * failure so the caller can react without a crash.
     */
    suspend fun send(context: Context, type: String, message: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!Telemetry.isEnabled) return@withContext false
            runCatching {
                val app = context.applicationContext
                val payload = JSONObject().apply {
                    put("deviceId", DeviceId.get(app))
                    put("type", type)
                    put("message", message)
                    put("appVersion", BuildConfig.VERSION_NAME)
                    put("versionCode", BuildConfig.VERSION_CODE)
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    val user = runCatching { ServiceLocator.settings.getSourceConfig()?.username }
                        .getOrNull()
                    if (!user.isNullOrBlank()) put("username", user)
                }
                val request = Request.Builder()
                    .url(Telemetry.REQUEST_ENDPOINT)
                    .header("X-Kululu-Key", Telemetry.INGEST_KEY)
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
                ServiceLocator.httpClient.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** A single past request of this device, newest first, for the Home history list. */
    data class MyRequest(
        val id: Long,
        val type: String,
        val message: String,
        val status: String,
        val createdAt: String,
    )

    /**
     * Fetches this device's own request history from the crash-receiver. Newest
     * first, capped server-side. Runs on IO and returns an empty list on any error.
     */
    suspend fun fetchMine(context: Context): List<MyRequest> =
        withContext(Dispatchers.IO) {
            if (!Telemetry.isEnabled) return@withContext emptyList()
            runCatching {
                val app = context.applicationContext
                val deviceId = java.net.URLEncoder.encode(DeviceId.get(app), "UTF-8")
                val request = Request.Builder()
                    .url("${Telemetry.REQUESTS_MINE_ENDPOINT}?deviceId=$deviceId")
                    .header("X-Kululu-Key", Telemetry.INGEST_KEY)
                    .get()
                    .build()
                ServiceLocator.httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<MyRequest>()
                    val text = resp.body?.string()
                    if (text.isNullOrBlank()) return@use emptyList<MyRequest>()
                    val arr = JSONObject(text).optJSONArray("requests")
                        ?: return@use emptyList<MyRequest>()
                    buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            add(
                                MyRequest(
                                    id = o.optLong("id"),
                                    type = o.optString("type"),
                                    message = o.optString("message"),
                                    status = o.optString("status"),
                                    createdAt = o.optString("createdAt"),
                                )
                            )
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    /**
     * Acknowledges resolved-request popups so the server flips notified=true and
     * stops re-sending them in the heartbeat. Best-effort; returns success.
     */
    suspend fun ack(context: Context, ids: List<Long>): Boolean =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext true
            if (!Telemetry.isEnabled) return@withContext false
            runCatching {
                val app = context.applicationContext
                val payload = JSONObject().apply {
                    put("deviceId", DeviceId.get(app))
                    put("ids", org.json.JSONArray(ids))
                }
                val request = Request.Builder()
                    .url(Telemetry.REQUESTS_ACK_ENDPOINT)
                    .header("X-Kululu-Key", Telemetry.INGEST_KEY)
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
                ServiceLocator.httpClient.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
}
