package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

class QoeSpoolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }

    @Test
    fun `append keeps insertion order and snapshot does not remove`() {
        val spool = QoeSpool(file = null)
        val a = summary()
        val b = summary()
        assertTrue(spool.append(a))
        assertTrue(spool.append(b))

        val first = spool.snapshot(1)
        assertEquals(listOf(a), first.map { it.json })
        assertEquals(2, spool.size())
        assertEquals(listOf(a, b), spool.snapshot(20).map { it.json })
        assertTrue(spool.snapshot(0).isEmpty())
    }

    @Test
    fun `confirmUploaded removes only the acknowledged ids`() {
        val spool = QoeSpool(file = null)
        val a = summary()
        val b = summary()
        val c = summary()
        listOf(a, b, c).forEach { assertTrue(spool.append(it)) }
        val batch = spool.snapshot(2)

        spool.confirmUploaded(batch.map { it.id })

        assertEquals(listOf(c), spool.snapshot(20).map { it.json })
        spool.confirmUploaded(listOf("00000000-0000-4000-8000-000000000000"))
        assertEquals(1, spool.size())
        assertEquals(c.length + 1, spool.byteSize())
    }

    @Test
    fun `duplicate session ids are refused`() {
        val spool = QoeSpool(file = null)
        val a = summary()
        assertTrue(spool.append(a))
        assertFalse(spool.append(a))
        assertFalse(spool.append(a.replace("\"schema\":1", "\"schema\":2")))
        assertEquals(1, spool.size())
    }

    @Test
    fun `malformed or oversized summaries are refused`() {
        val spool = QoeSpool(file = null)
        assertFalse(spool.append(""))
        assertFalse(spool.append("{\"schema\":1}"))
        assertFalse(spool.append("{\"session_id\":\"not-a-uuid\"}"))
        assertFalse(spool.append(summary() + "\n" + summary()))
        val huge = summary(padding = QoeSpool.MAX_ENTRY_BYTES)
        assertTrue(huge.length > QoeSpool.MAX_ENTRY_BYTES)
        assertFalse(spool.append(huge))
        assertEquals(0, spool.size())
        assertEquals(0, spool.droppedCount())
    }

    @Test
    fun `entry cap drops the oldest and counts it`() {
        val spool = QoeSpool(file = null, maxEntries = 3)
        val lines = List(5) { summary() }
        lines.forEach { assertTrue(spool.append(it)) }

        assertEquals(3, spool.size())
        assertEquals(lines.drop(2), spool.snapshot(20).map { it.json })
        assertEquals(2, spool.droppedCount())
        spool.confirmDropped(2)
        assertEquals(0, spool.droppedCount())
        spool.confirmDropped(5)
        assertEquals(0, spool.droppedCount())
    }

    @Test
    fun `byte cap drops the oldest until the spool fits`() {
        val one = summary(padding = 900)
        val spool = QoeSpool(file = null, maxEntries = 1_000, maxBytes = (one.length + 1) * 2 + 10)
        val lines = List(4) { summary(padding = 900) }
        lines.forEach { assertTrue(spool.append(it)) }

        assertEquals(2, spool.size())
        assertEquals(lines.drop(2), spool.snapshot(20).map { it.json })
        assertTrue(spool.byteSize() <= (one.length + 1) * 2 + 10)
        assertEquals(2, spool.droppedCount())
    }

    @Test
    fun `defaults are 200 entries and 256 KB`() {
        assertEquals(200, QoeSpool.MAX_ENTRIES)
        assertEquals(256 * 1024, QoeSpool.MAX_BYTES)
        assertEquals(4 * 1024, QoeSpool.MAX_ENTRY_BYTES)
    }

    @Test
    fun `serialize and load round trip as json lines`() {
        val spool = QoeSpool(file = null)
        val lines = List(3) { summary() }
        lines.forEach { spool.append(it) }
        val text = spool.serialize()
        assertEquals(lines.joinToString("\n", postfix = "\n"), text)

        val other = QoeSpool(file = null)
        other.load("\n" + text + "garbage line\n{\"schema\":1}\n" + lines[0] + "\n")
        assertEquals(lines, other.snapshot(20).map { it.json })
        assertEquals(0, other.droppedCount())
        assertEquals(text.length, other.byteSize())
    }

    @Test
    fun `load applies the bounds without counting drops`() {
        val lines = List(5) { summary() }
        val spool = QoeSpool(file = null, maxEntries = 2)
        spool.load(lines.joinToString("\n"))
        assertEquals(lines.drop(3), spool.snapshot(20).map { it.json })
        assertEquals(0, spool.droppedCount())
    }

    @Test
    fun `persists to disk and reloads after a restart`() {
        val file = File(tmp.root, QoeSpool.FILE_NAME)
        val spool = QoeSpool(file = file, io = direct)
        val a = summary()
        val b = summary()
        spool.append(a)
        spool.append(b)
        assertEquals(a + "\n" + b + "\n", file.readText())

        val restarted = QoeSpool(file = file, io = direct)
        restarted.init()
        assertEquals(listOf(a, b), restarted.snapshot(20).map { it.json })

        restarted.confirmUploaded(restarted.snapshot(20).map { it.id })
        assertFalse(file.exists())
    }

    @Test
    fun `init without a file is a no-op`() {
        val spool = QoeSpool(file = null, io = direct)
        spool.init()
        assertEquals(0, spool.size())
        assertEquals("", spool.serialize())
    }

    @Test
    fun `extractId lifts the session id and rejects non uuids`() {
        val id = UUID.randomUUID().toString()
        assertEquals(id, QoeSpool.extractId("{\"schema\":1, \"session_id\" : \"$id\"}"))
        assertNull(QoeSpool.extractId("{\"session_id\":\"abc\"}"))
        assertNull(QoeSpool.extractId("{\"event_id\":\"$id\"}"))
    }

    private fun summary(padding: Int = 0): String {
        val id = UUID.randomUUID().toString()
        val pad = if (padding > 0) ",\"failure_codes\":\"" + "X".repeat(padding) + "\"" else ""
        return "{\"schema\":1,\"session_id\":\"$id\",\"content_kind\":\"LIVE_TV\"," +
            "\"end_reason\":\"USER_STOP\",\"session_duration_ms\":1200$pad}"
    }
}
