package com.iptv.player.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackRouteMemoryTest {

    @Test
    fun `old software routes are re-evaluated but working hardware routes are retained`() {
        assertFalse(PlaybackRouteMemory.acceptsStoredRoute(4, "VLC_SW"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(4, "EXO"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(4, "VLC_HW"))
        assertFalse(PlaybackRouteMemory.acceptsStoredRoute(5, "VLC_SW"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(5, "EXO"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(5, "VLC_HW"))
        assertFalse(PlaybackRouteMemory.acceptsStoredRoute(6, "VLC_SW"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(6, "EXO"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(6, "VLC_HW"))
    }

    @Test
    fun `new proven software fallback can still be remembered`() {
        for (stage in listOf("EXO", "VLC_HW", "VLC_SW")) {
            assertTrue(PlaybackRouteMemory.acceptsStoredRoute(7, stage))
            assertFalse(PlaybackRouteMemory.acceptsStoredRoute(3, stage))
            assertFalse(PlaybackRouteMemory.acceptsStoredRoute(8, stage))
        }
        assertFalse(PlaybackRouteMemory.acceptsStoredRoute(7, "unknown"))
    }

    @Test
    fun `record-only lines load on the current schema only`() {
        assertTrue(PlaybackRouteMemory.acceptsStoredEntry(7, ""))
        assertFalse(PlaybackRouteMemory.acceptsStoredEntry(6, ""))
        assertTrue(PlaybackRouteMemory.acceptsStoredEntry(6, "EXO"))
        assertFalse(PlaybackRouteMemory.acceptsStoredEntry(6, "VLC_SW"))
    }

    @After
    fun tearDown() {
        PlaybackRouteMemory.clear()
    }

    @Test
    fun `quality failure immediately forgets matching learned route`() {
        PlaybackRouteMemory.markStable("channel-key", "EXO")

        PlaybackRouteMemory.forget("channel-key", "EXO")

        assertNull(PlaybackRouteMemory.bestStage("channel-key"))
    }

    @Test
    fun `failure from another stage does not erase learned route`() {
        PlaybackRouteMemory.markStable("channel-key", "VLC_HW")

        PlaybackRouteMemory.forget("channel-key", "EXO")

        assertEquals("VLC_HW", PlaybackRouteMemory.bestStage("channel-key"))
    }

    @Test
    fun `rebuffers and breaches accumulate into a bounded record without a stable stage`() {
        val now = 1_000_000L
        repeat(10) { PlaybackRouteMemory.recordRebuffer("weak", now) }
        repeat(20) { PlaybackRouteMemory.recordDroppedFrameBreach("weak", now) }

        val record = PlaybackRouteMemory.record("weak", now)!!
        assertEquals(6, record.rebufferCount)
        assertEquals(9, record.droppedFrameBreaches)
        assertNull(record.lastStableStage)
        assertNull(PlaybackRouteMemory.bestStage("weak"))
    }

    @Test
    fun `stable stage and quality record live side by side`() {
        val now = 1_000_000L
        PlaybackRouteMemory.recordRebuffer("ch", now)
        PlaybackRouteMemory.recordRebuffer("ch", now)
        PlaybackRouteMemory.markStable("ch", "VLC_SW")

        val record = PlaybackRouteMemory.record("ch", now)!!
        assertEquals("VLC_SW", record.lastStableStage)
        assertEquals(2, record.rebufferCount)
        assertEquals("VLC_SW", PlaybackRouteMemory.bestStage("ch"))
    }

    @Test
    fun `forgetting a route keeps a fresh quality record`() {
        val now = System.currentTimeMillis()
        PlaybackRouteMemory.markStable("ch", "EXO")
        PlaybackRouteMemory.recordRebuffer("ch", now)
        PlaybackRouteMemory.recordRebuffer("ch", now)

        PlaybackRouteMemory.forget("ch", "EXO")

        assertNull(PlaybackRouteMemory.bestStage("ch"))
        assertEquals(2, PlaybackRouteMemory.record("ch", now)?.rebufferCount)
    }

    @Test
    fun `seed applies at two recent rebuffers and decays per session`() {
        assertEquals(0, PlaybackRouteMemory.seedRebuffers(null))
        assertEquals(0, PlaybackRouteMemory.seedRebuffers(record(rebuffers = 1)))
        assertEquals(2, PlaybackRouteMemory.seedRebuffers(record(rebuffers = 2)))
        assertEquals(6, PlaybackRouteMemory.seedRebuffers(record(rebuffers = 9)))

        assertEquals(3, PlaybackRouteMemory.decayedForNewSession(6))
        assertEquals(1, PlaybackRouteMemory.decayedForNewSession(2))
        assertEquals(0, PlaybackRouteMemory.decayedForNewSession(1))
        assertEquals(0, PlaybackRouteMemory.decayedForNewSession(0))

        val now = 1_000_000L
        repeat(4) { PlaybackRouteMemory.recordRebuffer("ch", now) }
        PlaybackRouteMemory.beginSession("ch", now)
        assertEquals(2, PlaybackRouteMemory.record("ch", now)?.rebufferCount)
        PlaybackRouteMemory.beginSession("ch", now)
        assertEquals(1, PlaybackRouteMemory.record("ch", now)?.rebufferCount)
        PlaybackRouteMemory.beginSession("ch", now)
        assertEquals(0, PlaybackRouteMemory.seedRebuffers(PlaybackRouteMemory.record("ch", now)))
    }

    @Test
    fun `records older than seven days are ignored and restarted`() {
        val then = 1_000_000L
        val week = 7L * 24 * 60 * 60 * 1000
        PlaybackRouteMemory.recordRebuffer("ch", then)
        PlaybackRouteMemory.recordRebuffer("ch", then)

        assertTrue(PlaybackRouteMemory.recordIsFresh(then, then + week))
        assertFalse(PlaybackRouteMemory.recordIsFresh(then, then + week + 1))
        assertFalse(PlaybackRouteMemory.recordIsFresh(0L, then))
        assertNull(PlaybackRouteMemory.record("ch", then + week + 1))
        assertEquals(2, PlaybackRouteMemory.record("ch", then + week)?.rebufferCount)

        PlaybackRouteMemory.beginSession("ch", then + week + 1)
        PlaybackRouteMemory.recordRebuffer("ch", then + week + 1)
        assertEquals(1, PlaybackRouteMemory.record("ch", then + week + 1)?.rebufferCount)
    }

    @Test
    fun `null keys never create records`() {
        PlaybackRouteMemory.recordRebuffer(null)
        PlaybackRouteMemory.recordDroppedFrameBreach(null)
        PlaybackRouteMemory.beginSession(null)
        assertNull(PlaybackRouteMemory.record(null))
    }

    private fun record(rebuffers: Int) = PlaybackRouteMemory.ChannelRecord(
        rebufferCount = rebuffers,
        droppedFrameBreaches = 0,
        lastStableStage = null,
        updatedAtMs = 1L,
    )
}
