/*
 * IptvApp
 * Application entry point. Wires up the singleton service locator so the rest of
 * the app can grab the database, repository, settings store and OkHttp client
 * without a full DI framework (keeps things lightweight for low-end devices).
 */
package com.iptv.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import com.iptv.player.data.ServiceLocator
import com.iptv.player.util.AnrWatchdog
import com.iptv.player.util.CrashReporter
import com.iptv.player.util.HeartbeatReporter
import com.iptv.player.util.Logger
import com.iptv.player.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IptvApp : Application() {

    private var anrWatchdog: AnrWatchdog? = null

    /** Count of started (foreground) activities; drives heartbeat start/stop. */
    private var startedActivities = 0

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

        // Live-device telemetry: ping the ops panel every minute WHILE foregrounded
        // so we can see who is watching right now (count, IP, model, version).
        // Gated on the started-activity count because Android TV keeps idle
        // processes alive for hours — a bare process loop would over-count "live".
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) HeartbeatReporter.start(this@IptvApp)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) HeartbeatReporter.stop()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

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
