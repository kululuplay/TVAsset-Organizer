/*
 * ConnectivityWatcher.kt
 * Thin wrapper over ConnectivityManager.registerNetworkCallback (minSdk 21 safe)
 * that collapses per-network callbacks into a single main-thread online/offline
 * signal. Multiple networks (Wi-Fi + Ethernet) are tracked as a set so a handoff
 * between them doesn't flap the state: offline only fires when the LAST network
 * with internet capability is gone. Used by the Dashboard to show the outage
 * banner and auto-resync when the connection returns.
 */
package com.iptv.player.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

class ConnectivityWatcher(
    context: Context,
    private val onChange: (online: Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    // Networks currently reported available; mutated only on the main thread.
    private val networks = HashSet<Network>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Registers the callback. Idempotent; failures are swallowed (best-effort UI). */
    fun start() {
        if (callback != null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    val wasEmpty = networks.isEmpty()
                    networks.add(network)
                    if (wasEmpty) onChange(true)
                }
            }

            override fun onLost(network: Network) {
                main.post {
                    networks.remove(network)
                    if (networks.isEmpty()) onChange(false)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { callback = cb }
            .onFailure { Logger.w("Connectivity", "registerNetworkCallback failed: ${it.message}") }
    }

    /** Unregisters and drops any queued posts so no callback fires after stop. */
    fun stop() {
        val cb = callback ?: return
        callback = null
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
        runCatching { cm?.unregisterNetworkCallback(cb) }
        main.removeCallbacksAndMessages(null)
        networks.clear()
    }
}
