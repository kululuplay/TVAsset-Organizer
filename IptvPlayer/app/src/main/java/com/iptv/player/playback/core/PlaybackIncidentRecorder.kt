package com.iptv.player.playback.core

import java.util.ArrayDeque
import java.util.UUID

enum class PlaybackIncidentTrigger { STARTUP_SLOW, BUFFERING, VIDEO_STALL, PLAYBACK_ERROR, USER_REPORT }

data class PlaybackIncident(
    val incidentId: String,
    val sessionId: String,
    val contentKey: String?,
    val trigger: PlaybackIncidentTrigger,
    val triggeredAtMs: Long,
    val complete: Boolean,
    val points: List<Map<String, Any>>,
    val truncated: Boolean = false,
) {
    fun toSafeFields(): Map<String, Any> = buildMap {
        put("incidentId", incidentId); put("sessionId", sessionId)
        contentKey?.let { put("contentKey", it) }
        put("trigger", trigger.name); put("triggeredAtMs", triggeredAtMs)
        put("complete", complete); put("points", points); put("truncated", truncated)
    }
}

/** Bounded incident windows derived from existing QoE snapshots; no native/player calls. */
class PlaybackIncidentRecorder(
    private val maxSessions: Int = 16,
    private val beforeMs: Long = 20_000,
    private val afterMs: Long = 10_000,
    private val cooldownMs: Long = 120_000,
) {
    init { require(maxSessions in 1..16); require(beforeMs in 0..30_000); require(afterMs in 0..30_000) }
    private data class Point(val at: Long, val fields: Map<String, Any>)
    private data class Pending(val id: String, val trigger: PlaybackIncidentTrigger, val at: Long,
        val epoch: Long, val key: String?, val points: ArrayDeque<Point>, var truncated: Boolean = false)
    private data class Session(val history: ArrayDeque<Point> = ArrayDeque(), var pending: Pending? = null,
        val lastTriggers: MutableMap<PlaybackIncidentTrigger, Long> = mutableMapOf(),
        var lastUserId: String? = null, var lastUserAt: Long = Long.MIN_VALUE,
        var lastPointBucket: Long? = null, var lastAt: Long = Long.MIN_VALUE,
        var lastDuration: Long = 0, var closed: Boolean = false)
    private val sessions = LinkedHashMap<String, Session>()
    private val emitted = ArrayDeque<PlaybackIncident>()

    @Synchronized fun observe(record: PlaybackQoeRecord, contentKey: String?, now: Long, epoch: Long) {
        val state = state(record.session.id.value)
        // A main-thread terminal event may overtake an already captured IO sampler snapshot.
        if (now < state.lastAt || record.sessionDurationMs < state.lastDuration || (state.closed && !record.isFinal)) return
        state.lastAt = now
        state.lastDuration = record.sessionDurationMs
        state.closed = record.isFinal
        val point = point(record, now)
        val bucket = now / 2_000
        if (state.history.lastOrNull()?.at == now) {
            state.history.removeLast(); state.history.addLast(point)
        } else if (state.lastPointBucket != bucket || record.isFinal) {
            state.history.addLast(point)
            state.lastPointBucket = bucket
        } else if (state.history.isNotEmpty()) {
            state.history.removeLast(); state.history.addLast(point)
        }
        while (state.history.isNotEmpty() && (state.history.first.at < now - beforeMs || state.history.size > MAX_POINTS)) state.history.removeFirst()
        state.pending?.let { pending ->
            if (now > (pending.points.lastOrNull()?.at ?: Long.MIN_VALUE) &&
                (pending.points.lastOrNull()?.at?.div(2_000) != bucket || record.isFinal || record.observedState == PlaybackObservedState.PAUSED)) {
                pending.points.addLast(point)
            }
            while (pending.points.size > MAX_POINTS) { pending.points.removeFirst(); pending.truncated = true }
            if (now - pending.at >= afterMs || record.isFinal || record.observedState == PlaybackObservedState.PAUSED) {
                publish(record.session.id.value, pending, true, early = now - pending.at < afterMs)
                state.pending = null
            }
        }
        if (!record.isFinal && record.observedState != PlaybackObservedState.PAUSED) {
            val trigger = when {
                record.observedState == PlaybackObservedState.STARTING && record.stateDurationMs >= 15_000 -> PlaybackIncidentTrigger.STARTUP_SLOW
                record.observedState == PlaybackObservedState.BUFFERING && record.stateDurationMs >= 5_000 -> PlaybackIncidentTrigger.BUFFERING
                record.observedState == PlaybackObservedState.PLAYING && record.session.kind != PlaybackContentKind.RADIO &&
                    record.framesKnown && (record.lastFrameAgeMs ?: -1) >= 8_000 -> PlaybackIncidentTrigger.VIDEO_STALL
                else -> null
            }
            if (trigger != null) trigger(record, contentKey, trigger, now, epoch)
        }
    }

    @Synchronized fun trigger(record: PlaybackQoeRecord, key: String?, trigger: PlaybackIncidentTrigger, now: Long, epoch: Long): String? {
        val id = record.session.id.value
        val state = state(id)
        if (trigger == PlaybackIncidentTrigger.USER_REPORT && state.lastUserId != null && now - state.lastUserAt in 0 until 60_000) return state.lastUserId
        val last = state.lastTriggers[trigger]
        if (trigger != PlaybackIncidentTrigger.USER_REPORT && last != null && now - last in 0 until cooldownMs) return null
        val old = state.pending
        if (old != null) {
            if (trigger != PlaybackIncidentTrigger.USER_REPORT) return old.id
            publish(id, old, true, early = true)
        }
        val points = ArrayDeque(state.history.filter { it.at >= now - beforeMs })
        // The IO sampler can advance between an explicit UI report's snapshot and this lock.
        // Retain its newer actual counters instead of appending a regressing older snapshot.
        val at = maxOf(now, state.lastAt)
        if (points.lastOrNull()?.at != at && record.sessionDurationMs >= state.lastDuration) points.addLast(point(record, at))
        val pending = Pending(UUID.randomUUID().toString(), trigger, at, epoch, key, points)
        state.pending = pending
        state.lastTriggers[trigger] = now
        if (trigger == PlaybackIncidentTrigger.USER_REPORT) { state.lastUserAt = now; state.lastUserId = pending.id }
        publish(id, pending, record.isFinal, early = record.isFinal)
        if (record.isFinal) state.pending = null
        return pending.id
    }

    @Synchronized fun flush(id: PlaybackSessionId) {
        val state = sessions[id.value] ?: return
        state.pending?.let { publish(id.value, it, true, early = true) }
        state.pending = null
    }

    @Synchronized fun drain(): List<PlaybackIncident> = emitted.toList().also { emitted.clear() }
    @Synchronized fun trackedSessionCount(): Int = sessions.size

    private fun state(id: String): Session = sessions.getOrPut(id) {
        while (sessions.size >= maxSessions) {
            val first = sessions.entries.first()
            first.value.pending?.let { publish(first.key, it, true, early = true) }
            sessions.remove(first.key)
        }
        Session()
    }

    private fun publish(id: String, pending: Pending, complete: Boolean, early: Boolean = false) {
        val points = pending.points.filter { it.at - pending.at in -30_000L..30_000L }.takeLast(MAX_POINTS).map { point ->
            point.fields + ("offsetMs" to (point.at - pending.at))
        }
        emitted.addLast(PlaybackIncident(pending.id, id, pending.key, pending.trigger, pending.epoch, complete, points,
            pending.truncated || early || points.size < pending.points.size))
        while (emitted.size > 32) emitted.removeFirst()
    }

    private fun point(record: PlaybackQoeRecord, now: Long) = Point(now, buildMap {
        put("state", record.observedState.name); put("stateDurationMs", record.stateDurationMs)
        put("sessionDurationMs", record.sessionDurationMs); put("rebufferCount", record.rebufferCount)
        put("rebufferDurationMs", record.rebufferDurationMs); put("framesKnown", record.framesKnown)
        if (record.framesKnown) put("renderedFrames", record.renderedFrames)
        if (record.droppedFramesKnown) put("droppedFrames", record.droppedFrames)
        record.lastFrameAgeMs?.let { put("lastFrameAgeMs", it) }
        record.currentBufferMs?.let { put("currentBufferMs", it) }
    })
    companion object { const val MAX_POINTS = 31 }
}
