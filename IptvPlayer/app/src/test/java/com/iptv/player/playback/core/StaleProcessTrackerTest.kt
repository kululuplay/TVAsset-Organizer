package com.iptv.player.playback.core

import com.iptv.player.playback.core.StaleProcessTracker.InputDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleProcessTrackerTest {

    private val hour = 60L * 60L * 1000L
    private val threshold = 4L * hour
    private fun tracker() = StaleProcessTracker { threshold }

    @Test
    fun `a return after at least the threshold in the background recycles once`() {
        val t = tracker()
        t.onForeground(0L)
        t.onBackground(1_000L)
        t.onForeground(1_000L + threshold)
        assertEquals(threshold, t.onResumed(1_000L + threshold + 50L))
        // The next screen resumed in the same visit must not re-trigger.
        assertNull(t.onResumed(1_000L + threshold + 900L))
    }

    @Test
    fun `a shorter absence is ignored and counts as fresh input`() {
        val t = tracker()
        t.onForeground(0L)
        t.onBackground(10L)
        t.onForeground(10L + threshold - 1L)
        assertNull(t.onResumed(10L + threshold))
        // The return reset the idle clock, so an immediate press is delivered.
        assertEquals(InputDecision.DELIVER, t.onInput(10L + threshold + 5L, healthyPlayback = false).decision)
    }

    @Test
    fun `no recycle before the app was ever in front`() {
        val t = tracker()
        assertNull(t.onResumed(10L * hour))
        assertEquals(InputDecision.DELIVER, t.onInput(10L * hour, healthyPlayback = false).decision)
    }

    @Test
    fun `first press after a long idle in front recycles unless playback is healthy`() {
        val idle = tracker()
        idle.onForeground(0L)
        val result = idle.onInput(threshold + 7L, healthyPlayback = false)
        assertEquals(InputDecision.RECYCLE, result.decision)
        assertEquals(threshold + 7L, result.idleMs)

        val watching = tracker()
        watching.onForeground(0L)
        assertEquals(InputDecision.DELIVER, watching.onInput(threshold + 7L, healthyPlayback = true).decision)
        // The press itself proves the user is there; the next one is normal.
        assertEquals(InputDecision.DELIVER, watching.onInput(threshold + 9L, healthyPlayback = false).decision)
    }

    @Test
    fun `regular use never recycles`() {
        val t = tracker()
        t.onForeground(0L)
        var now = 0L
        repeat(50) {
            now += threshold - 1L
            assertEquals(InputDecision.DELIVER, t.onInput(now, healthyPlayback = false).decision)
        }
    }

    @Test
    fun `after a recycle was requested every event is swallowed`() {
        val t = tracker()
        t.onForeground(0L)
        assertEquals(InputDecision.RECYCLE, t.onInput(threshold, healthyPlayback = false).decision)
        t.markRecycleRequested()
        assertTrue(t.recycleRequested)
        assertEquals(InputDecision.SWALLOW, t.onInput(threshold + 1L, healthyPlayback = false).decision)
        assertEquals(InputDecision.SWALLOW, t.onInput(threshold + 2L, healthyPlayback = true).decision)
    }

    @Test
    fun `a recycle that could not be launched re-arms after another full threshold`() {
        val t = tracker()
        t.onForeground(0L)
        assertEquals(InputDecision.RECYCLE, t.onInput(threshold, healthyPlayback = false).decision)
        t.markRecycleRequested()
        t.recycleNotLaunched(threshold)
        assertFalse(t.recycleRequested)
        assertEquals(InputDecision.DELIVER, t.onInput(threshold + 10L, healthyPlayback = false).decision)
        assertEquals(InputDecision.RECYCLE, t.onInput(threshold + 10L + threshold, healthyPlayback = false).decision)
    }

    @Test
    fun `a background return is not acted on while a recycle is already under way`() {
        val t = tracker()
        t.onForeground(0L)
        t.markRecycleRequested()
        t.onBackground(1L)
        t.onForeground(1L + 2L * threshold)
        assertNull(t.onResumed(1L + 2L * threshold))
    }
}
