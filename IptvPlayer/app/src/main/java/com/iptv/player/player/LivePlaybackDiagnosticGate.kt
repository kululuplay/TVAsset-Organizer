package com.iptv.player.player

import java.util.ArrayDeque

/**
 * Bounds optional live-playback diagnostics without changing playback decisions.
 *
 * Callers supply monotonic time and fixed symbolic event names, never media URLs,
 * channel names, or other source identity. Counters include throttled events so a
 * fast READY/BUFFERING oscillation remains visible in the next emitted snapshot.
 * At most four diagnostics per rolling second and 480 per session are emitted.
 */
internal class LivePlaybackDiagnosticGate {
    data class Decision(
        /** Events suppressed since the preceding permitted diagnostic. */
        val suppressed: Int,
        /** Detached, cumulative session counters, including this event. */
        val eventCounts: Map<String, Int>,
    )

    private var active = false
    private var sessionStartedMs = 0L
    private var lastNowMs = 0L
    private var suppressed = 0
    private val emittedTimes = ArrayDeque<Long>()
    private val lastEmittedByEvent = mutableMapOf<String, Long>()
    private val eventCounts = linkedMapOf<String, Int>()

    @Synchronized
    fun beginSession(nowMs: Long) {
        active = true
        sessionStartedMs = nowMs.coerceAtLeast(0L)
        lastNowMs = sessionStartedMs
        suppressed = 0
        emittedTimes.clear()
        lastEmittedByEvent.clear()
        eventCounts.clear()
    }

    /** Expiry is permanent until [beginSession], even if the supplied clock retreats. */
    @Synchronized
    fun isActive(nowMs: Long): Boolean = activeTime(nowMs) != null

    @Synchronized
    fun record(event: String, nowMs: Long): Decision? {
        val now = activeTime(nowMs) ?: return null
        val key = boundedEventKey(event)
        eventCounts[key] = increment(eventCounts[key] ?: 0)

        while (true) {
            val earliest = emittedTimes.peekFirst() ?: break
            if (now - earliest < WINDOW_MS) break
            emittedTimes.removeFirst()
        }
        val previous = lastEmittedByEvent[key]
        if (
            emittedTimes.size >= MAX_EVENTS_PER_WINDOW ||
            (previous != null && now - previous < DUPLICATE_INTERVAL_MS)
        ) {
            suppressed = increment(suppressed)
            return null
        }

        emittedTimes.addLast(now)
        lastEmittedByEvent[key] = now
        val decision = Decision(suppressed = suppressed, eventCounts = eventCounts.toMap())
        suppressed = 0
        return decision
    }

    private fun activeTime(nowMs: Long): Long? {
        if (!active) return null
        // Clamp clock regressions so they cannot renew a budget or session lifetime.
        val now = nowMs.coerceAtLeast(lastNowMs)
        lastNowMs = now
        if (now - sessionStartedMs >= SESSION_DURATION_MS) {
            active = false
            return null
        }
        return now
    }

    private fun boundedEventKey(event: String): String {
        // Syntax checks reject URL-shaped input; callers must still use fixed keys.
        if (event.length !in 1..MAX_KEY_LENGTH || !EVENT_KEY.matches(event)) return OTHER_EVENT
        if (event in eventCounts || eventCounts.size < MAX_EVENT_KEYS - 1) return event
        return OTHER_EVENT
    }

    private fun increment(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private companion object {
        private const val SESSION_DURATION_MS = 120_000L
        private const val WINDOW_MS = 1_000L
        private const val MAX_EVENTS_PER_WINDOW = 4
        private const val DUPLICATE_INTERVAL_MS = 250L
        private const val MAX_EVENT_KEYS = 32
        private const val MAX_KEY_LENGTH = 48
        private const val OTHER_EVENT = "other"
        private val EVENT_KEY = Regex("[A-Za-z][A-Za-z0-9_]*")
    }
}
