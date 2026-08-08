package com.iptv.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodNextEpisodeGateTest {

    @Test
    fun `same completion token can launch only once`() {
        val gate = VodNextEpisodeGate()
        assertTrue(gate.arm(7))
        assertTrue(gate.consume(7))
        assertFalse(gate.consume(7))
        assertFalse(gate.arm(7))
    }

    @Test
    fun `cancel rejects late timer but a new completion can arm`() {
        val gate = VodNextEpisodeGate()
        assertTrue(gate.arm(10))
        gate.cancel()
        assertFalse(gate.consume(10))
        assertFalse(gate.arm(10))
        assertTrue(gate.arm(11))
        assertTrue(gate.consume(11))
    }
}
