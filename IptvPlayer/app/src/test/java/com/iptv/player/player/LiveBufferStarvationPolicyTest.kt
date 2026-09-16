package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBufferStarvationPolicyTest {
    private val policy = LiveBufferStarvationPolicy()

    @Test
    fun `buffered position growing is never a stall`() {
        policy.start()
        var marker = 1_000L
        for (t in 0L..30_000L step 3_000L) {
            marker += 2_000L
            assertFalse(policy.sample(t, buffering = false, bufferMarker = marker))
        }
    }

    @Test
    fun `buffering but growing slowly is left to the ordinary watchdog`() {
        policy.start()
        var marker = 1_000L
        for (t in 0L..30_000L step 3_000L) {
            marker += 1L
            assertFalse(policy.sample(t, buffering = true, bufferMarker = marker))
        }
    }

    @Test
    fun `buffering with a flat buffer for six seconds is a stall`() {
        policy.start()
        assertFalse(policy.sample(0L, buffering = true, bufferMarker = 5_000L))
        assertFalse(policy.sample(3_000L, buffering = true, bufferMarker = 5_000L))
        assertTrue(policy.sample(6_000L, buffering = true, bufferMarker = 5_000L))
        // Fires once; the controller re-arms after its reconnect.
        assertFalse(policy.sample(9_000L, buffering = true, bufferMarker = 5_000L))
    }

    @Test
    fun `not buffering keeps the policy inactive even with a flat marker`() {
        policy.start()
        for (t in 0L..30_000L step 3_000L) {
            assertFalse(policy.sample(t, buffering = false, bufferMarker = 5_000L))
        }
        // A flat spell while playing never seeds the buffering baseline.
        assertFalse(policy.sample(33_000L, buffering = true, bufferMarker = 5_000L))
        assertFalse(policy.sample(36_000L, buffering = true, bufferMarker = 5_000L))
        assertTrue(policy.sample(39_000L, buffering = true, bufferMarker = 5_000L))
    }

    @Test
    fun `unknown marker and unstarted policy never trigger`() {
        assertFalse(policy.sample(0L, buffering = true, bufferMarker = 1L))
        assertFalse(policy.sample(10_000L, buffering = true, bufferMarker = 1L))
        policy.start()
        for (t in 0L..30_000L step 3_000L) {
            assertFalse(policy.sample(t, buffering = true, bufferMarker = -1L))
        }
        policy.reset()
        assertFalse(policy.sample(40_000L, buffering = true, bufferMarker = 1L))
    }

    @Test
    fun `a healthy top-up that resumes resets the flat baseline`() {
        policy.start()
        assertFalse(policy.sample(0L, buffering = true, bufferMarker = 5_000L))
        assertFalse(policy.sample(3_000L, buffering = true, bufferMarker = 5_000L))
        assertFalse(policy.sample(6_000L, buffering = false, bufferMarker = 5_000L))
        assertFalse(policy.sample(9_000L, buffering = true, bufferMarker = 5_000L))
        assertFalse(policy.sample(12_000L, buffering = true, bufferMarker = 5_000L))
        assertTrue(policy.sample(15_000L, buffering = true, bufferMarker = 5_000L))
    }
}
