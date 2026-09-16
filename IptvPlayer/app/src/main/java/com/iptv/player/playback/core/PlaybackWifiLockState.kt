package com.iptv.player.playback.core

/**
 * Pure hold/release state for a system performance lock that must be held only
 * while some playback session is open. The gate already reference-counts the
 * owners, so this only needs to turn its busy/idle edges into at most one
 * acquire and one matching release, and to tolerate an acquire that fails
 * (no Wi-Fi radio on an Ethernet-only box) by simply retrying on the next edge.
 */
class PlaybackWifiLockState(
    private val acquire: () -> Boolean,
    private val release: () -> Unit,
) {
    var held: Boolean = false
        private set

    @Synchronized
    fun onPlaybackActivity(active: Boolean) {
        if (active) {
            if (held) return
            held = runCatching(acquire).getOrDefault(false)
        } else {
            if (!held) return
            held = false
            runCatching(release)
        }
    }
}
