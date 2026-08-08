/*
 * ServiceLocator.kt
 * Minimal manual dependency container. Avoids a full DI framework to keep the
 * APK small and startup fast on weak devices. Initialised once from IptvApp.
 */
package com.iptv.player.data

import android.content.Context
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.repository.IptvRepository
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.AppInfo
import com.iptv.player.util.Logger
import com.iptv.player.util.RetryInterceptor
import com.iptv.player.util.SensitiveDataRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

object ServiceLocator {

    @Volatile private var initialized = false

    lateinit var settings: SettingsStore
        private set
    lateinit var repository: IptvRepository
        private set
    lateinit var httpClient: OkHttpClient
        private set

    /**
     * Application-lifetime coroutine scope. Used for work that must outlive the
     * Activity/ViewModel that started it — notably the splash prefetch, which
     * should keep running in the background after the splash routes onward so an
     * unfinished refresh isn't cancelled. SupervisorJob keeps one failing child
     * from cancelling the others.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext

            val logging = HttpLoggingInterceptor { line ->
                Logger.d("HTTP", SensitiveDataRedactor.redact(line))
            }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            httpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                // Retry transient blips (IO errors / 5xx) with bounded backoff so a
                // flaky portal doesn't surface as an immediate failure. Sits first
                // so the UA + logging interceptors run on every attempt.
                .addInterceptor(RetryInterceptor())
                // Stamp the app identity on REST / update requests, but only when
                // the caller hasn't already chosen its own User-Agent. The speed
                // test deliberately sends a browser UA so CDN/WAF-fronted endpoints
                // (e.g. Cloudflare) don't challenge/block the bare app UA and fail.
                .addInterceptor { chain ->
                    val original = chain.request()
                    val request = if (original.header("User-Agent") == null) {
                        original.newBuilder()
                            .header("User-Agent", AppInfo.USER_AGENT)
                            .build()
                    } else {
                        original
                    }
                    chain.proceed(request)
                }
                .addInterceptor(logging)
                // Network interceptors run for each request sent on the wire,
                // including redirect follow-ups. Never expose the app-owned ingest
                // key over cleartext HTTP while still allowing arbitrary provider
                // and IPTV requests to use HTTP through this shared client.
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.scheme == "http" &&
                        request.header("X-Kululu-Key") != null
                    ) {
                        throw IOException("Refusing to send X-Kululu-Key over cleartext HTTP")
                    }
                    chain.proceed(request)
                }
                .build()

            val retrofitBuilder = Retrofit.Builder()
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create())

            val db = AppDatabase.build(app)
            val secureValues = SecureValueCodec(app)
            settings = SettingsStore(app, secureValues)
            repository = IptvRepository(
                db,
                httpClient,
                retrofitBuilder,
                settings,
                secureValues,
            )
            initialized = true
            appScope.launch {
                runCatching { repository.migrateSensitiveStorage() }
                    .onFailure { Logger.e("SecureStorage", "legacy migration failed", it) }
            }
        }
    }
}
