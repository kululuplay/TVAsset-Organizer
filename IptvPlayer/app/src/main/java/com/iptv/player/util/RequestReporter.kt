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
}
