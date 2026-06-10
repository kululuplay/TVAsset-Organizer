/*
 * ResolvedRequestCenter.kt
 * Holds the list of the user's requests the operator just marked "done" on the
 * panel, pushed to this device via the heartbeat response. IptvApp watches this
 * and pops a confirmation once per id, on a safe (non-player) screen, then ACKs
 * the server so it stops re-sending. Mirrors AnnouncementCenter, but:
 *  - dedup lives in an in-memory shownIds set (NOT persisted): the server's
 *    `notified` flag is the durable dedup, so an un-acked id simply re-arrives on
 *    the next heartbeat and we retry the ack without re-showing.
 *  - it carries a list (the operator may resolve several at once).
 */
package com.iptv.player.util

import java.util.Collections

object ResolvedRequestCenter {

    data class ResolvedRequest(val id: Long, val type: String, val message: String)

    @Volatile
    var pending: List<ResolvedRequest> = emptyList()
        private set

    /** Ids already popped this process, so a re-delivery (failed ack) won't re-show. */
    private val shownIds: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    @Volatile private var listener: (() -> Unit)? = null

    /** IptvApp registers here to surface a freshly-arrived resolution promptly. */
    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    /** Called from the heartbeat loop with the server's current un-acked list. */
    fun update(list: List<ResolvedRequest>) {
        pending = list
        if (list.any { it.id !in shownIds }) runCatching { listener?.invoke() }
    }

    fun clear() {
        pending = emptyList()
    }

    /** The newest pending resolution not yet shown this process, or null. */
    fun nextUnshown(): ResolvedRequest? =
        pending.filter { it.id !in shownIds }.maxByOrNull { it.id }

    fun markShown(id: Long) {
        shownIds.add(id)
    }

    /** Ids the server still reports as un-acked but we've already shown — retry ack. */
    fun shownButPending(): List<Long> = pending.map { it.id }.filter { it in shownIds }
}
