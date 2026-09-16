package com.iptv.player.playback.android

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.iptv.player.playback.core.PlaybackResourceGovernor
import com.iptv.player.playback.core.PlaybackWifiLockState
import com.iptv.player.util.PlaybackLog

/**
 * Holds a Wi-Fi performance lock only while a live/VOD/preview session is open.
 *
 * Old Android TV sticks aggressively power-save the Wi-Fi radio (short DTIM
 * wake-ups, low-latency mode off), which shows up as periodic rebuffers on
 * otherwise healthy streams. The lock is keyed to [PlaybackResourceGovernor]
 * busy/idle edges, so nothing is held while nothing plays. Install once from
 * the Application; every step is best-effort because WifiManager may be null
 * (Ethernet-only boxes) or refuse the lock on vendor builds.
 */
object PlaybackWifiLock {

    @Volatile private var installed = false

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is the only option before API 29.
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val app = context.applicationContext
        val lock: WifiManager.WifiLock? = runCatching {
            val manager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@runCatching null
            // LOW_LATENCY (API 29+) also disables Wi-Fi power save on supported
            // chipsets; HIGH_PERF is the best older builds offer.
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            manager.createWifiLock(mode, "kululu:playback").apply {
                setReferenceCounted(false)
            }
        }.getOrNull()
        if (lock == null) {
            PlaybackLog.log(app, "WifiLock", "unavailable (no WifiManager) -> skipped")
            return
        }
        val state = PlaybackWifiLockState(
            acquire = {
                lock.acquire()
                PlaybackLog.log(app, "WifiLock", "acquired")
                lock.isHeld
            },
            release = {
                if (lock.isHeld) lock.release()
                PlaybackLog.log(app, "WifiLock", "released")
            },
        )
        // Process-lifetime registration; the handle is intentionally never closed.
        PlaybackResourceGovernor.addActivityListener(state::onPlaybackActivity)
    }
}
