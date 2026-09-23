package com.iptv.player.playback.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackIncidentRecorderTest {
    private class Harness(val kind: PlaybackContentKind = PlaybackContentKind.LIVE_TV) {
        var now = 0L
        val epoch = 1_000_000L
        val id = PlaybackSessionId.random()
        val recorder = PlaybackQoeRecorder(PlaybackMonotonicClock { now })
        val incidents = PlaybackIncidentRecorder()
        val key = "a".repeat(64)
        init { recorder.start(PlaybackSession(id, kind, epoch, PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS)) }
        fun sample(at: Long) { now = at; incidents.observe(record(), key, now, epoch + now) }
        fun record() = recorder.snapshotActive(id)!!
    }

    @Test fun `startup threshold emits once and closes bounded before after window`() {
        val h = Harness()
        for (at in 0L..14_000L step 2_000) h.sample(at)
        assertTrue(h.incidents.drain().isEmpty())
        h.sample(16_000)
        val initial = h.incidents.drain().single()
        assertEquals(PlaybackIncidentTrigger.STARTUP_SLOW, initial.trigger)
        assertFalse(initial.complete)
        assertEquals(-16_000L, initial.points.first()["offsetMs"])
        for (at in 18_000L..26_000L step 2_000) h.sample(at)
        val completed = h.incidents.drain().single()
        assertEquals(initial.incidentId, completed.incidentId)
        assertTrue(completed.complete)
        assertFalse(completed.truncated)
        assertEquals(10_000L, completed.points.last()["offsetMs"])
        for (at in 28_000L..50_000L step 2_000) h.sample(at)
        assertTrue(h.incidents.drain().isEmpty())
    }

    @Test fun `intentional pause stops windows and is never a freeze`() {
        val h = Harness()
        h.recorder.markFirstFrame(h.id)
        h.recorder.observe(h.id, PlaybackObservation("decoder", rendered = 100, dropped = 0))
        h.sample(0)
        h.sample(8_000)
        assertEquals(PlaybackIncidentTrigger.VIDEO_STALL, h.incidents.drain().single().trigger)
        h.now = 9_000
        h.recorder.setPaused(h.id, true)
        h.sample(9_000)
        val completed = h.incidents.drain().single()
        assertTrue(completed.complete)
        assertTrue(completed.truncated)
        assertEquals("PAUSED", completed.points.last()["state"])
        h.sample(99_000)
        assertTrue(h.incidents.drain().isEmpty())
    }

    @Test fun `unknown frame measurements and audio only streams never create video stall incidents`() {
        val unknown = Harness()
        unknown.recorder.markFirstFrame(unknown.id)
        unknown.sample(60_000)
        assertTrue(unknown.incidents.drain().isEmpty())
        val radio = Harness(PlaybackContentKind.RADIO)
        radio.recorder.markFirstFrame(radio.id)
        radio.recorder.observe(radio.id, PlaybackObservation("audio", rendered = 1))
        radio.sample(60_000)
        assertTrue(radio.incidents.drain().isEmpty())
    }

    @Test fun `rebuffer threshold uses ongoing duration and explicit reports reuse a linked incident`() {
        val h = Harness()
        h.recorder.markFirstFrame(h.id)
        h.recorder.setRebuffering(h.id, true)
        h.sample(4_000)
        assertTrue(h.incidents.drain().isEmpty())
        h.sample(6_000)
        val automatic = h.incidents.drain().single()
        assertEquals(PlaybackIncidentTrigger.BUFFERING, automatic.trigger)
        val reportId = h.incidents.trigger(h.record(), h.key, PlaybackIncidentTrigger.USER_REPORT, 6_000, h.epoch + 6_000)
        val events = h.incidents.drain()
        assertEquals(2, events.size)
        assertEquals(automatic.incidentId, events.first().incidentId)
        assertTrue(events.first().complete)
        assertEquals(reportId, events.last().incidentId)
        assertEquals(h.id.value, events.last().sessionId)
        assertEquals(h.key, events.last().contentKey)
        assertEquals(reportId, h.incidents.trigger(h.record(), h.key, PlaybackIncidentTrigger.USER_REPORT, 7_000, h.epoch + 7_000))
        assertTrue(h.incidents.drain().isEmpty())
    }

    @Test fun `terminal stop flushes early and strict point ordering survives fast callbacks`() {
        val h = Harness()
        h.incidents.trigger(h.record(), h.key, PlaybackIncidentTrigger.USER_REPORT, 0, h.epoch)
        h.incidents.drain()
        for (at in 1L..8_000L step 53) h.sample(at)
        h.now = 8_001
        val ended = h.recorder.finish(h.id, PlaybackEndReason.USER_STOP, h.epoch + h.now)!!
        h.incidents.observe(ended, h.key, h.now, h.epoch + h.now)
        val event = h.incidents.drain().single()
        assertTrue(event.complete && event.truncated)
        assertEquals("ENDED", event.points.last()["state"])
        val offsets = event.points.map { it["offsetMs"] as Long }
        assertTrue(offsets.zipWithNext().all { (a,b) -> a < b })
        assertTrue(offsets.size <= 31)
    }

    @Test fun `many sessions and event bursts remain bounded`() {
        val incidents = PlaybackIncidentRecorder(maxSessions = 2)
        repeat(100) {
            val h = Harness()
            incidents.trigger(h.record(), null, PlaybackIncidentTrigger.USER_REPORT, it * 2_000L, h.epoch + it * 2_000L)
        }
        assertEquals(2, incidents.trackedSessionCount())
        val events = incidents.drain()
        assertTrue(events.size <= 32)
        assertTrue(events.all { it.points.size <= 31 })
    }

    @Test fun `same millisecond terminal event has unique offsets and late sampler cannot reopen it`() {
        val h = Harness()
        h.sample(16_000)
        val late = h.record()
        h.incidents.drain()
        val ended = h.recorder.finish(h.id, PlaybackEndReason.FATAL_FAILURE, h.epoch + h.now)!!
        h.incidents.observe(ended, h.key, h.now, h.epoch + h.now)
        h.incidents.drain()
        h.incidents.observe(late, h.key, h.now + 30_000, h.epoch + h.now + 30_000)
        assertTrue(h.incidents.drain().isEmpty())
        h.incidents.trigger(ended, h.key, PlaybackIncidentTrigger.USER_REPORT, h.now + 1, h.epoch + h.now + 1)
        val event = h.incidents.drain().single()
        assertTrue(event.complete)
        val offsets = event.points.map { it["offsetMs"] }
        assertEquals(offsets.size, offsets.distinct().size)
    }
}
