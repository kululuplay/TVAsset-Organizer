/*
 * NetworkSignal.kt
 * Reports a coarse connection-quality level for the dashboard signal chip.
 * Uses the active network's capabilities (validated internet => Good, connected
 * but unvalidated => Weak, nothing => Offline). For Wi-Fi it refines the result
 * with the RSSI signal level so a barely-connected AP reads as Weak.
 */
package com.iptv.player.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

object NetworkSignal {

    enum class Level { GOOD, WEAK, OFFLINE }

    fun current(context: Context): Level {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Level.OFFLINE

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            return if (cm.activeNetworkInfo?.isConnected == true) Level.GOOD else Level.OFFLINE
        }

        val network = cm.activeNetwork ?: return Level.OFFLINE
        val caps = cm.getNetworkCapabilities(network) ?: return Level.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Level.OFFLINE

        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        // Refine Wi-Fi with the RSSI level: weak signal reads as Weak even if validated.
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val bars = runCatching {
                @Suppress("DEPRECATION")
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val rssi = wifi?.connectionInfo?.rssi ?: return@runCatching null
                if (rssi == Int.MIN_VALUE) null
                else WifiManager.calculateSignalLevel(rssi, 5) // 0..4
            }.getOrNull()
            if (bars != null) {
                return when {
                    !validated -> Level.WEAK
                    bars <= 1 -> Level.WEAK
                    else -> Level.GOOD
                }
            }
        }

        return if (validated) Level.GOOD else Level.WEAK
    }
}
