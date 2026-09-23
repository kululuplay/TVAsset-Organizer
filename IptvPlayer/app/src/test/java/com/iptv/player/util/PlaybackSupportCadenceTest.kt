package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSupportCadenceTest {
    @Test fun `idle device refreshes its card once per alive interval instead of every cycle`() {
        val cadence = PlaybackSupportCadence(aliveIntervalMs = 300_000)
        assertTrue(cadence.shouldCapture(active = false, now = 0))
        var captures = 0
        for (now in 30_000L..299_000L step 30_000) if (cadence.shouldCapture(active = false, now = now)) captures++
        assertEquals(0, captures)
        assertTrue(cadence.shouldCapture(active = false, now = 300_000))
        assertFalse(cadence.shouldCapture(active = false, now = 330_000))
    }

    @Test fun `active playback keeps every cycle and a new foreground start reports immediately`() {
        val cadence = PlaybackSupportCadence(aliveIntervalMs = 300_000)
        assertTrue(cadence.shouldCapture(active = false, now = 0))
        for (now in 30_000L..120_000L step 30_000) assertTrue(cadence.shouldCapture(active = true, now = now))
        // Playback ended: its final boundary already travelled, so the idle interval applies again.
        assertFalse(cadence.shouldCapture(active = false, now = 150_000))
        cadence.reset()
        assertTrue(cadence.shouldCapture(active = false, now = 151_000))
    }
}
