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
import com.iptv.player.player.PlayerController
import com.iptv.player.util.AppInfo
import com.iptv.player.util.RetryInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

    /**
     * Transient hand-off slot for a live preview's PlayerController that is being
     * promoted to the fullscreen player. HomeActivity parks the still-playing
     * controller here and launches PlayerActivity in "adopt" mode; PlayerActivity
     * consumes it (rebinding the engine to its own surface) so going fullscreen
     * never tears down and reconnects the single stream. Cleared on consume, so at
     * most one controller is ever parked and it is owned by exactly one screen.
     */
    @Volatile private var pendingLiveController: PlayerController? = null

    /** Park a live controller for the fullscreen player to adopt. */
    fun handOverLiveController(controller: PlayerController) {
        pendingLiveController = controller
    }

    /** Take (and clear) the parked controller, or null if none was handed over. */
    fun consumePendingLiveController(): PlayerController? {
        val controller = pendingLiveController
        pendingLiveController = null
        return controller
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext

            val logging = HttpLoggingInterceptor().apply {
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
                .build()

            val retrofitBuilder = Retrofit.Builder()
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create())

            val db = AppDatabase.build(app)
            settings = SettingsStore(app)
            repository = IptvRepository(db, httpClient, retrofitBuilder, settings)
            initialized = true
        }
    }
}
