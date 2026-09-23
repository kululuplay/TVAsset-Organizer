package com.iptv.player.util

import org.junit.Assert.*
import org.junit.Test

class PlaybackUploadQueueTest {
    @Test fun `returning online prioritizes current state ahead of stale incident backlog`() {
        val queue = PlaybackUploadQueue()
        repeat(25) { queue.enqueue(entry("old-$it", at = 1_000 + it.toLong()).copy(urgent = true)) }
        queue.enqueue(entry("current", false, at = 600_000))
        assertEquals("current", queue.next(600_000)?.id)
        queue.acknowledge("current", "current")
        assertEquals("old-0", queue.next(600_000)?.id)
        queue.enqueue(entry("fresh-incident", at = 600_000).copy(urgent = true))
        assertEquals("fresh-incident", queue.next(600_000)?.id)
        queue.acknowledge("fresh-incident", "fresh-incident")
        assertEquals("old-0", queue.next(600_000)?.id)
        assertEquals(25, queue.entries().size)
    }
    @Test fun `incident batches bypass retained boundaries without bypassing failure backoff or exact ack`() {
        val queue = PlaybackUploadQueue()
        queue.enqueue(entry("old-start"))
        queue.enqueue(entry("current", false))
        val incident = entry("incident").copy(urgent = true)
        queue.enqueue(incident)
        assertEquals(incident, queue.next(1_000))
        queue.failed(1_000)
        assertNull(queue.next(2_000))
        assertFalse(queue.acknowledge("incident", "wrong"))
        assertEquals(incident, queue.next(31_000))
        queue.acknowledge("incident", "incident")
        assertEquals("current", queue.next(31_000)?.id)
    }

    @Test fun `quick reports only retain closed diagnostic linkage metadata and never a raw log`() {
        val session = "7b426a10-1b00-448d-9eaa-10983b6aee21"
        val incident = "395ec35b-a54f-4f4e-aee6-aa20dba4f2f9"
        val metadata = mapOf("playback_session_id" to session, "playback_incident_id" to incident,
            "content_key" to "f".repeat(64), "stream_url" to "https://user:password@host/secret")
        val report = SupportPayloadPolicy.prepare("diagnostic", "Channel is stuttering", null, metadata)!!
        assertEquals(setOf("playback_session_id", "playback_incident_id", "content_key"), report.metadata.keys)
        assertNull(report.log)
        assertEquals(session, report.metadata["playback_session_id"])
        assertEquals(incident, report.metadata["playback_incident_id"])
        assertEquals(report.fingerprint(), SupportPayloadPolicy.prepare("diagnostic", report.message, null, metadata)!!.fingerprint())
        assertTrue(SupportPayloadPolicy.prepare("movie", "Test", null, metadata)!!.metadata.isEmpty())
        assertTrue(SupportPayloadPolicy.prepare("diagnostic", "Test", null, mapOf(
            "playback_session_id" to "customer-account", "content_key" to "https://user:pass@host"))!!.metadata.isEmpty())
    }
    @Test fun `content labels remove stream URLs emails and account secrets before disk storage`() {
        val label = SupportPayloadPolicy.playbackLabel(
            "TR channel rtsp://account:password@host/live account-name customer@example.com\nHD",
            listOf("account-name"),
        )
        assertFalse(label.contains("rtsp://"))
        assertFalse(label.contains("account-name"))
        assertFalse(label.contains("customer@example.com"))
        assertTrue(label.contains("TR channel"))
        assertEquals(160, SupportPayloadPolicy.playbackLabel("a".repeat(200), emptyList()).length)
    }
    private fun entry(id: String, boundary: Boolean = true, at: Long = 1_000) =
        PlaybackUploadQueue.Entry(id, "{immutable:$id}", at, boundary)

    @Test fun `only exact ack removes a sample and restarts preserve immutable payload`() {
        val queue = PlaybackUploadQueue()
        queue.enqueue(entry("a"))
        queue.failed(1_000)
        assertFalse(queue.acknowledge("a", "different"))
        val restored = PlaybackUploadQueue()
        restored.restore(queue.entries(), queue.failureCount, queue.nextAttemptMs, 2_000)
        assertNull(restored.next(2_000))
        assertEquals(entry("a"), restored.next(31_000))
        assertTrue(restored.acknowledge("a", "a"))
        assertTrue(restored.entries().isEmpty())
    }

    @Test fun `offline periodic samples coalesce while start and end remain`() {
        val queue = PlaybackUploadQueue()
        queue.enqueue(entry("start"))
        queue.enqueue(entry("old", false))
        queue.enqueue(entry("new", false))
        queue.enqueue(entry("end"))
        assertEquals(listOf("start", "new", "end"), queue.entries().map { it.id })
        assertEquals("new", queue.next(1_000)?.id)
        queue.acknowledge("new", "new")
        assertEquals("start", queue.next(1_000)?.id)
    }

    @Test fun `entry and UTF8 byte bounds prevent unbounded offline storage`() {
        val queue = PlaybackUploadQueue(capacity = 3, maxBytes = 100)
        repeat(10) { queue.enqueue(entry(it.toString())) }
        assertEquals(listOf("7", "8", "9"), queue.entries().map { it.id })
        queue.enqueue(PlaybackUploadQueue.Entry("large", "é".repeat(30_000), 1_000, true))
        assertEquals(3, queue.entries().size)
        queue.enqueue(PlaybackUploadQueue.Entry("fits", "é".repeat(45), 1_000, true))
        assertEquals(listOf("fits"), queue.entries().map { it.id })
    }

    @Test fun `backoff caps at fifteen minutes and survives clock adjustment`() {
        val queue = PlaybackUploadQueue()
        queue.enqueue(entry("a"))
        repeat(20) { queue.failed(1_000) }
        assertEquals(901_000L, queue.nextAttemptMs)
        val restored = PlaybackUploadQueue()
        restored.restore(queue.entries(), queue.failureCount, Long.MAX_VALUE, 1_000)
        assertEquals(901_000L, restored.nextAttemptMs)
    }

    @Test fun `expired future and permanently rejected payloads cannot block current evidence`() {
        val now = 8 * 24 * 60 * 60_000L
        val queue = PlaybackUploadQueue()
        queue.enqueue(entry("expired"))
        queue.enqueue(entry("future", at = now + 400_000))
        queue.enqueue(entry("current", at = now))
        assertEquals("current", queue.next(now)?.id)
        assertEquals(1, queue.entries().size)
        queue.reject("current")
        assertTrue(queue.entries().isEmpty())
    }
}
