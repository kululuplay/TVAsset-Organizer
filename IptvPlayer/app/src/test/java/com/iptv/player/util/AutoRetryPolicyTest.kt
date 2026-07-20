/*
 * AutoRetryPolicyTest.kt
 * Pure-JVM tests for the post-fatal automatic retry schedule. The policy must
 * grow (never hammer a dead stream), cap at two minutes, and stop after the
 * last scheduled attempt so an unattended TV doesn't retry forever.
 */
package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRetryPolicyTest {

    @Test
    fun `first attempt waits 15 seconds`() {
        assertEquals(15_000L, AutoRetryPolicy.delayForAttempt(0))
    }

    @Test
    fun `delays never shrink between attempts`() {
        for (i in 1 until AutoRetryPolicy.maxAttempts) {
            val prev = AutoRetryPolicy.delayForAttempt(i - 1)!!
            val cur = AutoRetryPolicy.delayForAttempt(i)!!
            assertTrue("delay[$i]=$cur must be >= delay[${i - 1}]=$prev", cur >= prev)
        }
    }

    @Test
    fun `delays cap at two minutes`() {
        for (i in 0 until AutoRetryPolicy.maxAttempts) {
            assertTrue(AutoRetryPolicy.delayForAttempt(i)!! <= 120_000L)
        }
    }

    @Test
    fun `schedule stops after the last attempt`() {
        assertNull(AutoRetryPolicy.delayForAttempt(AutoRetryPolicy.maxAttempts))
        assertNull(AutoRetryPolicy.delayForAttempt(AutoRetryPolicy.maxAttempts + 5))
    }

    @Test
    fun `negative attempt is clamped to the first delay`() {
        assertEquals(AutoRetryPolicy.delayForAttempt(0), AutoRetryPolicy.delayForAttempt(-1))
    }
}
