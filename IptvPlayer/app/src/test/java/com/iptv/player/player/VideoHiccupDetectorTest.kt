package com.iptv.player.player

import com.iptv.player.player.VideoHiccupDetector.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoHiccupDetectorTest {

    private fun detector() = VideoHiccupDetector(stallMs = 1_500L, resumedMs = 600L)

    private fun VideoHiccupDetector.poll(
        frameAgeMs: Long?,
        ready: Boolean = true,
        buffering: Boolean = false,
        bufferHealthy: Boolean = true,
        clockAdvanceMs: Long = 2_000L,
    ) = sample(ready, buffering, bufferHealthy, clockAdvanceMs, frameAgeMs, minimumClockAdvanceMs = 500L)

    @Test
    fun `a frame pause that resumes is one hiccup`() {
        val d = detector()
        assertEquals(Event.NONE, d.poll(frameAgeMs = 40L))
        assertEquals(Event.NONE, d.poll(frameAgeMs = 1_800L))
        assertEquals(Event.HICCUP_RESUMED, d.poll(frameAgeMs = 30L))
        // Not reported twice for the same pause.
        assertEquals(Event.NONE, d.poll(frameAgeMs = 40L))
    }

    @Test
    fun `a pause during a rebuffer or with an empty buffer is not a decoder hiccup`() {
        val d = detector()
        assertEquals(Event.NONE, d.poll(frameAgeMs = 2_000L, buffering = true))
        assertEquals(Event.NONE, d.poll(frameAgeMs = 30L))
        assertEquals(Event.NONE, d.poll(frameAgeMs = 2_000L, bufferHealthy = false))
        assertEquals(Event.NONE, d.poll(frameAgeMs = 30L))
    }

    @Test
    fun `a pause with a frozen media clock is a stall for the watchdog not a hiccup`() {
        val d = detector()
        assertEquals(Event.NONE, d.poll(frameAgeMs = 2_000L, clockAdvanceMs = 0L))
        assertEquals(Event.NONE, d.poll(frameAgeMs = 30L))
    }

    @Test
    fun `a rebuffer in the middle of a pause cancels it`() {
        val d = detector()
        assertEquals(Event.NONE, d.poll(frameAgeMs = 1_800L))
        assertEquals(Event.NONE, d.poll(frameAgeMs = 3_000L, buffering = true))
        assertEquals(Event.NONE, d.poll(frameAgeMs = 30L))
    }

    @Test
    fun `unknown frame age is ignored`() {
        val d = detector()
        assertEquals(Event.NONE, d.poll(frameAgeMs = null))
    }
}
