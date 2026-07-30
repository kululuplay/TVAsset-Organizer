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

        assertNull(gate.onDroppedFrames(nowMs = 5_000, droppedFrames = 20, elapsedMs = 1_000))
        gate.onFirstFrame(nowMs = 10_000)
        assertNull(gate.onDroppedFrames(nowMs = 12_999, droppedFrames = 20, elapsedMs = 1_000))
        assertNull(gate.onDroppedFrames(nowMs = 13_000, droppedFrames = 2, elapsedMs = 1_000))
    }

    @Test
    fun `sustained visible loss trips once after confirmation`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 0,
            windowMs = 2_000,
            thresholdFrames = 12,
            confirmationMs = 1_000,
        )
        gate.onFirstFrame(nowMs = 1_000)

        assertNull(gate.onDroppedFrames(nowMs = 2_000, droppedFrames = 5, elapsedMs = 1_000))
        assertNull(gate.onDroppedFrames(nowMs = 2_500, droppedFrames = 6, elapsedMs = 500))
        assertNull(gate.onDroppedFrames(nowMs = 2_700, droppedFrames = 2, elapsedMs = 200))
        val breach =
            gate.onDroppedFrames(nowMs = 3_700, droppedFrames = 12, elapsedMs = 1_000)

        assertEquals(22, breach?.droppedFrames)
        assertEquals(2_000L, breach?.windowMs)
        assertNull(gate.onDroppedFrames(nowMs = 3_800, droppedFrames = 20, elapsedMs = 100))
    }

    @Test
    fun `old drops leave the rolling window`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 0,
            windowMs = 1_000,
            thresholdFrames = 10,
        )
        gate.onFirstFrame(nowMs = 0)

        assertNull(gate.onDroppedFrames(nowMs = 100, droppedFrames = 6, elapsedMs = 100))
        assertNull(gate.onDroppedFrames(nowMs = 1_101, droppedFrames = 6, elapsedMs = 1))
    }

    @Test
    fun `new first frame resets a prior breach`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 0,
            windowMs = 2_000,
            thresholdFrames = 4,
            confirmationMs = 100,
        )
        gate.onFirstFrame(nowMs = 0)
        assertNull(gate.onDroppedFrames(nowMs = 100, droppedFrames = 4, elapsedMs = 100))
        assertEquals(
            8,
            gate.onDroppedFrames(nowMs = 200, droppedFrames = 4, elapsedMs = 100)
                ?.droppedFrames,
        )

        gate.onFirstFrame(nowMs = 1_000)
        assertNull(gate.onDroppedFrames(nowMs = 1_100, droppedFrames = 3, elapsedMs = 100))
    }

    @Test
    fun `cumulative callback crossing startup grace is ignored`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 3_000,
            windowMs = 2_000,
            thresholdFrames = 12,
            confirmationMs = 1_000,
        )
        gate.onFirstFrame(nowMs = 10_000)

        // Mirrors the device log: 50 drops were accumulated across 5.143s,
        // beginning before the first-frame grace boundary.
        assertNull(
            gate.onDroppedFrames(
                nowMs = 15_300,
                droppedFrames = 50,
                elapsedMs = 5_143,
            ),
        )
    }

    @Test
    fun `surface handoff rearms grace and rejects crossing batch`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 3_000,
            windowMs = 2_000,
            thresholdFrames = 12,
            confirmationMs = 1_000,
        )
        gate.onFirstFrame(nowMs = 10_000)
        gate.onOutputTransition(nowMs = 12_280)

        assertNull(
            gate.onDroppedFrames(
                nowMs = 15_229,
                droppedFrames = 50,
                elapsedMs = 5_143,
            ),
        )
    }

    @Test
    fun `one long severe batch does not switch player`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 0,
            windowMs = 2_000,
            thresholdFrames = 12,
            confirmationMs = 1_000,
        )
        gate.onFirstFrame(nowMs = 0)

        assertNull(
            gate.onDroppedFrames(
                nowMs = 5_143,
                droppedFrames = 50,
                elapsedMs = 5_143,
            ),
        )
        assertEquals(
            20,
            gate.onDroppedFrames(
                nowMs = 10_286,
                droppedFrames = 50,
                elapsedMs = 5_143,
            )?.droppedFrames,
        )
    }

    @Test
    fun `tiny callback cannot confirm an earlier severe burst`() {
        val gate = DroppedFrameRecoveryGate(
            startupGraceMs = 0,
            windowMs = 2_000,
            thresholdFrames = 12,
            confirmationMs = 1_000,
        )
        gate.onFirstFrame(nowMs = 0)

        assertNull(
            gate.onDroppedFrames(
                nowMs = 1_000,
                droppedFrames = 12,
                elapsedMs = 1_000,
            ),
        )
        assertNull(
            gate.onDroppedFrames(
                nowMs = 2_000,
                droppedFrames = 1,
                elapsedMs = 100,
            ),
        )
    }
}
