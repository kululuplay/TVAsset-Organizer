/*
 * RequestReporter.kt
 * Fire-and-forget uploader for user requests/complaints submitted from the Home
 * "İstek & Şikayet" dialog. Mirrors HeartbeatReporter: same OkHttp client and
 * shared ingest key, POSTs a small JSON body to the crash-receiver, which stores
 * it and surfaces it in the operator panel. Returns whether the post succeeded so
 * the dialog can show a confirmation or a retry hint; runs on IO and only lets
 * structured coroutine cancellation propagate.
 */
package com.iptv.player.util

import android.content.Context
import android.os.Build
import com.iptv.player.BuildConfig
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

object RequestReporter {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val TYPES = setOf("channel", "movie", "series", "complaint")

    /** TV keyboard input and history cards stay useful without accepting huge payloads. */
    const val MAX_MESSAGE_LENGTH = 500

    private const val MAX_HISTORY_ITEMS = 20
    private const val MAX_CREATED_AT_LENGTH = 64

    /**
     * Posts a single request/complaint. [type] is one of channel/movie/series/
     * complaint. Runs on IO and swallows all errors, returning false on any
     * failure so the caller can react without a crash.
     */
    suspend fun send(context: Context, type: String, message: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!Telemetry.isEnabled) return@withContext false
            val normalizedType = type.lowercase(Locale.ROOT)
            val normalizedMessage = normalizeMessage(message) ?: return@withContext false
            if (normalizedType !in TYPES) return@withContext false
            try {
                val app = context.applicationContext
                val payload = JSONObject().apply {
                    put("deviceId", DeviceId.get(app))
                    put("type", normalizedType)
                    put("message", normalizedMessage)
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
                execute(request).successful
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }

    /** A single past request of this device, newest first, for the Home history list. */
    data class MyRequest(
        val id: Long,
        val type: String,
        val message: String,
        val status: String,
        val createdAt: String,
    )

    sealed interface HistoryResult {
        data class Success(val items: List<MyRequest>) : HistoryResult
        data object Error : HistoryResult
    }

    /**
     * Fetches this device's own request history from the crash-receiver. Newest
     * first, capped server-side. Empty history is kept distinct from a transport
     * or contract error so the UI never reports "no requests" while offline.
     */
    suspend fun fetchMineResult(context: Context): HistoryResult =
        withContext(Dispatchers.IO) {
            if (!Telemetry.isEnabled) return@withContext HistoryResult.Error
            try {
                val app = context.applicationContext
                val deviceId = java.net.URLEncoder.encode(DeviceId.get(app), "UTF-8")
                val request = Request.Builder()
                    .url("${Telemetry.REQUESTS_MINE_ENDPOINT}?deviceId=$deviceId")
                    .header("X-Kululu-Key", Telemetry.INGEST_KEY)
                    .get()
                    .build()
                val response = execute(request)
                if (!response.successful || response.body.isNullOrBlank()) {
                    return@withContext HistoryResult.Error
                }
                val arr = JSONObject(response.body).optJSONArray("requests")
                    ?: return@withContext HistoryResult.Error
                val items = buildList {
                    for (i in 0 until minOf(arr.length(), MAX_HISTORY_ITEMS)) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optLong("id")
                        if (id <= 0L) continue
                        val message = sanitizeServerText(
                            o.optString("message"),
                            MAX_MESSAGE_LENGTH,
                        )
                        if (message.isBlank()) continue
                        add(
                            MyRequest(
                                id = id,
                                type = o.optString("type")
                                    .lowercase(Locale.ROOT)
                                    .takeIf { it in TYPES }
                                    ?: "other",
                                message = message,
                                status = if (
                                    o.optString("status").equals("done", ignoreCase = true)
                                ) {
                                    "done"
                                } else {
                                    "new"
                                },
                                createdAt = o.optString("createdAt")
                                    .trim()
                                    .take(MAX_CREATED_AT_LENGTH),
                            )
                        )
                    }
                }
                HistoryResult.Success(items)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HistoryResult.Error
            }
        }

    /** Compatibility helper for non-UI callers that do not need error state. */
    suspend fun fetchMine(context: Context): List<MyRequest> =
        when (val result = fetchMineResult(context)) {
            is HistoryResult.Success -> result.items
            HistoryResult.Error -> emptyList()
        }

    /**
     * Acknowledges resolved-request popups so the server flips notified=true and
     * stops re-sending them in the heartbeat. Best-effort; returns success.
     */
    suspend fun ack(context: Context, ids: List<Long>): Boolean =
        withContext(Dispatchers.IO) {
            val safeIds = ids.asSequence().filter { it > 0L }.distinct().take(20).toList()
            if (safeIds.isEmpty()) return@withContext true
            if (!Telemetry.isEnabled) return@withContext false
            try {
                val app = context.applicationContext
                val payload = JSONObject().apply {
                    put("deviceId", DeviceId.get(app))
                    put("ids", org.json.JSONArray(safeIds))
                }
                val request = Request.Builder()
                    .url(Telemetry.REQUESTS_ACK_ENDPOINT)
                    .header("X-Kululu-Key", Telemetry.INGEST_KEY)
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
                execute(request).successful
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Reject instead of truncating so a caller can never unknowingly submit only
     * part of a request. The EditText uses the same limit.
     */
    internal fun normalizeMessage(message: String): String? {
        val normalized = cleanText(message).trim()
        return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_MESSAGE_LENGTH }
    }

    private fun sanitizeServerText(value: String, maxLength: Int): String =
        cleanText(value)
            .trim()
            .take(maxLength)

    private fun cleanText(value: String): String =
        value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter {
                (it == '\n' || it == '\t' || !it.isISOControl()) &&
                    !isBidiControl(it)
            }

    private fun isBidiControl(value: Char): Boolean =
        value == '\u061C' ||
            value == '\u200E' ||
            value == '\u200F' ||
            value in '\u202A'..'\u202E' ||
            value in '\u2066'..'\u2069'

    /**
     * OkHttp's async API lets dialog/job cancellation cancel the underlying call
     * immediately. A blocking execute() would keep using the network and could
     * resume against a view that has already been dismissed.
     */
    private suspend fun execute(request: Request): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = ServiceLocator.httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) {
                            continuation.resume(HttpResult(successful = false, body = null))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                HttpResult(
                                    successful = it.isSuccessful,
                                    body = it.body?.string(),
                                )
                            }
                        }.getOrElse {
                            HttpResult(successful = false, body = null)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            )
        }

    private data class HttpResult(val successful: Boolean, val body: String?)
}
