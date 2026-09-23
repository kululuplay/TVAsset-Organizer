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
    /** Never closed by the UI: swept after [PlaybackQoeRecorder.ABANDON_AFTER_MS] or orphaned by a process death. */
    ABANDONED,
}

enum class PlaybackObservedState { STARTING, PLAYING, BUFFERING, PAUSED, ENDED, FAILED }

/** Optional counters are evidence; an unavailable decoder never becomes zero dropped frames. */
data class PlaybackObservation(
    val source: String,
    val rendered: Long? = null,
    val dropped: Long? = null,
    val bufferMs: Long? = null,
    val paused: Boolean? = null,
    val video: PlaybackVideoFormat? = null,
    val startup: PlaybackStartupTiming? = null,
)

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
    val observedState: PlaybackObservedState = PlaybackObservedState.STARTING,
    val stateDurationMs: Long = 0,
    val pausedDurationMs: Long = 0,
    val framesKnown: Boolean = false,
    val droppedFramesKnown: Boolean = false,
    val lastFrameAgeMs: Long? = null,
    val currentBufferMs: Long? = null,
    val video: PlaybackVideoFormat? = null,
    val startup: PlaybackStartupTiming? = null,
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
        put("state", observedState.name)
        put("state_duration_ms", stateDurationMs)
        put("paused_duration_ms", pausedDurationMs)
        put("frames_known", framesKnown)
        if (framesKnown) put("rendered_frames", renderedFrames)
        if (droppedFramesKnown) put("dropped_frames", droppedFrames)
        lastFrameAgeMs?.let { put("last_frame_age_ms", it) }
        currentBufferMs?.let { put("current_buffer_ms", it) }
        video?.let { putAll(it.toSafeFields()) }
        startup?.let { putAll(it.toSafeFields()) }
        put("failure_codes", failures.joinToString(",") { it.code.name })
        put("failure_categories", failures.joinToString(",") { it.category.name })
        put("failure_phases", failures.joinToString(",") { it.phase.name })
        put("failure_components", failures.joinToString(",") { it.component.name })
        put("failure_retry_advice", failures.joinToString(",") { it.retryAdvice.name })
        put("failure_http_statuses", failures.joinToString(",") { it.httpStatus?.toString().orEmpty() })
        val audioEvidence = failures.mapNotNull(PlaybackFailure::audioEvidence)
        if (audioEvidence.isNotEmpty()) {
            put("audio_failure_codecs", audioEvidence.joinToString(",") { it.codec.name })
            put("audio_failure_decoders", audioEvidence.joinToString(",") { it.decoder.name })
            put("audio_failure_sink_events", audioEvidence.joinToString(",") { it.sinkEvent.name })
            put("audio_failure_output_modes", audioEvidence.joinToString(",") { it.outputMode.name })
        }
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
                state.clearOutputEvidence()
                if (!discoveredInitialEngine) {
                    state.engineSwitchCount += 1
                    state.initialStartupEligible = false
                }
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
        if (state.observedState != PlaybackObservedState.PAUSED) {
            state.transition(PlaybackObservedState.PLAYING, clock.nowMs())
        }
    }

    /** Startup buffering is not counted as a rebuffer until a first frame exists. */
    fun setRebuffering(sessionId: PlaybackSessionId, rebuffering: Boolean): Boolean =
        mutate(sessionId) { state ->
            val now = clock.nowMs()
            if (rebuffering) {
                if (state.observedState != PlaybackObservedState.PAUSED) {
                    state.transition(if (state.firstFrameAtMs == null) PlaybackObservedState.STARTING else PlaybackObservedState.BUFFERING, now)
                }
                if (state.firstFrameAtMs != null && state.rebufferStartedAtMs == null) {
                    state.rebufferStartedAtMs = now
                    state.rebufferCount += 1
                }
            } else {
                state.closeRebuffer(now)
                if (state.observedState == PlaybackObservedState.BUFFERING) {
                    state.transition(if (state.firstFrameAtMs == null) PlaybackObservedState.STARTING else PlaybackObservedState.PLAYING, now)
                }
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
            state.noteObservation(clock.nowMs())
            state.framesKnown = true
            state.droppedFramesKnown = true
            if (renderedDelta > 0) state.lastFrameAtMs = clock.nowMs()
            state.renderedFrames = saturatingAdd(state.renderedFrames, renderedDelta)
            state.droppedFrames = saturatingAdd(state.droppedFrames, droppedDelta)
        }
    }

    /** A decoder restart can retain the same engine enum (for example VLC HW → SW). */
    fun resetOutputEvidence(sessionId: PlaybackSessionId): Boolean = mutate(sessionId) { state ->
        state.clearOutputEvidence()
        state.initialStartupEligible = false
    }

    fun observe(sessionId: PlaybackSessionId, sample: PlaybackObservation): Boolean = mutate(sessionId) { state ->
        val now = clock.nowMs()
        state.noteObservation(now)
        if (sample.source != state.counterSource) {
            state.counterSource = sample.source
            state.previousRendered = null
            state.previousDropped = null
            state.framesKnown = false
            state.droppedFramesKnown = false
            state.lastFrameAtMs = null
        }
        if (sample.rendered == null) {
            state.framesKnown = false
            state.lastFrameAtMs = null
        }
        if (sample.dropped == null) state.droppedFramesKnown = false
        sample.rendered?.takeIf { it >= 0 }?.let { rendered ->
            val previous = state.previousRendered
            val delta = if (previous == null || rendered < previous) rendered else rendered - previous
            state.framesKnown = true
            state.renderedFrames = saturatingAdd(state.renderedFrames, delta)
            if (delta > 0) state.lastFrameAtMs = now
            state.previousRendered = rendered
        }
        sample.dropped?.takeIf { it >= 0 }?.let { dropped ->
            val previous = state.previousDropped
            state.droppedFrames = saturatingAdd(state.droppedFrames, if (previous == null || dropped < previous) dropped else dropped - previous)
            state.droppedFramesKnown = true
            state.previousDropped = dropped
        }
        state.currentBufferMs = sample.bufferMs?.coerceAtLeast(0)
        state.video = sample.video
        // Startup detail belongs to the initial engine attempt only. Never combine a fallback
        // connection with the original session's first frame.
        if (state.initialStartupEligible) state.startup = sample.startup
        if (sample.paused == true) {
            state.closeRebuffer(now)
            state.lastFrameAtMs = null
            state.transition(PlaybackObservedState.PAUSED, now)
        } else if (sample.paused == false && state.observedState == PlaybackObservedState.PAUSED) {
            state.lastFrameAtMs = null
            state.transition(if (state.firstFrameAtMs == null) PlaybackObservedState.STARTING else PlaybackObservedState.PLAYING, now)
        }
    }

    fun pauseAll() = synchronized(lock) {
        active.values.forEach { state ->
            state.closeRebuffer(clock.nowMs())
            state.lastFrameAtMs = null
            state.transition(PlaybackObservedState.PAUSED, clock.nowMs())
        }
    }

    fun setPaused(sessionId: PlaybackSessionId, paused: Boolean) = mutate(sessionId) { state ->
        val now = clock.nowMs()
        if (paused) {
            state.closeRebuffer(now)
            state.lastFrameAtMs = null
            state.transition(PlaybackObservedState.PAUSED, now)
        } else if (state.observedState == PlaybackObservedState.PAUSED) {
            state.lastFrameAtMs = null
            state.transition(if (state.firstFrameAtMs == null) PlaybackObservedState.STARTING else PlaybackObservedState.PLAYING, now)
        }
    }

    fun activeSnapshot(): List<PlaybackQoeRecord> = synchronized(lock) {
        active.values.map { it.snapshot(clock.nowMs(), false) }
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
        state.transition(if (reason == PlaybackEndReason.FATAL_FAILURE) PlaybackObservedState.FAILED else PlaybackObservedState.ENDED, now)
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

    /** IDs of open sessions, oldest first, optionally only those of [kind]. */
    fun activeSessionIds(kind: PlaybackContentKind? = null): List<PlaybackSessionId> =
        synchronized(lock) {
            active.values.filter { kind == null || it.session.kind == kind }.map { it.session.id }
        }

    /**
     * Close every session open longer than [maxAgeMs] (monotonic) as [reason].
     * A missed teardown otherwise pins the session in memory forever and its
     * summary never reaches the spool. Returns the records closed.
     */
    fun finishStale(
        maxAgeMs: Long,
        endedAtEpochMs: Long,
        reason: PlaybackEndReason = PlaybackEndReason.ABANDONED,
    ): List<PlaybackQoeRecord> {
        require(maxAgeMs >= 0L) { "maxAgeMs must not be negative" }
        val stale = synchronized(lock) {
            val now = clock.nowMs()
            active.values.filter { now - it.startedAtMs >= maxAgeMs }.map { it.session.id }
        }
        return stale.mapNotNull { finish(it, reason, endedAtEpochMs) }
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
        var observedState: PlaybackObservedState = PlaybackObservedState.STARTING,
        var stateStartedAtMs: Long = startedAtMs,
        var pausedDurationMs: Long = 0,
        var framesKnown: Boolean = false,
        var droppedFramesKnown: Boolean = false,
        var lastFrameAtMs: Long? = null,
        var lastObservedAtMs: Long? = null,
        var currentBufferMs: Long? = null,
        var counterSource: String? = null,
        var previousRendered: Long? = null,
        var previousDropped: Long? = null,
        var video: PlaybackVideoFormat? = null,
        var startup: PlaybackStartupTiming? = null,
        var initialStartupEligible: Boolean = true,
    ) {
        fun clearOutputEvidence() {
            counterSource = null
            previousRendered = null
            previousDropped = null
            framesKnown = false
            droppedFramesKnown = false
            lastFrameAtMs = null
            lastObservedAtMs = null
            currentBufferMs = null
            video = null
            startup = null
        }
        /**
         * A quiesced or paused engine stops sampling (PlayerController suppresses observations while
         * suspended). What the picture did meanwhile is unknown, so after a silent gap the freeze clock
         * restarts at this observation instead of blaming the gap on the renderer.
         */
        fun noteObservation(now: Long) {
            val previous = lastObservedAtMs
            if (previous != null && now - previous > STALE_OBSERVATION_MS && lastFrameAtMs != null) lastFrameAtMs = now
            lastObservedAtMs = now
        }
        fun transition(next: PlaybackObservedState, now: Long) {
            if (observedState != next) {
                if (next == PlaybackObservedState.PAUSED && firstFrameAtMs == null) {
                    initialStartupEligible = false
                    startup = null
                }
                if (observedState == PlaybackObservedState.PAUSED) pausedDurationMs = saturatingAdd(pausedDurationMs, duration(stateStartedAtMs, now))
                observedState = next; stateStartedAtMs = now
            }
        }
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
            // Frame age is evidence only while the sampler still delivers; a silent sampler makes it unknown, not a freeze.
            val sampling = lastObservedAtMs?.let { duration(it, nowMs) <= STALE_OBSERVATION_MS } == true
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
                observedState = observedState,
                stateDurationMs = duration(stateStartedAtMs, nowMs),
                pausedDurationMs = saturatingAdd(pausedDurationMs, if (observedState == PlaybackObservedState.PAUSED) duration(stateStartedAtMs, nowMs) else 0),
                framesKnown = framesKnown,
                droppedFramesKnown = droppedFramesKnown,
                // Age as of the last observation: a quiesced engine (no observations) must not
                // let the clock run into a false freeze before the 6 s silence gate closes.
                lastFrameAgeMs = lastFrameAtMs?.takeIf { sampling }?.let { duration(it, lastObservedAtMs ?: nowMs) },
                currentBufferMs = currentBufferMs,
                video = video,
                startup = startup,
            )
        }
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val DEFAULT_MAX_ACTIVE_SESSIONS = 16
        private const val DEFAULT_MAX_COMPLETED_SESSIONS = 50
        private const val DEFAULT_MAX_FAILURES_PER_SESSION = 8

        /** Sessions still open after this long are closed as [PlaybackEndReason.ABANDONED]. */
        const val ABANDON_AFTER_MS = 6L * 60L * 60L * 1000L

        /**
         * Media3 samples every 2 s and the VLC health poll every 1.5 s. Without an observation for this long
         * the engine is stopped, paused or hung, and a last frame age nobody measures is reported as unknown.
         */
        const val STALE_OBSERVATION_MS = 6_000L

        /**
         * Summary for a session a previous process left open (no measurements
         * survived the death): only the start facts are known, so the duration
         * is reported as 0 rather than guessed from wall time.
         */
        fun abandoned(session: PlaybackSession, endedAtEpochMs: Long): PlaybackQoeRecord {
            require(endedAtEpochMs >= 0L) { "endedAtEpochMs must not be negative" }
            return PlaybackQoeRecord(
                session = session,
                finalEngine = session.initialEngine,
                endedAtEpochMs = endedAtEpochMs,
                endReason = PlaybackEndReason.ABANDONED,
                sessionDurationMs = 0L,
                timeToReadyMs = null,
                timeToFirstFrameMs = null,
                rebufferCount = 0,
                rebufferDurationMs = 0L,
                engineSwitchCount = 0,
                renderedFrames = 0L,
                droppedFrames = 0L,
                failures = emptyList(),
                discardedFailureCount = 0,
                isFinal = true,
                observedState = PlaybackObservedState.ENDED,
            )
        }

        private fun duration(startMs: Long, endMs: Long): Long = (endMs - startMs).coerceAtLeast(0L)

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
