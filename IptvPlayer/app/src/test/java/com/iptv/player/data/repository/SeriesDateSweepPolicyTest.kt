package com.iptv.player.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesDateSweepPolicyTest {
    @Test
    fun `sweep is paused only while the backoff instant is in the future`() {
        assertTrue(SeriesDateSweepPolicy.isPaused(now = 999L, backoffUntil = 1_000L))
        assertFalse(SeriesDateSweepPolicy.isPaused(now = 1_000L, backoffUntil = 1_000L))
        assertFalse(SeriesDateSweepPolicy.isPaused(now = 5L, backoffUntil = 0L))
    }

    @Test
    fun `category sweeps recheck every 15 minutes and catalog sweeps every 6 hours`() {
        val now = 100_000_000L
        assertEquals(now - 15L * 60_000L, SeriesDateSweepPolicy.dueBefore(now, "7", force = false))
        assertEquals(now - 6L * 60L * 60_000L, SeriesDateSweepPolicy.dueBefore(now, null, force = false))
    }

    @Test
    fun `forced sweep makes every series due regardless of scope`() {
        val now = 100_000_000L
        assertEquals(now + 1, SeriesDateSweepPolicy.dueBefore(now, "7", force = true))
        assertEquals(now + 1, SeriesDateSweepPolicy.dueBefore(now, null, force = true))
    }

    @Test
    fun `429 backs off longer than a server error and only those statuses throttle`() {
        assertEquals(15L * 60_000L, SeriesDateSweepPolicy.backoffMs(429))
        assertEquals(5L * 60_000L, SeriesDateSweepPolicy.backoffMs(503))
        assertTrue(SeriesDateSweepPolicy.isThrottleStatus(429))
        assertTrue(SeriesDateSweepPolicy.isThrottleStatus(500))
        assertTrue(SeriesDateSweepPolicy.isThrottleStatus(599))
        assertFalse(SeriesDateSweepPolicy.isThrottleStatus(404))
        assertFalse(SeriesDateSweepPolicy.isThrottleStatus(600))
    }
}
