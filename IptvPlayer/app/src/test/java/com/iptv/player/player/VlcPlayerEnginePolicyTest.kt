package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcPlayerEnginePolicyTest {

    @Test
    fun `buffering values near one hundred are treated as complete`() {
        assertTrue(isVlcBuffering(99.49f))
        assertFalse(isVlcBuffering(99.5f))
        assertFalse(isVlcBuffering(99.9f))
        assertFalse(isVlcBuffering(100f))
    }

    @Test
    fun `invalid buffering progress stays in buffering state`() {
        assertTrue(isVlcBuffering(Float.NaN))
        assertTrue(isVlcBuffering(Float.NEGATIVE_INFINITY))
        assertTrue(isVlcBuffering(Float.POSITIVE_INFINITY))
    }
}
