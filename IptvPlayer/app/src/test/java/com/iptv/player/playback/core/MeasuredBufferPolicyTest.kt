package com.iptv.player.playback.core

import com.iptv.player.data.model.BufferMode
import org.junit.Assert.*
import org.junit.Test

class MeasuredBufferPolicyTest {
    @Test fun `constrained stream gains time reserve without growing its byte budget`() {
        val initial = MeasuredBufferPolicy.target(true, true, BufferMeasurements())
        val jitter = MeasuredBufferPolicy.target(true, true, BufferMeasurements(2_500, 3_000, 2))
        assertTrue(jitter.restartMs > initial.restartMs)
        assertTrue(jitter.reserveMs > initial.reserveMs)
        assertEquals(24 * 1_048_576, jitter.bytes)
        assertTrue(jitter.reserveMs <= 12_000)
    }
    @Test fun `memory pressure takes precedence over repeated stalls`() {
        val target = MeasuredBufferPolicy.target(true, false, BufferMeasurements(8_000, 8_000, 100, true))
        assertEquals(8 * 1_048_576, target.bytes)
        assertTrue(target.restartMs <= 4_000)
    }
    @Test fun `negative and oversized observations cannot overflow or create unbounded waits`() {
        listOf(-1L, Long.MAX_VALUE).forEach { value ->
            val target = MeasuredBufferPolicy.target(false, true, BufferMeasurements(value, value, Int.MAX_VALUE))
            assertTrue(target.restartMs in 1_000..6_000)
            assertTrue(target.reserveMs in 8_000..20_000)
        }
    }
    @Test fun `healthy loader pauses and tiny packet gaps do not become network jitter`() {
        val window = BufferMeasurementWindow()
        window.networkGap(5_000, 4_000, false)
        window.networkGap(6_000, 5, true)
        assertEquals(0, window.snapshot(6_000, false).networkGapMs)
    }
    @Test fun `real starving gaps expire and reset clears prior content evidence`() {
        val window = BufferMeasurementWindow()
        window.networkGap(5_000, 2_000, true)
        window.rebuffer(3_000)
        assertEquals(2_000, window.snapshot(6_000, false).networkGapMs)
        assertEquals(0, window.snapshot(66_000, false).networkGapMs)
        window.reset()
        assertEquals(BufferMeasurements(), window.snapshot(67_000, false))
    }
    @Test fun `native player honors explicit settings and never exceeds safe adaptive cache`() {
        val sample = BufferMeasurements(8_000, 8_000, 10)
        assertEquals(750, MeasuredBufferPolicy.nativeCacheMs(750, false, true, false, sample))
        assertEquals(3_000, MeasuredBufferPolicy.nativeCacheMs(5_000, true, true, true, sample))
        assertEquals(1_500, MeasuredBufferPolicy.nativeCacheMs(5_000, true, true, true, sample.copy(memoryPressure = true)))
    }
    @Test fun `adaptive is default only when no explicit valid preference exists`() {
        assertEquals(BufferMode.ADAPTIVE, BufferMode.fromName(null))
        assertEquals(BufferMode.NORMAL, BufferMode.fromName("NORMAL"))
        assertEquals(BufferMode.LOW, BufferMode.fromName("LOW"))
        assertEquals(BufferMode.HIGH, BufferMode.fromName("HIGH"))
    }
}
