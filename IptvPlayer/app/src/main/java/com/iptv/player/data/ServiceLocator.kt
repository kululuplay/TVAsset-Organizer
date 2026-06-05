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
