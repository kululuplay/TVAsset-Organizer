package com.iptv.player.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQoeRecorderTest {

    @Test
    fun `records startup fallback rebuffer and frame QoE with monotonic time`() {
        val clock = FakeClock()
        val recorder = PlaybackQoeRecorder(clock = clock)
        val id = PlaybackSessionId.random()
        assertTrue(recorder.start(session(id)))

        clock.advance(300)
        assertTrue(recorder.markReady(id))
        clock.advance(700)
        assertTrue(recorder.markFirstFrame(id))
        assertTrue(recorder.markEngine(id, PlaybackEngineKind.VLC))
        recorder.addFrameCounters(id, renderedDelta = 100, droppedDelta = 2)

        clock.advance(1_000)
        recorder.setRebuffering(id, true)
        clock.advance(450)
        recorder.setRebuffering(id, false)
        recorder.recordFailure(
            id,
            PlaybackFailureClassifier.classify(
                FailureSignal.Timeout(FailureSignal.TimeoutKind.STALL),
                PlaybackFailure.Phase.PLAYBACK,
            ),
        )
        clock.advance(50)

        val record = recorder.finish(
            id,
            reason = PlaybackEndReason.USER_STOP,
            endedAtEpochMs = 15_000,
        )!!

        assertEquals(2_500L, record.sessionDurationMs)
        assertEquals(300L, record.timeToReadyMs)
        assertEquals(1_000L, record.timeToFirstFrameMs)
        assertEquals(1, record.rebufferCount)
        assertEquals(450L, record.rebufferDurationMs)
        assertEquals(1, record.engineSwitchCount)
        assertEquals(100L, record.renderedFrames)
        assertEquals(2L, record.droppedFrames)
        assertEquals(PlaybackFailure.Code.PLAYBACK_STALL, record.failures.single().code)
        assertTrue(record.isFinal)
        assertNull(recorder.snapshotActive(id))
    }

    @Test
    fun `startup buffering is excluded and active rebuffer appears in snapshots`() {
        val clock = FakeClock()
        val recorder = PlaybackQoeRecorder(clock = clock)
        val id = PlaybackSessionId.random()
        recorder.start(session(id))

        recorder.setRebuffering(id, true)
        clock.advance(900)
        recorder.markFirstFrame(id)
        recorder.setRebuffering(id, true)
        clock.advance(125)

        val snapshot = recorder.snapshotActive(id)!!
        assertEquals(1, snapshot.rebufferCount)
        assertEquals(125L, snapshot.rebufferDurationMs)
        assertFalse(snapshot.isFinal)
    }

    @Test
    fun `safe payload structurally rejects URL session IDs and has no free text`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSessionId.from("https://user:pass@example.test/live/1")
        }

        val recorder = PlaybackQoeRecorder(clock = FakeClock())
        val id = PlaybackSessionId.random()
        recorder.start(session(id))
        val fields = recorder.finish(id, PlaybackEndReason.COMPLETED, 2_000)!!.toSafeFields()
        val serializedForAssertion = fields.entries.joinToString("|") { "${it.key}=${it.value}" }

        assertFalse(serializedForAssertion.contains("http://", ignoreCase = true))
        assertFalse(serializedForAssertion.contains("https://", ignoreCase = true))
        assertFalse(serializedForAssertion.contains("password", ignoreCase = true))
        assertFalse(serializedForAssertion.contains("url", ignoreCase = true))
        assertEquals(id.value, fields["session_id"])
    }

    @Test
    fun `safe payload exposes only the closed telemetry schema`() {
        val recorder = PlaybackQoeRecorder(clock = FakeClock())
        val id = PlaybackSessionId.random()
        recorder.start(
            session(id).copy(
                capabilityFingerprint = CapabilityFingerprint("cap-v1-${"0".repeat(64)}"),
            ),
        )
        recorder.recordFailure(id, PlaybackFailureClassifier.classify(FailureSignal.Http(503)))
        val fields = recorder.finish(id, PlaybackEndReason.FATAL_FAILURE, 2_000)!!.toSafeFields()

        assertEquals(
            setOf(
                "schema",
                "session_id",
                "content_kind",
                "started_at_epoch_ms",
                "initial_engine",
                "final_engine",
                "transport",
                "capability_fingerprint",
                "ended_at_epoch_ms",
                "end_reason",
                "session_duration_ms",
                "rebuffer_count",
                "rebuffer_duration_ms",
                "engine_switch_count",
                "rendered_frames",
                "dropped_frames",
                "failure_codes",
                "failure_categories",
                "failure_phases",
                "failure_components",
                "failure_retry_advice",
                "failure_http_statuses",
                "discarded_failure_count",
                "final",
            ),
            fields.keys,
        )
        assertEquals("503", fields["failure_http_statuses"])
        assertTrue(fields.values.all { it is String || it is Number || it is Boolean })
    }

    @Test
    fun `completed sessions and per-session failures are bounded`() {
        val clock = FakeClock()
        val recorder = PlaybackQoeRecorder(
            clock = clock,
            maxCompletedSessions = 2,
            maxFailuresPerSession = 2,
        )
        val repeatedFailure = PlaybackFailureClassifier.classify(FailureSignal.Http(503))
        val ids = List(3) { PlaybackSessionId.random() }

        repeat(3) { index ->
            val id = ids[index]
            recorder.start(session(id))
            repeat(4) { recorder.recordFailure(id, repeatedFailure) }
            recorder.finish(id, PlaybackEndReason.FATAL_FAILURE, endedAtEpochMs = index.toLong())
        }

        val completed = recorder.completedSnapshot()
        assertEquals(listOf(ids[1], ids[2]), completed.map { it.session.id })
        assertEquals(2, completed.last().failures.size)
        assertEquals(2, completed.last().discardedFailureCount)
    }

    @Test
    fun `audio failure telemetry contains only closed enum evidence`() {
        val recorder = PlaybackQoeRecorder(clock = FakeClock())
        val id = PlaybackSessionId.random()
        recorder.start(session(id))
        recorder.recordFailure(
            id,
            PlaybackFailureClassifier.classify(
                FailureSignal.AudioStall(
                    AudioFailureEvidence(
                        codec = AudioFailureEvidence.Codec.AC3,
                        decoder = AudioFailureEvidence.Decoder.HARDWARE,
                        sinkEvent = AudioFailureEvidence.SinkEvent.SINK_ERROR,
                        outputMode = AudioFailureEvidence.OutputMode.PCM,
                    ),
                ),
                PlaybackFailure.Phase.PLAYBACK,
            ),
        )

        val fields = recorder.finish(id, PlaybackEndReason.FATAL_FAILURE, 2_000)!!.toSafeFields()
        assertEquals("AC3", fields["audio_failure_codecs"])
        assertEquals("HARDWARE", fields["audio_failure_decoders"])
        assertEquals("SINK_ERROR", fields["audio_failure_sink_events"])
        assertEquals("PCM", fields["audio_failure_output_modes"])
        assertFalse(fields.values.joinToString().contains("http", ignoreCase = true))
    }

    @Test
    fun `late callbacks from a finished session are ignored`() {
        val recorder = PlaybackQoeRecorder(clock = FakeClock())
        val id = PlaybackSessionId.random()
        recorder.start(session(id))
        recorder.finish(id, PlaybackEndReason.REPLACED, 1_000)

        assertFalse(recorder.markFirstFrame(id))
        assertFalse(recorder.recordFailure(id, PlaybackFailureClassifier.classify(FailureSignal.Http(500))))
        assertNull(recorder.finish(id, PlaybackEndReason.USER_STOP, 2_000))
    }

    @Test
    fun `discovering an initially unknown engine is not counted as a switch`() {
        val recorder = PlaybackQoeRecorder(clock = FakeClock())
        val id = PlaybackSessionId.random()
        recorder.start(
            session(id).copy(initialEngine = PlaybackEngineKind.UNKNOWN),
        )

        assertTrue(recorder.markEngine(id, PlaybackEngineKind.EXO_PLAYER))
        assertEquals(0, recorder.snapshotActive(id)!!.engineSwitchCount)

        assertTrue(recorder.markEngine(id, PlaybackEngineKind.VLC))
        assertEquals(1, recorder.snapshotActive(id)!!.engineSwitchCount)
    }

    @Test
    fun `resolved transport replaces configured transport without free text`() {
        val recorder = PlaybackQoeRecorder(clock = FakeClock())
        val id = PlaybackSessionId.random()
        recorder.start(session(id).copy(transport = PlaybackTransportKind.MPEG_TS))

        assertTrue(recorder.markTransport(id, PlaybackTransportKind.HLS))
        assertEquals(
            PlaybackTransportKind.HLS,
            recorder.snapshotActive(id)!!.session.transport,
        )

        recorder.finish(id, PlaybackEndReason.USER_STOP, 2_000)
        assertFalse(recorder.markTransport(id, PlaybackTransportKind.MPEG_TS))
    }

    @Test
    fun `active session storage is bounded when a caller misses teardown`() {
        val recorder = PlaybackQoeRecorder(
            clock = FakeClock(),
            maxActiveSessions = 2,
        )
        val first = PlaybackSessionId.random()
        val second = PlaybackSessionId.random()
        val rejected = PlaybackSessionId.random()

        assertTrue(recorder.start(session(first)))
        assertTrue(recorder.start(session(second)))
        assertFalse(recorder.start(session(rejected)))
        assertNull(recorder.snapshotActive(rejected))

        assertTrue(recorder.finish(first, PlaybackEndReason.USER_STOP, 2_000) != null)
        assertTrue(recorder.start(session(rejected)))
    }

    private fun session(id: PlaybackSessionId) = PlaybackSession(
        id = id,
        kind = PlaybackContentKind.LIVE_TV,
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
