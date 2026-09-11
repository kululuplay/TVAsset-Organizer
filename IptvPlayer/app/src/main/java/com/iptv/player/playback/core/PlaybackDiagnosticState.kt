package com.iptv.player.playback.core

/** Bounded observed state, separate from the user's configured preference. */
class PlaybackDiagnosticState {
    private var session: PlaybackSessionId? = null
    private var engine = PlaybackEngineKind.UNKNOWN
    private var transport = PlaybackTransportKind.UNKNOWN
    private var active = false
    private var startedAt = 0L
    private val failures = ArrayDeque<String>()

    @Synchronized fun start(id: PlaybackSessionId, backend: PlaybackEngineKind, route: PlaybackTransportKind, time: Long) {
        session = id; engine = backend; transport = route; startedAt = time; active = true; failures.clear()
    }
    @Synchronized fun engine(id: PlaybackSessionId?, value: PlaybackEngineKind) {
        if (id != null && id == session && active) engine = value
    }
    @Synchronized fun transport(id: PlaybackSessionId?, value: PlaybackTransportKind) {
        if (id != null && id == session && active) transport = value
    }
    @Synchronized fun failure(id: PlaybackSessionId?, value: PlaybackFailure) {
        if (id == null || id != session || !active) return
        failures.addLast("${value.phase}:${value.code}:${value.httpStatus ?: 0}")
        while (failures.size > 6) failures.removeFirst()
    }
    @Synchronized fun finish(id: PlaybackSessionId?) { if (id != null && id == session) active = false }
    @Synchronized fun snapshot(): Map<String, String> = mapOf(
        "engine" to if (active) engine.name else "last:${engine.name}",
        "transport" to if (active) transport.name else "last:${transport.name}",
        // Neither configured AUTO nor selecting VLC proves the decoder actually used.
        "decoder" to "UNKNOWN",
        "session" to (session?.value ?: "none"),
        "sessionActive" to active.toString(),
        "sessionStartedEpochMs" to startedAt.toString(),
        "recentFailures" to failures.joinToString(" > "),
    )
}
