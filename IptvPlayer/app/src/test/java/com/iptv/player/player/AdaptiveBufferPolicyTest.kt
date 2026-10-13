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
    fun `adaptive starts at normal and grows with rebuffer history`() {
        // 1.6.1: never LOW's 0.5 s/1 s thresholds in the first seconds of a channel.
        assertEquals(BufferMode.NORMAL, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, false, 0))
        assertEquals(BufferMode.NORMAL, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, false, 1))
        assertEquals(BufferMode.NORMAL, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, false, 2))
        assertEquals(BufferMode.HIGH, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, false, 3))
        assertEquals(BufferMode.HIGH, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, false, 6))
    }

    @Test
    fun `constrained devices start at normal and reach high only after repeated rebuffers`() {
        assertEquals(BufferMode.NORMAL, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, true, 0))
        assertEquals(BufferMode.NORMAL, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, true, 2))
        assertEquals(BufferMode.HIGH, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, true, 3))
        assertEquals(BufferMode.HIGH, AdaptiveBufferPolicy.resolve(BufferMode.ADAPTIVE, true, 6))
    }

    @Test
    fun `remote override replaces only the adaptive choice`() {
        assertEquals(
            BufferMode.HIGH,
            AdaptiveBufferPolicy.configuredWithOverride(BufferMode.ADAPTIVE, BufferMode.HIGH),
        )
        assertEquals(
            BufferMode.ADAPTIVE,
            AdaptiveBufferPolicy.configuredWithOverride(BufferMode.ADAPTIVE, null),
        )
        listOf(BufferMode.LOW, BufferMode.NORMAL, BufferMode.HIGH).forEach { user ->
            assertEquals(user, AdaptiveBufferPolicy.configuredWithOverride(user, BufferMode.ADAPTIVE))
            assertEquals(user, AdaptiveBufferPolicy.configuredWithOverride(user, BufferMode.HIGH))
        }
    }
}
