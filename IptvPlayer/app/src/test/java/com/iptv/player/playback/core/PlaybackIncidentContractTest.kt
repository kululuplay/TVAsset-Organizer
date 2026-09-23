package com.iptv.player.playback.core

import com.google.gson.Gson
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Exports actual client windows, not hand-authored JSON, for the Node service contract check. */
class PlaybackIncidentContractTest {
    @Test fun `export initial and completed client incident envelopes`() {
        var elapsed = 0L
        val epoch = System.currentTimeMillis()
        val recorder = PlaybackQoeRecorder(PlaybackMonotonicClock { elapsed })
        val incidents = PlaybackIncidentRecorder()
        val id = PlaybackSessionId.random()
        val key = PlaybackContentIdentity.forStream("https://example.test/live/account/password/42.ts", PlaybackContentKind.LIVE_TV)!!
        recorder.start(PlaybackSession(id, PlaybackContentKind.LIVE_TV, epoch,
            PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS))
        val samples = mutableListOf<Map<String, Any>>()
        for (time in 0L..26_000L step 2_000L) {
            elapsed = time
            val record = recorder.snapshotActive(id)!!
            incidents.observe(record, key, elapsed, epoch + elapsed)
            incidents.drain().forEach { event ->
                samples += mapOf("name" to if (event.complete) "completed" else "initial", "payload" to mapOf(
                    "schema" to 1, "sampleId" to UUID.randomUUID().toString(), "sampledAtMs" to epoch + elapsed,
                    "device" to mapOf("model" to "Incident contract test", "apiLevel" to 28, "networkType" to "WIFI"),
                    "sessions" to listOf(record.toSafeFields() + ("content_key" to key)),
                    "incidents" to listOf(event.toSafeFields()),
                ))
            }
        }
        assertEquals(2, samples.size)
        assertTrue(samples.all { Gson().toJson(it["payload"]).toByteArray().size < 32 * 1024 })
        File("build/reports/support-incident-contract.json").apply {
            parentFile.mkdirs(); writeText(Gson().toJson(samples), Charsets.UTF_8)
        }
    }
}
