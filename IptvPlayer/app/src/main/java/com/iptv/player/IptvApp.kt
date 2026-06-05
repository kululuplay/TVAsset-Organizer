/*
 * IptvApp
 * Application entry point. Wires up the singleton service locator so the rest of
 * the app can grab the database, repository, settings store and OkHttp client
 * without a full DI framework (keeps things lightweight for low-end devices).
 */
package com.iptv.player

import android.app.Application
import com.iptv.player.data.ServiceLocator
import com.iptv.player.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IptvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

        // Re-establish the periodic background sync if the user enabled it.
        CoroutineScope(Dispatchers.Default).launch {
            val settings = ServiceLocator.settings
            if (settings.isAutoSyncEnabled()) {
                SyncScheduler.schedule(this@IptvApp, settings.getAutoSyncHours())
            }
        }
    }
}
