package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcEventGenerationGateTest {

    @Test
    fun `events before current MediaChanged are rejected`() {
        val gate = VlcEventGenerationGate()
        val generation = gate.beginPlay()

        assertFalse(gate.acceptsCurrentMediaEvent())
        assertTrue(gate.prepareMediaChange(generation))
        assertFalse(gate.acceptsCurrentMediaEvent())

        gate.onMediaChanged()
        assertTrue(gate.acceptsCurrentMediaEvent())
    }

    @Test
    fun `a newer play invalidates callbacks from the previous media`() {
        val gate = VlcEventGenerationGate()
        val first = gate.beginPlay()
        assertTrue(gate.prepareMediaChange(first))
        gate.onMediaChanged()
        assertTrue(gate.acceptsCurrentMediaEvent())

        val second = gate.beginPlay()
        assertFalse(gate.isActive(first))
        assertFalse(gate.acceptsCurrentMediaEvent())
        assertFalse(gate.prepareMediaChange(first))
        assertTrue(gate.prepareMediaChange(second))
        gate.onMediaChanged()
        assertTrue(gate.acceptsCurrentMediaEvent())
    }

    @Test
    fun `late old MediaChanged cannot activate a newer prepared generation`() {
        val gate = VlcEventGenerationGate()
        val first = gate.beginPlay()
        assertTrue(gate.prepareMediaChange(first))

        val second = gate.beginPlay()
        assertTrue(gate.prepareMediaChange(second))

        // The old native callback arrives only after the newer setMedia boundary
        // has already been queued. It must consume the old FIFO entry and remain
        // rejected for the current channel.
        gate.onMediaChanged()
        assertFalse(gate.acceptsCurrentMediaEvent())

        gate.onMediaChanged()
        assertTrue(gate.acceptsCurrentMediaEvent())
    }

    @Test
    fun `failed setMedia boundary can be cancelled`() {
        val gate = VlcEventGenerationGate()
        val first = gate.beginPlay()
        assertTrue(gate.prepareMediaChange(first))
        gate.cancelPreparedMediaChange(first)

        val second = gate.beginPlay()
        assertTrue(gate.prepareMediaChange(second))
        gate.onMediaChanged()

        assertTrue(gate.acceptsCurrentMediaEvent())
    }

    @Test
    fun `stop invalidates the active native event stream`() {
        val gate = VlcEventGenerationGate()
        val generation = gate.beginPlay()
        gate.prepareMediaChange(generation)
        gate.onMediaChanged()
        assertTrue(gate.acceptsCurrentMediaEvent())

        gate.invalidate()
        assertFalse(gate.acceptsCurrentMediaEvent())
        assertFalse(gate.isActive(generation))
    }
}
