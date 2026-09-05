package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioUnderrunMonitorTest {
    private val monitor = AudioUnderrunMonitor()

    @Test
    fun `218 ms double underrun followed by playout is recovered without switching`() {
        monitor.onPosition(1_000_000L, 1_000L)
        assertTrue(monitor.onUnderrun(1_000L))
        monitor.onPosition(1_000_000L, 1_218L)
        assertFalse(monitor.onUnderrun(1_218L))
        assertEquals(218L, monitor.poll(1_218L).stalledDurationMs)
        monitor.onPosition(1_300_000L, 1_600L)
        val observation = monitor.poll(2_000L)
        assertFalse(observation.pending)
        assertTrue(observation.recovered)
        assertEquals(0, observation.underruns)
        assertFalse(monitor.poll(3_000L).recovered)
    }

    @Test
    fun `fresh repeated fixed positions prove a real six second stall`() {
        monitor.onPosition(1_000_000L, 1_000L)
        monitor.onUnderrun(1_000L)
        monitor.onUnderrun(1_218L)
        for (nowMs in 2_000L..7_000L step 1_000L) monitor.onPosition(1_000_000L, nowMs)
        val observation = monitor.poll(7_000L)
        assertTrue(observation.pending)
        assertEquals(2, observation.underruns)
        assertEquals(6_000L, observation.stalledDurationMs)
    }

    @Test
    fun `missing or stale position samples are unknown rather than stalled`() {
        monitor.onUnderrun(1_000L)
        monitor.onUnderrun(1_218L)
        assertEquals(0L, monitor.poll(7_000L).stalledDurationMs)
        monitor.reset()
        monitor.onPosition(1_000_000L, 1_000L)
        monitor.onUnderrun(1_000L)
        monitor.onUnderrun(1_218L)
        assertEquals(0L, monitor.poll(7_000L).stalledDurationMs)
    }

    @Test
    fun `a baseline that was already stale when the underrun arrived is not evidence`() {
        monitor.onPosition(1_000_000L, 0L)
        monitor.onUnderrun(3_000L)
        monitor.onUnderrun(3_218L)
        monitor.onPosition(1_000_000L, 9_000L)
        assertEquals(0L, monitor.poll(9_000L).stalledDurationMs)
    }

    @Test
    fun `unknown episodes expire without scheduling an endless watchdog`() {
        monitor.onUnderrun(1_000L)
        assertFalse(monitor.poll(13_001L).pending)
        monitor.onPosition(2_000_000L, 14_000L)
        assertTrue(monitor.onUnderrun(14_000L))
        assertEquals(1, monitor.poll(14_000L).underruns)
    }

    @Test
    fun `pause flush and channel replacement reset all old evidence`() {
        monitor.onPosition(1_000_000L, 1_000L)
        monitor.onUnderrun(1_000L)
        monitor.onUnderrun(1_218L)
        monitor.reset()
        monitor.onPosition(1_000_000L, 7_000L)
        assertFalse(monitor.poll(7_000L).pending)
        assertTrue(monitor.onUnderrun(7_000L))
        assertEquals(1, monitor.poll(7_000L).underruns)
    }

    @Test
    fun `retrograde or unset timestamps invalidate the suspected stall`() {
        for (position in listOf(null, -1L, 100_000L)) {
            monitor.reset()
            monitor.onPosition(1_000_000L, 1_000L)
            monitor.onUnderrun(1_000L)
            monitor.onUnderrun(1_218L)
            monitor.onPosition(position, 7_000L)
            assertFalse(monitor.poll(7_000L).pending)
        }
    }

    @Test
    fun `slow cumulative audio progress is still recognized as recovery`() {
        monitor.onPosition(1_000_000L, 1_000L)
        monitor.onUnderrun(1_000L)
        monitor.onUnderrun(1_218L)
        monitor.onPosition(1_100_000L, 2_000L)
        monitor.onPosition(1_200_000L, 3_000L)
        monitor.onPosition(1_250_000L, 4_000L)
        assertTrue(monitor.poll(4_000L).recovered)
    }

    @Test
    fun `new starvation after recovery cannot reuse the old underrun count`() {
        monitor.onPosition(1_000_000L, 1_000L)
        monitor.onUnderrun(1_000L)
        monitor.onUnderrun(1_218L)
        monitor.onPosition(2_000_000L, 2_000L)
        assertTrue(monitor.onUnderrun(2_100L))
        val observation = monitor.poll(2_100L)
        assertEquals(1, observation.underruns)
        assertEquals(0L, observation.stalledDurationMs)
        assertFalse(observation.recovered)
    }
}
