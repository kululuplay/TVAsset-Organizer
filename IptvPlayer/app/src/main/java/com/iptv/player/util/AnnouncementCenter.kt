/*
 * AnnouncementCenter.kt
 * Holds the latest operator announcement pushed from the crash-receiver via the
 * heartbeat response. IptvApp watches this and shows the message once per id, on a
 * safe (non-player) screen. A tiny in-memory holder — the heartbeat refreshes it
 * every minute; "already shown" dedup is persisted in SettingsStore.
 */
package com.iptv.player.util

object AnnouncementCenter {

    data class Announcement(val id: Long, val message: String)

    @Volatile
    var pending: Announcement? = null
        private set

    @Volatile private var listener: ((Announcement) -> Unit)? = null

    /** IptvApp registers here to surface a freshly-arrived announcement promptly. */
    fun setListener(l: ((Announcement) -> Unit)?) {
        listener = l
    }

    /** Called from the heartbeat loop with the server's current announcement. */
    fun update(id: Long, message: String) {
        val msg = message.trim()
        if (msg.isEmpty()) {
            pending = null
            return
        }
        val next = Announcement(id, msg)
        val isNew = next != pending
        pending = next
        if (isNew) runCatching { listener?.invoke(next) }
    }

    /** Server reports no active announcement. */
    fun clear() {
        pending = null
    }
}
