package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackDiagnosticGateTest {
    @Test
    fun `diagnostics require an explicit session`() {
        val gate = LivePlaybackDiagnosticGate()

        assertFalse(gate.isActive(0L))
        assertNull(gate.record("state_ready", 0L))
        gate.beginSession(0L)
        assertTrue(gate.isActive(0L))
        assertNotNull(gate.record("state_ready", 0L))
    }

    @Test
    fun `a rolling second permits at most four emitted events`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(0L)

        for (index in 0..3) {
            assertNotNull(gate.record("event_$index", 900L + index))
        }
        // Crossing a wall-clock second must not allow a second burst.
        assertNull(gate.record("event_next", 1_000L))
        assertNull(gate.record("event_next", 1_899L))
        val permitted = gate.record("event_next", 1_900L)!!
        assertEquals(2, permitted.suppressed)
        assertEquals(3, permitted.eventCounts["event_next"])
        assertNull(gate.record("event_another", 1_900L))
        assertNotNull(gate.record("event_another", 1_901L))
    }

    @Test
    fun `a sustained burst emits at most 480 diagnostics over a session`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(0L)
        var emitted = 0

        for (time in 0L until 120_000L) {
            if (gate.record("event_${time % 4}", time) != null) emitted++
        }

        assertEquals(480, emitted)
        assertNull(gate.record("sample", 120_000L))
        assertFalse(gate.isActive(120_000L))
    }

    @Test
    fun `duplicate interval uses the last emission not the last attempt`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(0L)

        assertNotNull(gate.record("state_ready", 0L))
        assertNull(gate.record("state_ready", 100L))
        assertNull(gate.record("state_ready", 249L))
        val permitted = gate.record("state_ready", 250L)!!
        assertEquals(2, permitted.suppressed)
        assertEquals(mapOf("state_ready" to 4), permitted.eventCounts)
    }

    @Test
    fun `suppressed oscillations survive in cumulative detached snapshots`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(0L)

        val first = gate.record("state_ready", 0L)!!
        gate.record("state_buffering", 10L)
        for (time in 20L..200L step 20L) {
            assertNull(gate.record("state_ready", time))
            assertNull(gate.record("state_buffering", time + 10L))
        }

        val snapshot = gate.record("sample", 220L)!!
        assertEquals(20, snapshot.suppressed)
        assertEquals(11, snapshot.eventCounts["state_ready"])
        assertEquals(11, snapshot.eventCounts["state_buffering"])
        assertEquals(1, snapshot.eventCounts["sample"])
        assertEquals(mapOf("state_ready" to 1), first.eventCounts)
        assertEquals(0, gate.record("sample", 470L)!!.suppressed)
        assertEquals(1, snapshot.eventCounts["sample"])
    }

    @Test
    fun `new sessions reset budgets duplicate timers counters and suppression`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(1_000L)
        for (index in 0..3) gate.record("event_$index", 1_000L)
        assertNull(gate.record("event_0", 1_001L))

        gate.beginSession(0L)
        val first = gate.record("event_0", 0L)!!
        assertEquals(0, first.suppressed)
        assertEquals(mapOf("event_0" to 1), first.eventCounts)
        for (index in 1..3) assertNotNull(gate.record("event_$index", 0L))
    }

    @Test
    fun `session expires exactly after two minutes and cannot revive on a clock retreat`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(1_000L)

        assertTrue(gate.isActive(120_999L))
        assertNotNull(gate.record("sample", 120_999L))
        assertFalse(gate.isActive(121_000L))
        assertNull(gate.record("state_ready", 121_000L))
        assertFalse(gate.isActive(1_000L))
        assertNull(gate.record("state_ready", 1_000L))
        gate.beginSession(2_000L)
        assertNotNull(gate.record("state_ready", 2_000L))
    }

    @Test
    fun `record also expires a session without a prior active check`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(0L)

        assertNull(gate.record("state_ready", 120_000L))
        assertFalse(gate.isActive(0L))
    }

    @Test
    fun `clock regressions cannot refill a budget or reset duplicate timing`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(1_000L)
        assertNotNull(gate.record("state_ready", 1_000L))

        assertNull(gate.record("state_ready", -100L))
        assertNull(gate.record("state_ready", 1_249L))
        assertNotNull(gate.record("state_ready", 1_250L))
        for (index in 0..1) assertNotNull(gate.record("event_$index", 1_250L))
        assertNull(gate.record("event_next", 0L))
        assertNull(gate.record("event_next", 1_999L))
        assertNotNull(gate.record("event_next", 2_000L))
    }

    @Test
    fun `clock extremes do not overflow elapsed time`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(Long.MIN_VALUE)
        assertNotNull(gate.record("state_ready", Long.MIN_VALUE))
        assertNull(gate.record("state_ready", Long.MAX_VALUE))

        gate.beginSession(Long.MAX_VALUE - 120_000L)
        assertTrue(gate.isActive(Long.MAX_VALUE - 1L))
        assertFalse(gate.isActive(Long.MAX_VALUE))
    }

    @Test
    fun `event map stays bounded and overflow counts are not lost`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(0L)

        for (index in 0..99) gate.record("event_$index", index * 1_000L)
        val snapshot = gate.record("sample", 100_000L)!!
        assertEquals(32, snapshot.eventCounts.size)
        assertEquals(101, snapshot.eventCounts.values.sum())
        assertEquals(70, snapshot.eventCounts["other"])
    }

    @Test
    fun `non symbolic event input is not retained in diagnostic counts`() {
        val gate = LivePlaybackDiagnosticGate()
        gate.beginSession(0L)
        val invalidEvents = listOf(
            "https://example.invalid/live/user/password/123.ts",
            "channel name",
            "state_ready\nsource=private",
            "a".repeat(49),
            "",
        )

        invalidEvents.forEachIndexed { index, event ->
            val snapshot = gate.record(event, index * 1_000L)!!
            assertEquals(mapOf("other" to index + 1), snapshot.eventCounts)
        }
    }
}
