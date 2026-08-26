package com.iptv.player.util

import android.content.Context
import android.os.Build
import com.iptv.player.BuildConfig
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.DiagnosticResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Uploads only redacted, closed-schema diagnostics and returns a phone-friendly code. */
object SupportReportUploader {

    suspend fun upload(
        context: Context,
        results: List<DiagnosticResult>,
        summary: String,
    ): String? = withContext(Dispatchers.IO) {
        if (!Telemetry.isEnabled) return@withContext null
        runCatching {
            val checks = JSONArray().apply {
                results.take(MAX_CHECKS).forEachIndexed { index, result ->
                    put(
                        JSONObject().apply {
                            put("key", "check_${index + 1}")
                            put("label", sanitize(result.label).take(80))
                            put("ok", result.ok)
                            put("detail", sanitize(result.detail))
                        },
                    )
                }
            }
            val json = JSONObject().apply {
                put("deviceId", DeviceId.get(context))
                put("appVersion", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("summary", sanitize(summary).take(MAX_SUMMARY))
                put("checks", checks)
            }
            val request = Request.Builder()
                .url(Telemetry.SUPPORT_REPORT_ENDPOINT)
                .header("X-Kululu-Key", Telemetry.INGEST_KEY)
                .post(
                    json.toString().toRequestBody(
                        "application/json; charset=utf-8".toMediaType(),
                    ),
                )
                .build()
            ServiceLocator.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty())
                    .optString("supportCode")
                    .takeIf { SUPPORT_CODE.matches(it) }
            }
        }.getOrNull()
    }

    internal fun sanitize(value: String): String = value
        .replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "•••")
        .replace(
            Regex("""(?i)(username|user|password|pass|token|auth)=([^&\s]+)"""),
            "$1=•••",
        )
        .take(MAX_DETAIL)

    private const val MAX_CHECKS = 24
    private const val MAX_DETAIL = 300
    private const val MAX_SUMMARY = 4_000
    private val SUPPORT_CODE = Regex("K-[A-F0-9]{4}-[A-F0-9]{4}")
}
