package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceProgressProbePolicyTest {
    private val probe = SurfaceProgressProbePolicy()

    @Test
    fun `static healthy picture never proves resumed video`() {
        repeat(3) { assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, probe.onSample(picture(0))) }
        assertEquals(SurfaceProgressProbePolicy.Decision.FINISHED, probe.onSample(picture(0)))
        assertEquals(SurfaceProgressProbePolicy.Decision.FINISHED, probe.onSample(picture(1)))
    }

    @Test
    fun `two fresh changing healthy pictures prove output progress`() {
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, probe.onSample(picture(0)))
        assertEquals(SurfaceProgressProbePolicy.Decision.PROGRESS, probe.onSample(picture(1)))
    }

    @Test
    fun `unavailable capture never proves video resumed`() {
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, probe.onSample(picture(0)))
        assertEquals(SurfaceProgressProbePolicy.Decision.FINISHED, probe.onSample(null))
        assertEquals(SurfaceProgressProbePolicy.Decision.FINISHED, probe.onSample(picture(1)))
    }

    @Test
    fun `cancel invalidates evidence and rejects a late changed image`() {
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, probe.onSample(picture(0)))
        probe.cancel()
        assertEquals(SurfaceProgressProbePolicy.Decision.FINISHED, probe.onSample(picture(1)))
    }

    @Test
    fun `a new probe cannot reuse an earlier sessions image`() {
        probe.onSample(picture(0))
        probe.cancel()
        val next = SurfaceProgressProbePolicy()
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, next.onSample(picture(1)))
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, next.onSample(picture(1)))
        assertEquals(SurfaceProgressProbePolicy.Decision.PROGRESS, next.onSample(picture(2)))
    }

    @Test
    fun `green and blank captures are never motion evidence`() {
        val black = IntArray(32 * 18) { 0xff000000.toInt() }
        val green = IntArray(32 * 18) { 0xff00bb00.toInt() }
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, probe.onSample(black))
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, probe.onSample(green))
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, probe.onSample(black))
        assertEquals(SurfaceProgressProbePolicy.Decision.FINISHED, probe.onSample(green))
    }

    @Test
    fun `bad capture discards preceding healthy evidence`() {
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, probe.onSample(picture(0)))
        assertEquals(
            SurfaceProgressProbePolicy.Decision.WAIT,
            probe.onSample(IntArray(32 * 18) { 0xff000000.toInt() }),
        )
        assertEquals(SurfaceProgressProbePolicy.Decision.WAIT, probe.onSample(picture(1)))
        assertEquals(SurfaceProgressProbePolicy.Decision.PROGRESS, probe.onSample(picture(2)))
    }

    private fun picture(frame: Int): IntArray = IntArray(32 * 18) { index ->
        if ((index + frame) % 4 == 0) 0xffdd8866.toInt() else 0xff4477aa.toInt()
    }
}
