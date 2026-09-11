package com.iptv.player.ui.player

import kotlin.math.abs

/** Keeps the requested position stable while a network player completes a seek. */
internal class SeekTimeline {
    var targetMs: Long? = null
        private set
    private var requestedAt = 0L
    private var issued = false

    fun request(target: Long, duration: Long, now: Long): Long {
        targetMs = target.coerceIn(0L, duration.coerceAtLeast(0L))
        requestedAt = now
        issued = false
        return targetMs!!
    }

    fun offset(actual: Long, delta: Long, duration: Long, now: Long): Long =
        request((targetMs ?: actual) + delta, duration, now)

    fun markIssued() { issued = true }

    fun display(actual: Long, now: Long): Long {
        val target = targetMs ?: return actual.coerceAtLeast(0L)
        val age = now - requestedAt
        if ((issued && age >= 350L && abs(actual - target) <= 2_000L) || age >= 10_000L) {
            clear()
            return actual.coerceAtLeast(0L)
        }
        return target
    }

    fun clear() { targetMs = null; issued = false }

    companion object {
        fun step(repeatCount: Int): Long = when {
            repeatCount >= 15 -> 60_000L
            repeatCount >= 5 -> 30_000L
            else -> 10_000L
        }
    }
}
