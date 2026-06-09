/*
 * IptvApp
 * Application entry point. Wires up the singleton service locator so the rest of
 * the app can grab the database, repository, settings store and OkHttp client
 * without a full DI framework (keeps things lightweight for low-end devices).
 */
package com.iptv.player

import android.app.Application
import android.os.StrictMode
import com.iptv.player.data.ServiceLocator
import com.iptv.player.util.AnrWatchdog
import com.iptv.player.util.CrashReporter
import com.iptv.player.util.Logger
import com.iptv.player.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IptvApp : Application() {

    private var anrWatchdog: AnrWatchdog? = null

    override fun onCreate() {
        super.onCreate()

        // Start logging + crash capture first so any failure during the rest of
        // startup is recorded.
        Logger.init(this)
        if (BuildConfig.DEBUG) enableStrictMode()

        ServiceLocator.init(this)

        // If the last run crashed, quietly ship the captured report so we can see
        // failures on users' devices (Fire TV / Sony) without any interaction.
        CrashReporter.uploadPendingIfAny(this)

        // Watch for main-thread freezes (ANRs) and record their stack to the log.
        anrWatchdog = AnrWatchdog().also { it.start() }

        // Re-establish the periodic background sync if the user enabled it.
        CoroutineScope(Dispatchers.Default).launch {
            val settings = ServiceLocator.settings
            if (settings.isAutoSyncEnabled()) {
                SyncScheduler.schedule(this@IptvApp, settings.getAutoSyncHours())
            }
        }
    }

    /**
     * Debug-only: surface accidental disk/network work on the main thread during
     * development. Logged, not crashed, so it never affects shipped builds.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
    }
}
