package com.iptv.player.playback.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackStartupTimingTest {
    @Test fun `delayed first frame callback uses actual renderer timestamp not UI dispatch time`() {
        var now = 0L
        val clock = PlaybackStartupClock { now }
        val owner = clock.reset()
        now = 100
        val transfer = clock.initializing(owner, true)
        now = 300
        clock.opened(owner, transfer)
        now = 500
        clock.bytes(owner, transfer, 32)
        now = 5_000 // The main looper delivers the event much later than the actual render.
        clock.firstFrame(renderedAtMs = 1_200)
        assertEquals(PlaybackStartupTiming(200, 500, 700), clock.snapshot())
        clock.firstFrame(renderedAtMs = 4_900)
        assertEquals(700L, clock.snapshot()!!.firstByteToFirstFrameMs)
    }

    @Test fun `invalid render timestamps stay unmeasured instead of being clamped or consuming first frame`() {
        var now = 100L
        val clock = PlaybackStartupClock { now }
        val owner = clock.reset()
        val transfer = clock.initializing(owner, true)
        now = 200
        clock.opened(owner, transfer)
        now = 300
        clock.bytes(owner, transfer, 1)
        now = 800
        listOf(-1L, 99L, 801L, Long.MAX_VALUE).forEach { invalid ->
            clock.firstFrame(invalid)
            assertNull(clock.snapshot()!!.firstByteToFirstFrameMs)
        }
        clock.firstFrame(500)
        assertEquals(200L, clock.snapshot()!!.firstByteToFirstFrameMs)
        clock.stop()
        clock.firstFrame(600)
        assertNull(clock.snapshot())
    }

    @Test fun `pause or seek interruption before first frame invalidates partial startup`() {
        var now = 0L
        val clock = PlaybackStartupClock { now }
        val owner = clock.reset()
        val transfer = clock.initializing(owner, true)
        now = 100
        clock.opened(owner, transfer)
        now = 200
        clock.bytes(owner, transfer, 10)
        assertEquals(200L, clock.snapshot()!!.timeToFirstByteMs)
        clock.interruptBeforeFirstFrame() // Both pause and seek handlers invoke this boundary.
        now = 20_000
        clock.bytes(owner, transfer, 100)
        clock.firstFrame(19_000)
        assertNull(clock.snapshot())
    }

    @Test fun `pause or seek after first frame preserves completed original timing`() {
        var now = 0L
        val clock = PlaybackStartupClock { now }
        val owner = clock.reset()
        val transfer = clock.initializing(owner, true)
        now = 100
        clock.opened(owner, transfer)
        now = 200
        clock.bytes(owner, transfer, 10)
        now = 500
        clock.firstFrame(400)
        val original = clock.snapshot()
        assertEquals(200L, original!!.firstByteToFirstFrameMs)
        clock.interruptBeforeFirstFrame()
        now = 30_000
        clock.interruptBeforeFirstFrame()
        clock.initializing(owner, true)
        clock.firstFrame(29_000)
        assertEquals(original, clock.snapshot())
    }

    @Test fun `same engine retry cannot reuse initial attempt eligibility but new session can`() {
        var now = 0L
        val recorder = PlaybackQoeRecorder(PlaybackMonotonicClock { now })
        val id = PlaybackSessionId.random()
        recorder.start(PlaybackSession(id, PlaybackContentKind.LIVE_TV, 0,
            PlaybackEngineKind.UNKNOWN, PlaybackTransportKind.MPEG_TS))
        recorder.markEngine(id, PlaybackEngineKind.EXO_PLAYER)
        now = 800
        recorder.markFirstFrame(id)
        recorder.observe(id, PlaybackObservation("first", startup = PlaybackStartupTiming(100, 200, 600)))
        assertEquals(100L, recorder.snapshotActive(id)!!.toSafeFields()["source_open_ms"])
        recorder.resetOutputEvidence(id)
        recorder.markEngine(id, PlaybackEngineKind.EXO_PLAYER)
        now = 1_000
        recorder.observe(id, PlaybackObservation("retry", startup = PlaybackStartupTiming(10, 20, 30)))
        assertEquals(0, recorder.snapshotActive(id)!!.engineSwitchCount)
        assertNull(recorder.snapshotActive(id)!!.startup)
        val replacement = PlaybackSessionId.random()
        recorder.start(PlaybackSession(replacement, PlaybackContentKind.LIVE_TV, 1_000,
            PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS))
        now = 1_100
        recorder.observe(replacement, PlaybackObservation("new-session", startup = PlaybackStartupTiming(10, 20, 30)))
        assertEquals(10L, recorder.snapshotActive(replacement)!!.toSafeFields()["source_open_ms"])
    }

    @Test fun `all pause entry points invalidate interrupted startup until a new session`() {
        for (entry in listOf("explicit", "observation", "background")) {
            var now = 0L
            val recorder = PlaybackQoeRecorder(PlaybackMonotonicClock { now })
            val id = PlaybackSessionId.random()
            recorder.start(PlaybackSession(id, PlaybackContentKind.VOD_MOVIE, 0,
                PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.PROGRESSIVE))
            now = 200
            recorder.observe(id, PlaybackObservation("initial", startup = PlaybackStartupTiming(100, 200)))
            when (entry) {
                "explicit" -> recorder.setPaused(id, true)
                "observation" -> recorder.observe(id, PlaybackObservation("initial", paused = true, startup = PlaybackStartupTiming(100, 200)))
                else -> recorder.pauseAll()
            }
            assertNull("$entry pause retained misleading startup", recorder.snapshotActive(id)!!.startup)
            now = 20_000
            recorder.setPaused(id, false)
            recorder.markFirstFrame(id)
            recorder.observe(id, PlaybackObservation("initial", paused = false, startup = PlaybackStartupTiming(100, 200, 19_800)))
            assertNull("$entry resume restored interrupted startup", recorder.snapshotActive(id)!!.startup)
        }
    }

    @Test fun `recorder retains completed timing across normal pause and resume`() {
        var now = 0L
        val recorder = PlaybackQoeRecorder(PlaybackMonotonicClock { now })
        val id = PlaybackSessionId.random()
        recorder.start(PlaybackSession(id, PlaybackContentKind.VOD_MOVIE, 0,
            PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.PROGRESSIVE))
        now = 600
        recorder.markFirstFrame(id)
        val timing = PlaybackStartupTiming(100, 200, 400)
        recorder.observe(id, PlaybackObservation("initial", startup = timing))
        recorder.setPaused(id, true)
        now = 50_000
        recorder.observe(id, PlaybackObservation("initial", paused = true, startup = timing))
        assertEquals(timing, recorder.snapshotActive(id)!!.startup)
        recorder.setPaused(id, false)
        recorder.observe(id, PlaybackObservation("initial", paused = false, startup = timing))
        assertEquals(timing, recorder.snapshotActive(id)!!.startup)
    }

    @Test fun `pause counter includes completed and active pauses without double counting`() {
        var now = 0L
        val recorder = PlaybackQoeRecorder(clock = PlaybackMonotonicClock { now })
        val id = PlaybackSessionId.random()
        recorder.start(PlaybackSession(id, PlaybackContentKind.LIVE_TV, 0,
            PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS))
        recorder.markFirstFrame(id)
        now = 100
        recorder.setPaused(id, true)
        now = 400
        assertEquals(300L, recorder.snapshotActive(id)!!.pausedDurationMs)
        recorder.setPaused(id, true)
        now = 500
        recorder.setPaused(id, false)
        assertEquals(400L, recorder.snapshotActive(id)!!.pausedDurationMs)
        now = 1_000
        recorder.setPaused(id, true)
        now = 1_500
        assertEquals(900L, recorder.finish(id, PlaybackEndReason.BACKGROUND, 1_500)!!.pausedDurationMs)
    }
    @Test fun `splits connection and payload to frame using actual callbacks`() {
        var now = 0L
        val clock = PlaybackStartupClock { now }
        val owner = clock.reset()
        now = 100
        val transfer = clock.initializing(owner, true)
        now = 400
        clock.opened(owner, transfer)
        now = 700
        clock.bytes(owner, transfer, 1)
        now = 2_500
        clock.firstFrame()
        assertEquals(PlaybackStartupTiming(300, 700, 1_800), clock.snapshot())
        now = 3_000
        clock.bytes(owner, transfer, 20_000)
        clock.firstFrame()
        assertEquals(1_800L, clock.snapshot()!!.firstByteToFirstFrameMs)
    }
    @Test fun `unavailable events do not become zero evidence`() {
        val clock = PlaybackStartupClock { 10 }
        val owner = clock.reset()
        clock.initializing(owner, false)
        clock.firstFrame()
        assertNull(clock.snapshot())
        assertTrue(PlaybackStartupTiming(-1, Long.MAX_VALUE, null).toSafeFields().isEmpty())
    }
    @Test fun `retired loader cannot contaminate replacement playback`() {
        var now = 10L
        val clock = PlaybackStartupClock { now }
        val old = clock.reset()
        val oldTransfer = clock.initializing(old, true)
        now = 40
        val current = clock.reset()
        clock.opened(old, oldTransfer)
        clock.bytes(old, oldTransfer, 1)
        assertNull(clock.snapshot())
        val transfer = clock.initializing(current, true)
        now = 70
        clock.opened(current, transfer)
        assertEquals(30L, clock.snapshot()!!.sourceOpenMs)
        assertNull(clock.snapshot()!!.timeToFirstByteMs)
        clock.stop()
        clock.bytes(current, transfer, 1)
        assertNull(clock.snapshot())
    }
    @Test fun `retry and range reopen before first frame invalidate split attribution`() {
        var now = 10L
        val clock = PlaybackStartupClock { now }
        val owner = clock.reset()
        val transfer = clock.initializing(owner, true)
        now = 20
        clock.opened(owner, transfer)
        clock.bytes(owner, transfer, 1)
        assertNotNull(clock.snapshot())
        now = 30
        clock.initializing(owner, true)
        clock.firstFrame()
        assertNull(clock.snapshot())
    }
    @Test fun `later segment after startup does not invalidate completed timing`() {
        var now = 0L
        val clock = PlaybackStartupClock { now }
        val owner = clock.reset()
        val transfer = clock.initializing(owner, true)
        now = 10
        clock.opened(owner, transfer)
        clock.bytes(owner, transfer, 1)
        now = 50
        clock.firstFrame()
        val captured = clock.snapshot()
        clock.initializing(owner, true)
        assertEquals(captured, clock.snapshot())
    }
    @Test fun `engine fallback clears original timing and excludes second connection`() {
        val recorder = PlaybackQoeRecorder(clock = PlaybackMonotonicClock { 0 })
        val id = PlaybackSessionId.random()
        recorder.start(PlaybackSession(id, PlaybackContentKind.LIVE_TV, 0,
            PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS))
        recorder.observe(id, PlaybackObservation("first", startup = PlaybackStartupTiming(100, 200, 500)))
        assertEquals(100L, recorder.snapshotActive(id)!!.toSafeFields()["source_open_ms"])
        recorder.markEngine(id, PlaybackEngineKind.VLC)
        recorder.observe(id, PlaybackObservation("second", startup = PlaybackStartupTiming(1, 2, 3)))
        assertFalse(recorder.snapshotActive(id)!!.toSafeFields().containsKey("source_open_ms"))
    }
}
