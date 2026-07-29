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

import java.util.Locale

object ResolvedRequestCenter {

    data class ResolvedRequest(val id: Long, val type: String, val message: String)

    private const val MAX_PENDING = 10
    private const val MAX_SHOWN_IDS = 256
    private const val MAX_RESOLVED_MESSAGE_LENGTH = 200
    private val TYPES = setOf("channel", "movie", "series", "complaint")

    private val lock = Any()

    @Volatile
    var pending: List<ResolvedRequest> = emptyList()
        private set

    /** Ids already popped this process, so a re-delivery (failed ack) won't re-show. */
    private val shownIds = linkedSetOf<Long>()

    @Volatile private var listener: (() -> Unit)? = null

    /** IptvApp registers here to surface a freshly-arrived resolution promptly. */
    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    /** Called from the heartbeat loop with the server's current un-acked list. */
    fun update(list: List<ResolvedRequest>) {
        val safeList = list.asSequence()
            .filter { it.id > 0L }
            .distinctBy { it.id }
            .map {
                ResolvedRequest(
                    id = it.id,
                    type = it.type.lowercase(Locale.ROOT)
                        .takeIf { type -> type in TYPES }
                        ?: "other",
                    message = sanitizeMessage(it.message),
                )
            }
            .take(MAX_PENDING)
            .toList()
        val hasUnshown = synchronized(lock) {
            pending = safeList
            safeList.any { it.id !in shownIds }
        }
        // Listener code can call back into this object, so invoke outside the lock.
        if (hasUnshown) runCatching { listener?.invoke() }
    }

    fun clear() {
        synchronized(lock) {
            pending = emptyList()
        }
    }

    /** The newest pending resolution not yet shown this process, or null. */
    fun nextUnshown(): ResolvedRequest? =
        synchronized(lock) {
            pending.asSequence()
                .filter { it.id !in shownIds }
                .maxByOrNull { it.id }
        }

    fun markShown(id: Long) {
        if (id <= 0L) return
        synchronized(lock) {
            shownIds.add(id)
            while (shownIds.size > MAX_SHOWN_IDS) {
                val oldest = shownIds.firstOrNull() ?: break
                shownIds.remove(oldest)
            }
        }
    }

    /** Ids the server still reports as un-acked but we've already shown — retry ack. */
    fun shownButPending(): List<Long> =
        synchronized(lock) {
            pending.asSequence()
                .map { it.id }
                .filter { it in shownIds }
                .distinct()
                .toList()
        }

    private fun sanitizeMessage(value: String): String =
        value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter {
                (it == '\n' || it == '\t' || !it.isISOControl()) &&
                    !isBidiControl(it)
            }
            .trim()
            .take(MAX_RESOLVED_MESSAGE_LENGTH)

    private fun isBidiControl(value: Char): Boolean =
        value == '\u061C' ||
            value == '\u200E' ||
            value == '\u200F' ||
            value in '\u202A'..'\u202E' ||
            value in '\u2066'..'\u2069'
}
