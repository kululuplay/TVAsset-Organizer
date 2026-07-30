package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DroppedFrameRecoveryGateTest {

    @Test
    fun `drops before first frame or during startup grace are ignored`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 3_000,
            windowMs = 2_000,
            thresholdFrames = 12,
        )

        assertNull(gate.onDroppedFrames(nowMs = 5_000, droppedFrames = 20))
        gate.onFirstFrame(nowMs = 10_000)
        assertNull(gate.onDroppedFrames(nowMs = 12_999, droppedFrames = 20))
        assertNull(gate.onDroppedFrames(nowMs = 13_000, droppedFrames = 2))
    }

    @Test
    fun `visible burst trips once with rolling total`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 0,
            windowMs = 2_000,
            thresholdFrames = 12,
        )
        gate.onFirstFrame(nowMs = 1_000)

        assertNull(gate.onDroppedFrames(nowMs = 2_000, droppedFrames = 5))
        assertNull(gate.onDroppedFrames(nowMs = 2_500, droppedFrames = 6))
        val breach = gate.onDroppedFrames(nowMs = 2_700, droppedFrames = 2)

        assertEquals(13, breach?.droppedFrames)
        assertEquals(2_000L, breach?.windowMs)
        assertNull(gate.onDroppedFrames(nowMs = 2_800, droppedFrames = 20))
    }

    @Test
    fun `old drops leave the rolling window`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 0,
            windowMs = 1_000,
            thresholdFrames = 10,
        )
        gate.onFirstFrame(nowMs = 0)

        assertNull(gate.onDroppedFrames(nowMs = 100, droppedFrames = 6))
        assertNull(gate.onDroppedFrames(nowMs = 1_101, droppedFrames = 6))
    }

    @Test
    fun `new first frame resets a prior breach`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 0,
            windowMs = 2_000,
            thresholdFrames = 4,
        )
        gate.onFirstFrame(nowMs = 0)
        assertEquals(4, gate.onDroppedFrames(nowMs = 100, droppedFrames = 4)?.droppedFrames)

        gate.onFirstFrame(nowMs = 1_000)
        assertNull(gate.onDroppedFrames(nowMs = 1_100, droppedFrames = 3))
    }
}
