package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeFrameCadenceTest {

    @Test
    fun `counts every frame and the frames of the last second`() {
        val cadence = NativeFrameCadence()
        // 25 fps for two seconds.
        for (i in 0 until 50) cadence.onFrame(1_000L + i * 40L)
        val snapshot = cadence.snapshot(nowMs = 2_960L)
        assertEquals(50L, snapshot.total)
        assertEquals(25, snapshot.lastSecond)
        assertEquals(0L, snapshot.lastAgeMs)
    }

    @Test
    fun `frames older than one second leave the window`() {
        val cadence = NativeFrameCadence()
        repeat(5) { cadence.onFrame(1_000L + it * 40L) } // MiTV-style 5 frames, then nothing.
        val snapshot = cadence.snapshot(nowMs = 9_000L)
        assertEquals(5L, snapshot.total)
        assertEquals(0, snapshot.lastSecond)
        assertEquals(7_840L, snapshot.lastAgeMs)
        // A frame exactly one second old is out, one millisecond younger is in.
        cadence.onFrame(8_000L)
        cadence.onFrame(8_001L)
        assertEquals(1, cadence.snapshot(nowMs = 9_000L).lastSecond)
    }

    @Test
    fun `ring wrap keeps the running total and the newest stamps`() {
        val cadence = NativeFrameCadence(capacity = 128)
        for (i in 0 until 300) cadence.onFrame(i * 10L) // 100 fps for three seconds.
        val snapshot = cadence.snapshot(nowMs = 2_990L)
        assertEquals(300L, snapshot.total)
        assertEquals(100, snapshot.lastSecond)
        assertEquals(0L, snapshot.lastAgeMs)
    }

    @Test
    fun `reset forgets every frame`() {
        val cadence = NativeFrameCadence()
        repeat(40) { cadence.onFrame(1_000L + it) }
        cadence.reset()
        val snapshot = cadence.snapshot(nowMs = 1_050L)
        assertEquals(0L, snapshot.total)
        assertEquals(0, snapshot.lastSecond)
        assertNull(snapshot.lastAgeMs)
    }
}
