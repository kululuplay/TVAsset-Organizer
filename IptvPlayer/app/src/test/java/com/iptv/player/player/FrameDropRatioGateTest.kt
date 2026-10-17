package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameDropRatioGateTest {

    private fun gate() = FrameDropRatioGate(windowMs = 5_000L, startupGraceMs = 3_000L, minFrames = 40, maxRatio = 0.25)

    @Test
    fun `healthy playback never trips`() {
        val g = gate()
        g.onFirstFrame(0L)
        var rendered = 0L
        for (t in 2_000L..20_000L step 2_000L) {
            rendered += 50
            assertNull(g.sample(t + 3_000L, rendered, 2L, buffering = false))
        }
    }

    @Test
    fun `a quarter of the frames dropped over five seconds trips once`() {
        val g = gate()
        g.onFirstFrame(0L)
        // Grace ends at 3 s; 2 s polls with 30 rendered / 20 dropped each.
        var rendered = 0L
        var dropped = 0L
        var breach: FrameDropRatioGate.Breach? = null
        var t = 3_000L
        while (breach == null && t <= 20_000L) {
            rendered += 30
            dropped += 20
            breach = g.sample(t, rendered, dropped, buffering = false)
            t += 2_000L
        }
        assertNotNull(breach)
        assertEquals(0.4, breach!!.ratio, 0.001)
        // Tripped once: later samples are silent.
        assertNull(g.sample(t, rendered + 100, dropped + 100, buffering = false))
    }

    @Test
    fun `drops inside the startup grace are ignored`() {
        val g = gate()
        g.onFirstFrame(10_000L)
        assertNull(g.sample(11_000L, 10, 100, false))
        assertNull(g.sample(12_900L, 20, 200, false))
        // First counted sample at the end of grace; window not full yet.
        assertNull(g.sample(13_000L, 30, 300, false))
    }

    @Test
    fun `a rebuffer clears the window so catch-up drops are not decoder evidence`() {
        val g = gate()
        g.onFirstFrame(0L)
        assertNull(g.sample(3_000L, 100, 0, false))
        assertNull(g.sample(5_000L, 150, 40, false))
        assertNull(g.sample(7_000L, 160, 90, buffering = true))
        // After the rebuffer the window restarts; the earlier drops are gone.
        assertNull(g.sample(9_000L, 200, 90, false))
        assertNull(g.sample(11_000L, 250, 92, false))
        assertNull(g.sample(14_000L, 320, 94, false))
    }

    @Test
    fun `too few frames in the window say nothing`() {
        val g = gate()
        g.onFirstFrame(0L)
        assertNull(g.sample(3_000L, 0, 0, false))
        assertNull(g.sample(8_000L, 10, 20, false))
        assertNull(g.sample(13_000L, 20, 30, false))
    }

    @Test
    fun `restarted counters rebuild the window instead of tripping`() {
        val g = gate()
        g.onFirstFrame(0L)
        assertNull(g.sample(3_000L, 500, 10, false))
        // A new decoder instance restarts both counters.
        assertNull(g.sample(5_000L, 20, 30, false))
        assertNull(g.sample(8_000L, 100, 35, false))
    }
}
