package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreenFrameRecoveryGateTest {

    @Test
    fun `transient startup green recovers without a restart`() {
        val gate = GreenFrameRecoveryGate()

        for (time in 0L..5_500L step 250L) {
            assertEquals(
                GreenFrameRecoveryGate.Decision.WAIT,
                gate.onSample(solidGreen = true, nowMs = time),
            )
        }

        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(solidGreen = false, nowMs = 5_750L),
        )
        assertEquals(
            GreenFrameRecoveryGate.Decision.FIRST_HEALTHY_FRAME,
            gate.onSample(solidGreen = false, nowMs = 6_000L),
        )
        assertTrue(gate.hasHealthyFrame)
    }

    @Test
    fun `persistent startup green fails once after the grace window`() {
        val gate = GreenFrameRecoveryGate()
        var decision = GreenFrameRecoveryGate.Decision.WAIT

        for (time in 0L..7_000L step 250L) {
            decision = gate.onSample(solidGreen = true, nowMs = time)
        }

        assertEquals(GreenFrameRecoveryGate.Decision.SOLID_GREEN_FAILURE, decision)
        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(solidGreen = true, nowMs = 7_250L),
        )
        assertFalse(gate.hasHealthyFrame)
    }

    @Test
    fun `healthy sample cancels all earlier green evidence`() {
        val gate = GreenFrameRecoveryGate()

        gate.onSample(solidGreen = true, nowMs = 0L)
        gate.onSample(solidGreen = true, nowMs = 5_900L)
        gate.onSample(solidGreen = false, nowMs = 6_000L)
        assertEquals(
            GreenFrameRecoveryGate.Decision.FIRST_HEALTHY_FRAME,
            gate.onSample(solidGreen = false, nowMs = 6_200L),
        )

        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(solidGreen = true, nowMs = 6_300L),
        )
        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(solidGreen = false, nowMs = 6_500L),
        )
    }

    @Test
    fun `green output after healthy playback uses the shorter steady deadline`() {
        val gate = GreenFrameRecoveryGate()
        gate.onSample(solidGreen = false, nowMs = 0L)
        gate.onSample(solidGreen = false, nowMs = 200L)

        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(solidGreen = true, nowMs = 1_000L),
        )
        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(solidGreen = true, nowMs = 2_000L),
        )
        assertEquals(
            GreenFrameRecoveryGate.Decision.SOLID_GREEN_FAILURE,
            gate.onSample(solidGreen = true, nowMs = 2_250L),
        )
    }

    @Test
    fun `single healthy sample is not enough to expose a stale surface`() {
        val gate = GreenFrameRecoveryGate()

        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(solidGreen = false, nowMs = 0L),
        )
        assertFalse(gate.hasHealthyFrame)
        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(solidGreen = true, nowMs = 100L),
        )
        assertFalse(gate.hasHealthyFrame)
    }

    @Test
    fun `blank surface never confirms healthy playback`() {
        val gate = GreenFrameRecoveryGate()

        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(
                solidGreen = false,
                visuallyBlank = true,
                nowMs = 0L,
            ),
        )
        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(
                solidGreen = false,
                visuallyBlank = true,
                nowMs = 500L,
            ),
        )
        assertFalse(gate.hasHealthyFrame)
    }

    @Test
    fun `persistent startup blank fails only after its long grace window`() {
        val gate = GreenFrameRecoveryGate()

        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(
                solidGreen = false,
                visuallyBlank = true,
                nowMs = 0L,
            ),
        )
        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(
                solidGreen = false,
                visuallyBlank = true,
                nowMs = 8_900L,
            ),
        )
        assertEquals(
            GreenFrameRecoveryGate.Decision.SOLID_BLANK_FAILURE,
            gate.onSample(
                solidGreen = false,
                visuallyBlank = true,
                nowMs = 9_000L,
            ),
        )
    }

    @Test
    fun `persistent blank after healthy playback triggers recovery`() {
        val gate = GreenFrameRecoveryGate()
        gate.onSample(solidGreen = false, nowMs = 0L)
        gate.onSample(solidGreen = false, nowMs = 200L)

        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(
                solidGreen = false,
                visuallyBlank = true,
                nowMs = 1_000L,
            ),
        )
        assertEquals(
            GreenFrameRecoveryGate.Decision.SOLID_BLANK_FAILURE,
            gate.onSample(
                solidGreen = false,
                visuallyBlank = true,
                nowMs = 8_000L,
            ),
        )
    }

    @Test
    fun `blank sample resets a partial healthy confirmation`() {
        val gate = GreenFrameRecoveryGate()
        gate.onSample(solidGreen = false, nowMs = 0L)
        gate.onSample(
            solidGreen = false,
            visuallyBlank = true,
            nowMs = 200L,
        )

        assertEquals(
            GreenFrameRecoveryGate.Decision.WAIT,
            gate.onSample(solidGreen = false, nowMs = 400L),
        )
        assertEquals(
            GreenFrameRecoveryGate.Decision.FIRST_HEALTHY_FRAME,
            gate.onSample(solidGreen = false, nowMs = 600L),
        )
    }
}
