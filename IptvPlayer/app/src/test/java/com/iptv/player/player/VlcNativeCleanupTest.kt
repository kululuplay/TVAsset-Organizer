package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcNativeCleanupTest {

    @Test
    fun `stop failure still proves closure when both releases succeed`() {
        val order = mutableListOf<String>()

        val result = VlcNativeCleanup.runFull(
            detachListener = { order += "listener" },
            stop = {
                order += "stop"
                throw IllegalStateException("stop failed")
            },
            releaseMediaPlayer = { order += "player-release" },
            releaseLibVlc = { order += "libvlc-release" },
        )

        assertEquals(
            listOf("listener", "stop", "player-release", "libvlc-release"),
            order,
        )
        assertEquals(1, result.failures.size)
        assertTrue(result.ownershipDefinitivelyReleased)
    }

    @Test
    fun `listener failure does not weaken successful release proof`() {
        val result = VlcNativeCleanup.runFull(
            detachListener = { throw IllegalStateException("listener failed") },
            stop = {},
            releaseMediaPlayer = {},
            releaseLibVlc = {},
        )

        assertTrue(result.ownershipDefinitivelyReleased)
    }

    @Test
    fun `media player release failure keeps ownership uncertain`() {
        var libReleaseRan = false
        val result = VlcNativeCleanup.runFull(
            detachListener = {},
            stop = {},
            releaseMediaPlayer = { throw IllegalStateException("player release failed") },
            releaseLibVlc = { libReleaseRan = true },
        )

        assertTrue(libReleaseRan)
        assertFalse(result.ownershipDefinitivelyReleased)
    }

    @Test
    fun `libvlc release failure keeps ownership uncertain`() {
        val result = VlcNativeCleanup.runReleaseOnly(
            releaseMediaPlayer = {},
            releaseLibVlc = { throw IllegalStateException("libvlc release failed") },
        )

        assertFalse(result.ownershipDefinitivelyReleased)
    }

    @Test
    fun `release only success proves ownership closure`() {
        val result = VlcNativeCleanup.runReleaseOnly(
            releaseMediaPlayer = {},
            releaseLibVlc = {},
        )

        assertTrue(result.ownershipDefinitivelyReleased)
    }

    @Test
    fun `multiple early failures still execute both release boundaries`() {
        val order = mutableListOf<String>()
        val result = VlcNativeCleanup.runFull(
            detachListener = {
                order += "listener"
                throw IllegalStateException("listener failed")
            },
            stop = {
                order += "stop"
                throw IllegalStateException("stop failed")
            },
            releaseMediaPlayer = { order += "player-release" },
            releaseLibVlc = { order += "libvlc-release" },
        )

        assertEquals(
            listOf("listener", "stop", "player-release", "libvlc-release"),
            order,
        )
        assertEquals(2, result.failures.size)
        assertTrue(result.ownershipDefinitivelyReleased)
    }

    @Test
    fun `unclaimed cleanup is never closure proof`() {
        assertFalse(VlcNativeCleanupResult.notClaimed().ownershipDefinitivelyReleased)
    }
}
