package com.iptv.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAttemptPolicyTest {

    private val policy = PinAttemptPolicy(threshold = 5, baseLockMs = 30_000L, maxLockMs = 300_000L)

    private fun failTimes(times: Int, from: PinAttemptPolicy.State = PinAttemptPolicy.State.CLEAR, now: Long = 1_000L): PinAttemptPolicy.State {
        var state = from
        repeat(times) { state = policy.onFailure(state, now) }
        return state
    }

    @Test
    fun `fewer than five failures never lock`() {
        val state = failTimes(4)
        assertEquals(4, state.failures)
        assertFalse(policy.isLocked(state, 1_000L))
        assertEquals(0L, policy.remainingLockMs(state, 1_000L))
    }

    @Test
    fun `fifth failure locks for thirty seconds`() {
        val state = failTimes(5)
        assertTrue(policy.isLocked(state, 1_000L))
        assertEquals(30_000L, policy.remainingLockMs(state, 1_000L))
        assertEquals(30, policy.remainingLockSeconds(state, 1_000L))
    }

    @Test
    fun `lock expires once the deadline passes`() {
        val state = failTimes(5)
        assertTrue(policy.isLocked(state, 30_999L))
        assertFalse(policy.isLocked(state, 31_000L))
        assertEquals(0, policy.remainingLockSeconds(state, 31_000L))
    }

    @Test
    fun `further failures double the lockout up to five minutes`() {
        var state = failTimes(5)
        val expected = listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L)
        expected.forEach { duration ->
            state = policy.onFailure(state, 5_000L)
            assertEquals(duration, policy.remainingLockMs(state, 5_000L))
        }
    }

    @Test
    fun `remaining seconds round up so the last second never reads zero`() {
        val state = failTimes(5, now = 0L)
        assertEquals(1, policy.remainingLockSeconds(state, 29_500L))
        assertEquals(30, policy.remainingLockSeconds(state, 1L))
    }

    @Test
    fun `success clears failures and any lockout`() {
        val locked = failTimes(6)
        val cleared = policy.onSuccess()
        assertEquals(PinAttemptPolicy.State.CLEAR, cleared)
        assertTrue(policy.isLocked(locked, 1_000L))
        assertFalse(policy.isLocked(cleared, 1_000L))
    }

    @Test
    fun `absurd failure counts do not overflow`() {
        val state = failTimes(80)
        assertEquals(300_000L, policy.remainingLockMs(state, 1_000L))
    }
}
