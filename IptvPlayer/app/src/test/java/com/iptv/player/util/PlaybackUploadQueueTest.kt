package com.iptv.player.util

import org.junit.Assert.*
import org.junit.Test

class PlaybackUploadQueueTest {
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
