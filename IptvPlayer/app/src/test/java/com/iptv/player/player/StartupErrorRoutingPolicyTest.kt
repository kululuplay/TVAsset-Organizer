package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupErrorRoutingPolicyTest {

    @Test
    fun `server errors stay on the current stage`() {
        assertTrue(StartupErrorRoutingPolicy.retrySameStage(500))
        assertTrue(StartupErrorRoutingPolicy.retrySameStage(502))
        assertTrue(StartupErrorRoutingPolicy.retrySameStage(503))
        assertTrue(StartupErrorRoutingPolicy.retrySameStage(504))
    }

    @Test
    fun `client errors, transport errors and unknown status keep the existing ladder`() {
        assertFalse(StartupErrorRoutingPolicy.retrySameStage(404))
        assertFalse(StartupErrorRoutingPolicy.retrySameStage(429))
        assertFalse(StartupErrorRoutingPolicy.retrySameStage(200))
        assertFalse(StartupErrorRoutingPolicy.retrySameStage(null))
    }
}
