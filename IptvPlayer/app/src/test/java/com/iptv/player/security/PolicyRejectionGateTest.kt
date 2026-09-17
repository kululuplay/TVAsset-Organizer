package com.iptv.player.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyRejectionGateTest {

    @Test
    fun `logs only once per process`() {
        val gate = PolicyRejectionGate()
        assertTrue(gate.shouldLog())
        assertFalse(gate.shouldLog())
        assertFalse(gate.shouldLog())
    }

    @Test
    fun `records one stability event per interval`() {
        val gate = PolicyRejectionGate(eventIntervalMs = 3_600_000L)
        assertTrue(gate.shouldRecordEvent(nowMs = 1_000L))
        assertFalse(gate.shouldRecordEvent(nowMs = 1_000L + 60_000L))
        assertFalse(gate.shouldRecordEvent(nowMs = 1_000L + 3_599_999L))
        assertTrue(gate.shouldRecordEvent(nowMs = 1_000L + 3_600_000L))
        assertFalse(gate.shouldRecordEvent(nowMs = 1_000L + 3_600_001L))
    }

    @Test
    fun `clock going backwards does not silence the event forever`() {
        val gate = PolicyRejectionGate(eventIntervalMs = 3_600_000L)
        assertTrue(gate.shouldRecordEvent(nowMs = 5_000_000L))
        assertTrue(gate.shouldRecordEvent(nowMs = 10L))
    }
}
