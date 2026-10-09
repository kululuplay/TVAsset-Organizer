package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterlacedExoPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `fails fast only for interlaced streams on Amlogic with fresh stall evidence`() {
        assertTrue(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = true, stallLearnedAtMs = now - 60_000L, nowMs = now, alternativeRouteAvailable = true))
    }

    @Test
    fun `progressive or unknown streams always try Exo`() {
        assertFalse(InterlacedExoPolicy.failFast(interlaced = false, amlogicDecoder = true, stallLearnedAtMs = now - 60_000L, nowMs = now, alternativeRouteAvailable = true))
        assertFalse(InterlacedExoPolicy.failFast(interlaced = null, amlogicDecoder = true, stallLearnedAtMs = now - 60_000L, nowMs = now, alternativeRouteAvailable = true))
    }

    @Test
    fun `non-Amlogic decoders and devices without evidence try Exo`() {
        assertFalse(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = false, stallLearnedAtMs = now - 60_000L, nowMs = now, alternativeRouteAvailable = true))
        assertFalse(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = true, stallLearnedAtMs = null, nowMs = now, alternativeRouteAvailable = true))
    }

    @Test
    fun `evidence expires and a clock set into the past does not count`() {
        val expired = now - InterlacedExoPolicy.LEARNED_STALL_TTL_MS
        assertFalse(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = true, stallLearnedAtMs = expired, nowMs = now, alternativeRouteAvailable = true))
        assertFalse(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = true, stallLearnedAtMs = now + 1L, nowMs = now, alternativeRouteAvailable = true))
    }

    @Test
    fun `fresh learned stall keeps Exo when the controller has no other video stage`() {
        // Constrained Amlogic stick, software HD withheld: failing fast would only
        // reopen Exo, so the frame watchdog must decide instead.
        assertFalse(InterlacedExoPolicy.failFast(interlaced = true, amlogicDecoder = true, stallLearnedAtMs = now - 60_000L, nowMs = now, alternativeRouteAvailable = false))
    }

    @Test
    fun `only an interlaced startup stall on Amlogic is recorded`() {
        assertTrue(InterlacedExoPolicy.shouldRecordStall(interlaced = true, amlogicDecoder = true, framesSinceFirstFrame = 5))
        assertFalse(InterlacedExoPolicy.shouldRecordStall(interlaced = false, amlogicDecoder = true, framesSinceFirstFrame = 5))
        assertFalse(InterlacedExoPolicy.shouldRecordStall(interlaced = true, amlogicDecoder = false, framesSinceFirstFrame = 5))
        assertFalse(InterlacedExoPolicy.shouldRecordStall(interlaced = null, amlogicDecoder = true, framesSinceFirstFrame = 5))
    }

    @Test
    fun `a stall deep into a long session does not teach the quirk`() {
        assertTrue(InterlacedExoPolicy.shouldRecordStall(interlaced = true, amlogicDecoder = true, framesSinceFirstFrame = 119))
        assertFalse(InterlacedExoPolicy.shouldRecordStall(interlaced = true, amlogicDecoder = true, framesSinceFirstFrame = 120))
        assertFalse(InterlacedExoPolicy.shouldRecordStall(interlaced = true, amlogicDecoder = true, framesSinceFirstFrame = 6_725))
    }
}
