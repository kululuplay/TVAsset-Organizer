package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceSampleRetryPolicyTest {

    @Test
    fun `unavailable surface uses bounded exponential retries then disables`() {
        val policy = SurfaceSampleRetryPolicy(
            initialDelayMs = 100L,
            maxDelayMs = 400L,
            maxFailures = 5,
        )

        assertEquals(100L, policy.onUnavailable())
        assertEquals(200L, policy.onUnavailable())
        assertEquals(400L, policy.onUnavailable())
        assertEquals(400L, policy.onUnavailable())
        assertNull(policy.onUnavailable())
        assertNull(policy.onUnavailable())
    }

    @Test
    fun `success and reset restore the full retry budget`() {
        val policy = SurfaceSampleRetryPolicy(10L, 40L, 3)
        assertEquals(10L, policy.onUnavailable())
        assertEquals(20L, policy.onUnavailable())
        policy.onSuccess()
        assertEquals(10L, policy.onUnavailable())
        policy.reset()
        assertEquals(10L, policy.onUnavailable())
    }
}
