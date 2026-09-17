package com.iptv.player.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session-lifetime safety nets used by PlaybackQoeRuntime (auto-end, stale sweep, orphans). */
class PlaybackQoeRecorderLifecycleTest {

    @Test
    fun `activeSessionIds lists open sessions oldest first and filters by kind`() {
        val recorder = PlaybackQoeRecorder()
        val live1 = PlaybackSessionId.random()
        val vod = PlaybackSessionId.random()
        val live2 = PlaybackSessionId.random()
        recorder.start(session(live1, PlaybackContentKind.LIVE_TV))
        recorder.start(session(vod, PlaybackContentKind.VOD_MOVIE))
        recorder.start(session(live2, PlaybackContentKind.LIVE_TV))

        assertEquals(listOf(live1, vod, live2), recorder.activeSessionIds())
        assertEquals(listOf(live1, live2), recorder.activeSessionIds(PlaybackContentKind.LIVE_TV))
        assertEquals(listOf(vod), recorder.activeSessionIds(PlaybackContentKind.VOD_MOVIE))
        assertTrue(recorder.activeSessionIds(PlaybackContentKind.RADIO).isEmpty())

        recorder.finish(live1, PlaybackEndReason.REPLACED, 5_000L)
        assertEquals(listOf(live2), recorder.activeSessionIds(PlaybackContentKind.LIVE_TV))
    }

    @Test
    fun `finishStale closes only sessions past the age limit as ABANDONED`() {
        val clock = FakeClock()
        val recorder = PlaybackQoeRecorder(clock = clock)
        val old = PlaybackSessionId.random()
        recorder.start(session(old, PlaybackContentKind.LIVE_TV))
        clock.advance(PlaybackQoeRecorder.ABANDON_AFTER_MS - 1)
        val young = PlaybackSessionId.random()
        recorder.start(session(young, PlaybackContentKind.VOD_MOVIE))

        assertTrue(recorder.finishStale(PlaybackQoeRecorder.ABANDON_AFTER_MS, 9_000L).isEmpty())
        clock.advance(1)
        val swept = recorder.finishStale(PlaybackQoeRecorder.ABANDON_AFTER_MS, 9_000L)

        assertEquals(1, swept.size)
        val record = swept.single()
        assertEquals(old, record.session.id)
        assertEquals(PlaybackEndReason.ABANDONED, record.endReason)
        assertEquals(9_000L, record.endedAtEpochMs)
        assertEquals(PlaybackQoeRecorder.ABANDON_AFTER_MS, record.sessionDurationMs)
        assertTrue(record.isFinal)
        assertEquals("ABANDONED", record.toSafeFields()["end_reason"])
        assertEquals(listOf(young), recorder.activeSessionIds())
        assertNull(recorder.finish(old, PlaybackEndReason.USER_STOP, 9_500L))
        assertEquals(1, recorder.completedSnapshot().size)
    }

    @Test
    fun `finishStale with a zero limit closes everything`() {
        val recorder = PlaybackQoeRecorder()
        val a = PlaybackSessionId.random()
        val b = PlaybackSessionId.random()
        recorder.start(session(a, PlaybackContentKind.LIVE_TV))
        recorder.start(session(b, PlaybackContentKind.CATCH_UP))

        val swept = recorder.finishStale(0L, 1L, PlaybackEndReason.APP_SHUTDOWN)

        assertEquals(setOf(a, b), swept.map { it.session.id }.toSet())
        assertTrue(swept.all { it.endReason == PlaybackEndReason.APP_SHUTDOWN })
        assertTrue(recorder.activeSessionIds().isEmpty())
    }

    @Test
    fun `abandon limit is six hours`() {
        assertEquals(6L * 60L * 60L * 1000L, PlaybackQoeRecorder.ABANDON_AFTER_MS)
    }

    @Test
    fun `abandoned orphan record carries start facts only`() {
        val id = PlaybackSessionId.random()
        val fingerprint = CapabilityFingerprint("cap-v1-" + "a".repeat(64))
        val session = session(id, PlaybackContentKind.VOD_EPISODE).copy(
            capabilityFingerprint = fingerprint,
        )

        val record = PlaybackQoeRecorder.abandoned(session, endedAtEpochMs = 7_000L)

        assertEquals(session, record.session)
        assertEquals(PlaybackEndReason.ABANDONED, record.endReason)
        assertEquals(PlaybackEngineKind.EXO_PLAYER, record.finalEngine)
        assertEquals(7_000L, record.endedAtEpochMs)
        assertEquals(0L, record.sessionDurationMs)
        assertNull(record.timeToReadyMs)
        assertNull(record.timeToFirstFrameMs)
        assertEquals(0, record.rebufferCount)
        assertTrue(record.failures.isEmpty())
        assertTrue(record.isFinal)
        val fields = record.toSafeFields()
        assertEquals(id.value, fields["session_id"])
        assertEquals("VOD_EPISODE", fields["content_kind"])
        assertEquals("ABANDONED", fields["end_reason"])
        assertEquals(fingerprint.value, fields["capability_fingerprint"])
        assertEquals("", fields["failure_codes"])
        assertFalse(fields.values.any { it is String && it.contains("http") })
    }

    private fun session(id: PlaybackSessionId, kind: PlaybackContentKind) = PlaybackSession(
        id = id,
        kind = kind,
        startedAtEpochMs = 1_000,
        initialEngine = PlaybackEngineKind.EXO_PLAYER,
        transport = PlaybackTransportKind.HLS,
    )

    private class FakeClock : PlaybackMonotonicClock {
        private var value = 0L
        override fun nowMs(): Long = value
        fun advance(deltaMs: Long) {
            value += deltaMs
        }
    }
}
