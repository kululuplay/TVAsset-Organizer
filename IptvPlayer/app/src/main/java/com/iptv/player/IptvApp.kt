/*
 * IptvApp
 * Application entry point. Wires up the singleton service locator so the rest of
 * the app can grab the database, repository, settings store and OkHttp client
 * without a full DI framework (keeps things lightweight for low-end devices).
 */
package com.iptv.player

import android.app.Application
import com.iptv.player.data.ServiceLocator

class IptvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
