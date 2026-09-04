package com.iptv.player.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import com.iptv.player.BuildConfig
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Explicit, customer-initiated support uploads. This client never sends IPTV credentials,
 * uses no shared telemetry key, and cannot redirect its per-installation bearer token.
 * All disk/crypto/network work is on IO and cancellation cancels the active HTTP call.
 */
object SupportClient {
    private const val BASE_URL = "https://212.95.41.130:8443"
    private const val PREFS = "support_installation_v1"
    private const val MAX_RESPONSE_BYTES = 256 * 1024L
    private const val MAX_PENDING_IDS = 256
    private const val PENDING_PREFIX = "pending_"
    private const val RECEIPT_PREFIX = "receipt_"
    private const val MAX_CACHED_RECEIPTS = 100
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val prefsLock = Any()
    private val registrationMutex = Mutex()
    @Volatile private var client: OkHttpClient? = null

    suspend fun uploadDiagnostic(
        context: Context,
        message: String,
        log: String,
        metadata: Map<String, Any?> = emptyMap(),
    ): SupportResult = send(context, "diagnostic", message, log, metadata)

    internal suspend fun sendRequest(context: Context, type: String, message: String): SupportResult =
        send(context, type, message, null, emptyMap())

    private suspend fun send(
        context: Context,
        type: String,
        message: String,
        log: String?,
        metadata: Map<String, Any?>,
    ): SupportResult = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(65_000L) {
                val app = context.applicationContext
                val payload = SupportPayloadPolicy.prepare(
                    type, message, log, deviceMetadata() + metadata, knownSecrets(),
                ) ?: return@withTimeoutOrNull SupportResult.Failure(SupportFailureKind.INVALID_INPUT)
                val prefs = preferences(app)
                val fingerprint = payload.fingerprint()
                // A lifecycle cancellation can occur after HTTP success but before UI success.
                // The same payload can recover its verified receipt without creating a new ticket.
                cachedReceipt(prefs, fingerprint)?.let { return@withTimeoutOrNull it }
                val installation = installation(prefs)
                // Persist before the first POST. A timeout/cancel/invalid receipt keeps the SAME
                // requestId for the next explicit retry, including after a process restart.
                val requestId = pendingRequestId(prefs, fingerprint)
                val body = JSONObject().apply {
                    put("requestId", requestId)
                    put("type", payload.type)
                    put("message", payload.message)
                    if (payload.log != null) put("log", payload.log)
                    put("metadata", JSONObject(payload.metadata))
                }
                val response = authorized(app, prefs, installation) {
                    Request.Builder().url("$BASE_URL/api/v1/tickets")
                        .post(body.toString().toRequestBody(jsonMediaType))
                }
                val receipt = SupportPayloadPolicy.receipt(
                    response.opt("ok"), response.opt("id"), response.opt("code"),
                ) ?: return@withTimeoutOrNull SupportResult.Failure(SupportFailureKind.INVALID_RESPONSE)
                rememberReceipt(prefs, fingerprint, requestId, receipt)
                receipt
            } ?: SupportResult.Failure(SupportFailureKind.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure(error)
        }
    }

    internal sealed interface HistoryResult {
        data class Success(val response: JSONObject) : HistoryResult
        data class Failure(val reason: SupportResult.Failure) : HistoryResult
    }

    internal suspend fun history(context: Context): HistoryResult = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(65_000L) {
                val app = context.applicationContext
                val prefs = preferences(app)
                val response = authorized(app, prefs, installation(prefs)) {
                    Request.Builder().url("$BASE_URL/api/v1/tickets").get()
                }
                if (response.optJSONArray("requests") == null) {
                    HistoryResult.Failure(SupportResult.Failure(SupportFailureKind.INVALID_RESPONSE))
                } else {
                    HistoryResult.Success(response)
                }
            } ?: HistoryResult.Failure(SupportResult.Failure(SupportFailureKind.TIMEOUT))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HistoryResult.Failure(failure(error))
        }
    }

    private suspend fun authorized(
        app: Context,
        prefs: SharedPreferences,
        installation: Installation,
        request: () -> Request.Builder,
    ): JSONObject {
        register(app, prefs, installation, force = false)
        fun build() = request().header("Authorization", "Bearer ${installation.id}.${installation.secret}")
            .header("Accept", "application/json").build()
        var response = execute(httpClient(app), build())
        // A reset server database may have forgotten this installation. Re-register once,
        // retaining both the same identity and requestId. Never retry arbitrary HTTP/IO errors.
        if (response.status == 401) {
            register(app, prefs, installation, force = true)
            response = execute(httpClient(app), build())
        }
        return checkedJson(response)
    }

    private suspend fun register(
        app: Context,
        prefs: SharedPreferences,
        installation: Installation,
        force: Boolean,
    ): Unit = registrationMutex.withLock {
        if (!force && prefs.getBoolean("registered", false)) return@withLock
        val payload = JSONObject().put("installationId", installation.id).put("secret", installation.secret)
        val response = execute(httpClient(app), Request.Builder()
            .url("$BASE_URL/api/v1/installations")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType)).build())
        checkedJson(response)
        if (!prefs.edit().putBoolean("registered", true).commit()) throw StorageFailure()
    }

    private fun preferences(app: Context): SharedPreferences =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class Installation(val id: String, val secret: String)

    private fun installation(prefs: SharedPreferences): Installation = synchronized(prefsLock) {
        val id = prefs.getString("id", null)
        val secret = prefs.getString("secret", null)
        if (id != null || secret != null) {
            if (id == null || secret == null ||
                !Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id) ||
                !Regex("[A-Za-z0-9_-]{43}").matches(secret)) throw StorageFailure()
            return@synchronized Installation(id, secret)
        }
        val created = Installation(
            UUID.randomUUID().toString(),
            Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
        )
        if (!prefs.edit().putString("id", created.id).putString("secret", created.secret)
                .putBoolean("registered", false).commit()) throw StorageFailure()
        created
    }

    private fun pendingRequestId(prefs: SharedPreferences, fingerprint: String): String = synchronized(prefsLock) {
        val key = PENDING_PREFIX + fingerprint
        prefs.getString(key, null)?.let { return@synchronized it }
        // Do not silently evict uncertain sends: that would defeat server-side deduplication.
        if (prefs.all.keys.count { it.startsWith(PENDING_PREFIX) } >= MAX_PENDING_IDS) throw StorageFailure()
        UUID.randomUUID().toString().also {
            if (!prefs.edit().putString(key, it).commit()) throw StorageFailure()
        }
    }

    private fun cachedReceipt(prefs: SharedPreferences, fingerprint: String): SupportResult.Success? =
        synchronized(prefsLock) {
            SupportPayloadPolicy.cachedReceipt(
                prefs.getString(RECEIPT_PREFIX + fingerprint, null), System.currentTimeMillis(),
            )?.receipt
        }

    private fun rememberReceipt(
        prefs: SharedPreferences,
        fingerprint: String,
        requestId: String,
        receipt: SupportResult.Success,
    ): Unit = synchronized(prefsLock) {
        val now = System.currentTimeMillis()
        val key = RECEIPT_PREFIX + fingerprint
        val entries = prefs.all.filterKeys { it.startsWith(RECEIPT_PREFIX) }
        val live = entries.mapNotNull { (entryKey, value) ->
            SupportPayloadPolicy.cachedReceipt(value as? String, now)?.let { entryKey to it.recordedAt }
        }.filter { it.first != key }.sortedByDescending { it.second }
        val keep = live.take(MAX_CACHED_RECEIPTS - 1).map { it.first }.toSet()
        val edit = prefs.edit()
        entries.keys.filter { it !in keep && it != key }.forEach { edit.remove(it) }
        edit.putString(key, SupportPayloadPolicy.CachedReceipt(now, receipt).encode())
        // Two commits intentionally: if receipt persistence fails, the pending identity
        // remains on disk AND in memory so an uncertain retry cannot create a duplicate.
        if (edit.commit() && prefs.getString(PENDING_PREFIX + fingerprint, null) == requestId) {
            prefs.edit().remove(PENDING_PREFIX + fingerprint).commit()
        }
    }

    private fun deviceMetadata(): Map<String, Any?> = mapOf(
        "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
        "androidVersion" to Build.VERSION.RELEASE, "apiLevel" to Build.VERSION.SDK_INT,
        "appVersion" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE,
    )

    private suspend fun knownSecrets(): List<String> = try {
        ServiceLocator.settings.getSourceConfig()?.let { listOf(it.username, it.password) }.orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    private fun httpClient(app: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: buildHttpClient(app).also { client = it }
    }

    private fun buildHttpClient(app: Context): OkHttpClient {
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val root = app.resources.openRawResource(R.raw.support_isrg_root_x1).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null); setCertificateEntry("isrg-root-x1", root)
        }
        val fallback = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(anchors)
        }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (original: CertificateException) {
                    try { fallback.checkServerTrusted(chain, authType) }
                    catch (secondary: CertificateException) { original.addSuppressed(secondary); throw original }
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + fallback.acceptedIssuers
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust) // Default hostname/SAN verification remains enabled.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private data class HttpResult(val status: Int, val json: Boolean, val body: String?)

    private suspend fun execute(client: OkHttpClient, request: Request): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val body = it.body
                            val subtype = body?.contentType()?.subtype.orEmpty()
                            val isJson = subtype == "json" || subtype.endsWith("+json")
                            if (it.code !in 200..299) return@use HttpResult(it.code, isJson, null)
                            if (!isJson || body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
                                return@use HttpResult(it.code, false, null)
                            }
                            val source = body.source()
                            source.request(MAX_RESPONSE_BYTES + 1)
                            if (source.buffer.size > MAX_RESPONSE_BYTES) HttpResult(it.code, false, null)
                            else HttpResult(it.code, true, source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    private fun checkedJson(response: HttpResult): JSONObject {
        if (response.status !in 200..299) {
            throw SupportFailure(SupportResult.Failure(SupportFailureKind.HTTP, response.status))
        }
        val json = if (response.json) runCatching { JSONObject(response.body.orEmpty()) }.getOrNull() else null
        if (json == null || json.opt("ok") != true) {
            throw SupportFailure(SupportResult.Failure(SupportFailureKind.INVALID_RESPONSE))
        }
        return json
    }

    private class StorageFailure : IOException()
    private class SupportFailure(val reason: SupportResult.Failure) : IOException()

    private fun failure(error: Exception): SupportResult.Failure = when (error) {
        is SupportFailure -> error.reason
        is StorageFailure -> SupportResult.Failure(SupportFailureKind.STORAGE)
        is SSLException, is CertificateException -> SupportResult.Failure(SupportFailureKind.TLS)
        is UnknownHostException -> SupportResult.Failure(SupportFailureKind.DNS)
        is SocketTimeoutException, is InterruptedIOException -> SupportResult.Failure(SupportFailureKind.TIMEOUT)
        is IOException -> SupportResult.Failure(SupportFailureKind.NETWORK)
        else -> SupportResult.Failure(SupportFailureKind.UNKNOWN)
    }
}
