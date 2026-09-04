package com.iptv.player.player

import com.iptv.player.data.model.BufferMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveBufferPolicyTest {

    @Test
    fun `explicit choices are never rewritten`() {
        listOf(BufferMode.LOW, BufferMode.NORMAL, BufferMode.HIGH).forEach { mode ->
            assertEquals(mode, AdaptiveBufferPolicy.resolve(mode, true, 99))
        }
    }

    @Test
    fun `adaptive starts fast and grows with rebuffer history`() {
        assertEquals(BufferMode.LOW, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, false, 0))
        assertEquals(BufferMode.NORMAL, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, false, 1))
        assertEquals(BufferMode.HIGH, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, false, 3))
    }

    @Test
    fun `low ram devices are capped at normal`() {
        assertEquals(BufferMode.NORMAL, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, true, 0))
        assertEquals(BufferMode.NORMAL, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, true, 6))
    }
}
