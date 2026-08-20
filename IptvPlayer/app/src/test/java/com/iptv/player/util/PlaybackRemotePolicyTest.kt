package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRemotePolicyTest {

    @Test
    fun `remote timeouts are clamped to safe bounds`() {
        val snapshot = PlaybackRemotePolicy.sanitize(
            expiresAtEpochMs = 50_000L,
            disablePixelCopyValidation = true,
            vodConnectTimeoutMs = 1,
            vodReadTimeoutMs = Int.MAX_VALUE,
            allowSourceEngineFallback = false,
        )

        assertTrue(snapshot.disablePixelCopyValidation)
        assertEquals(5_000, snapshot.vodConnectTimeoutMs)
        assertEquals(60_000, snapshot.vodReadTimeoutMs)
        assertFalse(snapshot.allowSourceEngineFallback)
    }

    @Test
    fun `normal remote policy values are preserved`() {
        val snapshot = PlaybackRemotePolicy.sanitize(
            expiresAtEpochMs = 50_000L,
            disablePixelCopyValidation = false,
            vodConnectTimeoutMs = 18_000,
            vodReadTimeoutMs = 28_000,
            allowSourceEngineFallback = true,
        )

        assertEquals(18_000, snapshot.vodConnectTimeoutMs)
        assertEquals(28_000, snapshot.vodReadTimeoutMs)
        assertTrue(snapshot.allowSourceEngineFallback)
    }

    @Test
    fun `expired remote policy falls back to safe defaults`() {
        val expired = PlaybackRemotePolicy.sanitize(
            expiresAtEpochMs = 1L,
            disablePixelCopyValidation = true,
            vodConnectTimeoutMs = 30_000,
            vodReadTimeoutMs = 60_000,
            allowSourceEngineFallback = false,
        )

        val effective = PlaybackRemotePolicy.effective(expired, nowMs = 2L)

        assertFalse(effective.disablePixelCopyValidation)
        assertEquals(15_000, effective.vodConnectTimeoutMs)
        assertTrue(effective.allowSourceEngineFallback)
    }
}
