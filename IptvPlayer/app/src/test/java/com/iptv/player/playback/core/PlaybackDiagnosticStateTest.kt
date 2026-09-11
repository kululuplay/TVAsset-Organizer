package com.iptv.player.playback.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticStateTest {
    @Test fun `stale engine and failure cannot overwrite current session`() {
        val state = PlaybackDiagnosticState()
        val old = PlaybackSessionId.random()
        val current = PlaybackSessionId.random()
        state.start(old, PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS, 1)
        state.start(current, PlaybackEngineKind.VLC, PlaybackTransportKind.HLS, 2)
        state.engine(old, PlaybackEngineKind.EXO_PLAYER)
        state.finish(old)
        state.failure(old, PlaybackFailureClassifier.classify(FailureSignal.Http(403)))
        assertEquals("VLC", state.snapshot()["engine"])
        assertEquals("HLS", state.snapshot()["transport"])
        assertEquals("true", state.snapshot()["sessionActive"])
        assertEquals("", state.snapshot()["recentFailures"])
        state.finish(current)
        assertEquals("last:VLC", state.snapshot()["engine"])
    }
    @Test fun `failure evidence bounded without free form data`() {
        val state = PlaybackDiagnosticState()
        val id = PlaybackSessionId.random()
        state.start(id, PlaybackEngineKind.VLC, PlaybackTransportKind.HLS, 0)
        repeat(20) { state.failure(id, PlaybackFailureClassifier.classify(FailureSignal.Http(403))) }
        assertEquals(6, state.snapshot().getValue("recentFailures").split(" > ").size)
        assertEquals("UNKNOWN", state.snapshot()["decoder"])
    }
}
