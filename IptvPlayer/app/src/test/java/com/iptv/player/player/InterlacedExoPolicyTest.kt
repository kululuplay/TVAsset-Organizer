package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterlacedExoPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `fails fast only for interlaced streams on Amlogic with fresh stall evidence`() {
        assertTrue(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = true, stallLearnedAtMs = now - 60_000L, nowMs = now))
    }

    @Test
    fun `progressive or unknown streams always try Exo`() {
        assertFalse(InterlacedExoPolicy.failFast(interlaced = false, amlogicDecoder = true, stallLearnedAtMs = now - 60_000L, nowMs = now))
        assertFalse(InterlacedExoPolicy.failFast(interlaced = null, amlogicDecoder = true, stallLearnedAtMs = now - 60_000L, nowMs = now))
    }

    @Test
    fun `non-Amlogic decoders and devices without evidence try Exo`() {
        assertFalse(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = false, stallLearnedAtMs = now - 60_000L, nowMs = now))
        assertFalse(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = true, stallLearnedAtMs = null, nowMs = now))
    }

    @Test
    fun `evidence expires and a clock set into the past does not count`() {
        val expired = now - InterlacedExoPolicy.LEARNED_STALL_TTL_MS
        assertFalse(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = true, stallLearnedAtMs = expired, nowMs = now))
        assertFalse(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = true, stallLearnedAtMs = now + 1L, nowMs = now))
    }

    @Test
    fun `only an interlaced stall on Amlogic is recorded`() {
        assertTrue(InterlacedExoPolicy.shouldRecordStall(interlaced = true, amlogicDecoder = true))
        assertFalse(InterlacedExoPolicy.shouldRecordStall(interlaced = false, amlogicDecoder = true))
        assertFalse(InterlacedExoPolicy.shouldRecordStall(interlaced = true, amlogicDecoder = false))
        assertFalse(InterlacedExoPolicy.shouldRecordStall(interlaced = null, amlogicDecoder = true))
    }
}
