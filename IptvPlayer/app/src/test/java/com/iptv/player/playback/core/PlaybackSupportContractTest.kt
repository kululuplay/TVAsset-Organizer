package com.iptv.player.playback.core

import com.google.gson.Gson
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises real Android serialization against the support server after the JVM test run. */
class PlaybackSupportContractTest {
    @Test fun `export measured playback envelopes for server contract verification`() {
        var elapsed = 0L
        val epoch = System.currentTimeMillis()
        val recorder = PlaybackQoeRecorder(clock = PlaybackMonotonicClock { elapsed })
        val id = PlaybackSessionId.random()
        recorder.start(PlaybackSession(id, PlaybackContentKind.LIVE_TV, epoch,
            PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS))
        val samples = mutableListOf<Map<String, Any>>()
        fun capture(name: String, expected: String, record: PlaybackQoeRecord = recorder.snapshotActive(id)!!) {
            samples.add(mapOf("name" to name, "expected" to expected, "payload" to mapOf(
                "schema" to 1, "sampleId" to UUID.randomUUID().toString(), "sampledAtMs" to epoch,
                "device" to mapOf("model" to "Contract test", "apiLevel" to 28,
                    "networkType" to "WIFI", "networkConnected" to true, "lowMemory" to false),
                "sessions" to listOf(PlaybackSupportFields.from(record)),
            )))
        }
        recorder.setRebuffering(id, true)
        elapsed = 20_000
        assertEquals(PlaybackObservedState.STARTING, recorder.snapshotActive(id)!!.observedState)
        capture("first frame never arrived", "problem")
        recorder.markFirstFrame(id)
        recorder.setRebuffering(id, false)
        capture("playing without available frame counters", "unknown")
        recorder.observe(id, PlaybackObservation("contract-decoder", rendered = 200, dropped = 0, bufferMs = 4_000,
            video = PlaybackVideoFormat(PlaybackVideoCodec.H264, PlaybackVideoDecoder.HARDWARE, 1920, 1080, 29.97f),
            startup = PlaybackStartupTiming(100, 200, 19_800)))
        capture("recent rendered frames", "healthy")
        elapsed += 9_000
        recorder.observe(id, PlaybackObservation("contract-decoder", rendered = 200, dropped = 0, bufferMs = 4_000))
        capture("renderer stopped advancing", "problem")
        recorder.setPaused(id, true)
        capture("intentional pause", "unknown")
        recorder.setPaused(id, false)
        recorder.setRebuffering(id, true)
        elapsed += 10_000
        capture("rebuffer while playing", "problem")
        recorder.recordFailure(id, PlaybackFailure(PlaybackFailure.Category.AUTHORIZATION,
            PlaybackFailure.Code.HTTP_FORBIDDEN, PlaybackFailure.Phase.OPEN_SOURCE,
            PlaybackFailure.Component.TRANSPORT, httpStatus = 403))
        capture("terminal source denial", "problem", recorder.finish(id, PlaybackEndReason.FATAL_FAILURE, epoch)!!)
        capture("orphaned session without surviving measurements", "unknown", PlaybackQoeRecorder.abandoned(
            PlaybackSession(PlaybackSessionId.random(), PlaybackContentKind.LIVE_TV, epoch,
                PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS), epoch))
        val destination = File("build/reports/support-playback-contract.json")
        destination.parentFile.mkdirs()
        destination.writeText(Gson().toJson(samples), Charsets.UTF_8)
    }
}
