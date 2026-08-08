package com.iptv.player.playback.core

import java.util.ArrayDeque
import java.util.UUID

/** Opaque UUID; content IDs, URLs and credential-bearing labels cannot be used. */
@JvmInline
value class PlaybackSessionId private constructor(val value: String) {
    companion object {
        private val FORMAT = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )

        fun from(value: String): PlaybackSessionId {
            require(FORMAT.matches(value)) { "Playback session ID must be an opaque UUID" }
            return PlaybackSessionId(value)
        }

        fun random(): PlaybackSessionId = from(UUID.randomUUID().toString())
    }
}

enum class PlaybackContentKind {
    LIVE_TV,
    RADIO,
    VOD_MOVIE,
    VOD_EPISODE,
    CATCH_UP,
}

enum class PlaybackEngineKind {
    EXO_PLAYER,
    VLC,
    UNKNOWN,
}

enum class PlaybackTransportKind {
    HLS,
    MPEG_TS,
    DASH,
    PROGRESSIVE,
    UNKNOWN,
}

enum class PlaybackEndReason {
    USER_STOP,
    COMPLETED,
    REPLACED,
    BACKGROUND,
    FATAL_FAILURE,
    APP_SHUTDOWN,
}

data class PlaybackSession(
    val id: PlaybackSessionId,
    val kind: PlaybackContentKind,
    val startedAtEpochMs: Long,
    val initialEngine: PlaybackEngineKind,
    val transport: PlaybackTransportKind,
    val capabilityFingerprint: CapabilityFingerprint? = null,
) {
    init {
        require(startedAtEpochMs >= 0L) { "startedAtEpochMs must not be negative" }
    }
}

/** Immutable aggregate suitable for a disk spool or heartbeat payload. */
data class PlaybackQoeRecord(
    val session: PlaybackSession,
    val finalEngine: PlaybackEngineKind,
    val endedAtEpochMs: Long?,
    val endReason: PlaybackEndReason?,
    val sessionDurationMs: Long,
    val timeToReadyMs: Long?,
    val timeToFirstFrameMs: Long?,
    val rebufferCount: Int,
    val rebufferDurationMs: Long,
    val engineSwitchCount: Int,
    val renderedFrames: Long,
    val droppedFrames: Long,
    val failures: List<PlaybackFailure>,
    val discardedFailureCount: Int,
    val isFinal: Boolean,
) {
    /**
     * A deliberately closed, URL-free field set. No content title, request URI,
     * account identifier, exception message or native log text can enter it.
     */
    fun toSafeFields(): Map<String, Any> = buildMap {
        put("schema", 1)
        put("session_id", session.id.value)
        put("content_kind", session.kind.name)
        put("started_at_epoch_ms", session.startedAtEpochMs)
        put("initial_engine", session.initialEngine.name)
        put("final_engine", finalEngine.name)
        put("transport", session.transport.name)
        session.capabilityFingerprint?.let { put("capability_fingerprint", it.value) }
        endedAtEpochMs?.let { put("ended_at_epoch_ms", it) }
        endReason?.let { put("end_reason", it.name) }
        put("session_duration_ms", sessionDurationMs)
        timeToReadyMs?.let { put("time_to_ready_ms", it) }
        timeToFirstFrameMs?.let { put("time_to_first_frame_ms", it) }
        put("rebuffer_count", rebufferCount)
        put("rebuffer_duration_ms", rebufferDurationMs)
        put("engine_switch_count", engineSwitchCount)
        put("rendered_frames", renderedFrames)
        put("dropped_frames", droppedFrames)
        put("failure_codes", failures.joinToString(",") { it.code.name })
        put("failure_categories", failures.joinToString(",") { it.category.name })
        put("failure_phases", failures.joinToString(",") { it.phase.name })
        put("failure_components", failures.joinToString(",") { it.component.name })
        put("failure_retry_advice", failures.joinToString(",") { it.retryAdvice.name })
        put("failure_http_statuses", failures.joinToString(",") { it.httpStatus?.toString().orEmpty() })
        put("discarded_failure_count", discardedFailureCount)
        put("final", isFinal)
    }
}

fun interface PlaybackMonotonicClock {
    fun nowMs(): Long
}

/**
 * Thread-safe and bounded in-memory QoE accumulator shared by Live and VOD.
 *
 * It intentionally accepts structured values only. Native error text remains in
 * the existing redacted local log and cannot accidentally be uploaded here.
 */
class PlaybackQoeRecorder(
    private val clock: PlaybackMonotonicClock = PlaybackMonotonicClock {
        System.nanoTime() / NANOS_PER_MILLISECOND
    },
    private val maxActiveSessions: Int = DEFAULT_MAX_ACTIVE_SESSIONS,
    private val maxCompletedSessions: Int = DEFAULT_MAX_COMPLETED_SESSIONS,
    private val maxFailuresPerSession: Int = DEFAULT_MAX_FAILURES_PER_SESSION,
) {
    init {
        require(maxActiveSessions in 1..100) { "maxActiveSessions must be in 1..100" }
        require(maxCompletedSessions in 1..500) { "maxCompletedSessions must be in 1..500" }
        require(maxFailuresPerSession in 1..50) { "maxFailuresPerSession must be in 1..50" }
    }

    private val lock = Any()
    private val active = LinkedHashMap<PlaybackSessionId, MutableSession>()
    private val completed = ArrayDeque<PlaybackQoeRecord>(maxCompletedSessions)

    /** Returns false for a duplicate active session ID. */
    fun start(session: PlaybackSession): Boolean = synchronized(lock) {
        if (active.containsKey(session.id)) return@synchronized false
        // A missed Activity/player teardown must not make this process-local
        // telemetry helper an unbounded memory sink. New measurements degrade to
        // a no-op until an existing session closes; playback itself is unaffected.
        if (active.size >= maxActiveSessions) return@synchronized false
        active[session.id] = MutableSession(session = session, startedAtMs = clock.nowMs())
        true
    }

    fun markEngine(sessionId: PlaybackSessionId, engine: PlaybackEngineKind): Boolean =
        mutate(sessionId) { state ->
            if (state.currentEngine != engine) {
                val discoveredInitialEngine = state.currentEngine == PlaybackEngineKind.UNKNOWN
                state.currentEngine = engine
                if (!discoveredInitialEngine) state.engineSwitchCount += 1
            }
        }

    /** Updates the resolved transport without accepting a URL or free-form label. */
    fun markTransport(
        sessionId: PlaybackSessionId,
        transport: PlaybackTransportKind,
    ): Boolean = mutate(sessionId) { state ->
        state.currentTransport = transport
    }

    fun markReady(sessionId: PlaybackSessionId): Boolean = mutate(sessionId) { state ->
        if (state.readyAtMs == null) state.readyAtMs = clock.nowMs()
    }

    fun markFirstFrame(sessionId: PlaybackSessionId): Boolean = mutate(sessionId) { state ->
        if (state.firstFrameAtMs == null) state.firstFrameAtMs = clock.nowMs()
    }

    /** Startup buffering is not counted as a rebuffer until a first frame exists. */
    fun setRebuffering(sessionId: PlaybackSessionId, rebuffering: Boolean): Boolean =
        mutate(sessionId) { state ->
            val now = clock.nowMs()
            if (rebuffering) {
                if (state.firstFrameAtMs != null && state.rebufferStartedAtMs == null) {
                    state.rebufferStartedAtMs = now
                    state.rebufferCount += 1
                }
            } else {
                state.closeRebuffer(now)
            }
        }

    fun addFrameCounters(
        sessionId: PlaybackSessionId,
        renderedDelta: Long,
        droppedDelta: Long,
    ): Boolean {
        require(renderedDelta >= 0L) { "renderedDelta must not be negative" }
        require(droppedDelta >= 0L) { "droppedDelta must not be negative" }
        return mutate(sessionId) { state ->
            state.renderedFrames = saturatingAdd(state.renderedFrames, renderedDelta)
            state.droppedFrames = saturatingAdd(state.droppedFrames, droppedDelta)
        }
    }

    fun recordFailure(
        sessionId: PlaybackSessionId,
        failure: PlaybackFailure,
    ): Boolean = mutate(sessionId) { state ->
        if (state.failures.size == maxFailuresPerSession) {
            state.failures.removeFirst()
            state.discardedFailureCount += 1
        }
        state.failures.addLast(failure)
    }

    fun snapshotActive(sessionId: PlaybackSessionId): PlaybackQoeRecord? = synchronized(lock) {
        active[sessionId]?.snapshot(nowMs = clock.nowMs(), isFinal = false)
    }

    fun finish(
        sessionId: PlaybackSessionId,
        reason: PlaybackEndReason,
        endedAtEpochMs: Long,
    ): PlaybackQoeRecord? = synchronized(lock) {
        require(endedAtEpochMs >= 0L) { "endedAtEpochMs must not be negative" }
        val state = active.remove(sessionId) ?: return@synchronized null
        val now = clock.nowMs()
        state.closeRebuffer(now)
        val record = state.snapshot(
            nowMs = now,
            isFinal = true,
            endedAtEpochMs = endedAtEpochMs,
            endReason = reason,
        )
        completed.addLast(record)
        while (completed.size > maxCompletedSessions) completed.removeFirst()
        record
    }

    fun completedSnapshot(): List<PlaybackQoeRecord> = synchronized(lock) { completed.toList() }

    fun drainCompleted(max: Int): List<PlaybackQoeRecord> {
        require(max >= 0) { "max must not be negative" }
        return synchronized(lock) {
            buildList(minOf(max, completed.size)) {
                repeat(minOf(max, completed.size)) { add(completed.removeFirst()) }
            }
        }
    }

    private inline fun mutate(
        sessionId: PlaybackSessionId,
        action: (MutableSession) -> Unit,
    ): Boolean = synchronized(lock) {
        val state = active[sessionId] ?: return@synchronized false
        action(state)
        true
    }

    private data class MutableSession(
        val session: PlaybackSession,
        val startedAtMs: Long,
        var currentEngine: PlaybackEngineKind = session.initialEngine,
        var currentTransport: PlaybackTransportKind = session.transport,
        var readyAtMs: Long? = null,
        var firstFrameAtMs: Long? = null,
        var rebufferStartedAtMs: Long? = null,
        var rebufferDurationMs: Long = 0L,
        var rebufferCount: Int = 0,
        var engineSwitchCount: Int = 0,
        var renderedFrames: Long = 0L,
        var droppedFrames: Long = 0L,
        val failures: ArrayDeque<PlaybackFailure> = ArrayDeque(),
        var discardedFailureCount: Int = 0,
    ) {
        fun closeRebuffer(nowMs: Long) {
            val started = rebufferStartedAtMs ?: return
            rebufferDurationMs = saturatingAdd(rebufferDurationMs, duration(started, nowMs))
            rebufferStartedAtMs = null
        }

        fun snapshot(
            nowMs: Long,
            isFinal: Boolean,
            endedAtEpochMs: Long? = null,
            endReason: PlaybackEndReason? = null,
        ): PlaybackQoeRecord {
            val activeRebufferMs = rebufferStartedAtMs?.let { duration(it, nowMs) } ?: 0L
            return PlaybackQoeRecord(
                session = session.copy(transport = currentTransport),
                finalEngine = currentEngine,
                endedAtEpochMs = endedAtEpochMs,
                endReason = endReason,
                sessionDurationMs = duration(startedAtMs, nowMs),
                timeToReadyMs = readyAtMs?.let { duration(startedAtMs, it) },
                timeToFirstFrameMs = firstFrameAtMs?.let { duration(startedAtMs, it) },
                rebufferCount = rebufferCount,
                rebufferDurationMs = saturatingAdd(rebufferDurationMs, activeRebufferMs),
                engineSwitchCount = engineSwitchCount,
                renderedFrames = renderedFrames,
                droppedFrames = droppedFrames,
                failures = failures.toList(),
                discardedFailureCount = discardedFailureCount,
                isFinal = isFinal,
            )
        }
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val DEFAULT_MAX_ACTIVE_SESSIONS = 16
        private const val DEFAULT_MAX_COMPLETED_SESSIONS = 50
        private const val DEFAULT_MAX_FAILURES_PER_SESSION = 8

        private fun duration(startMs: Long, endMs: Long): Long = (endMs - startMs).coerceAtLeast(0L)

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
