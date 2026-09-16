package com.iptv.player.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWifiLockStateTest {

    @Test
    fun `lock is held only between busy and idle edges`() {
        var acquires = 0
        var releases = 0
        val state = PlaybackWifiLockState(acquire = { acquires++; true }, release = { releases++ })

        assertFalse(state.held)
        state.onPlaybackActivity(true)
        assertTrue(state.held)
        state.onPlaybackActivity(true)
        assertEquals(1, acquires)
        state.onPlaybackActivity(false)
        assertFalse(state.held)
        state.onPlaybackActivity(false)
        assertEquals(1, releases)
    }

    @Test
    fun `failed or throwing acquire leaves nothing held and idle releases nothing`() {
        var releases = 0
        val refusing = PlaybackWifiLockState(acquire = { false }, release = { releases++ })
        refusing.onPlaybackActivity(true)
        assertFalse(refusing.held)
        refusing.onPlaybackActivity(false)
        assertEquals(0, releases)

        val throwing = PlaybackWifiLockState(
            acquire = { throw IllegalStateException("no radio") },
            release = { releases++ },
        )
        throwing.onPlaybackActivity(true)
        assertFalse(throwing.held)
        throwing.onPlaybackActivity(false)
        assertEquals(0, releases)
    }

    @Test
    fun `release exception still clears the held flag`() {
        val state = PlaybackWifiLockState(
            acquire = { true },
            release = { throw SecurityException("revoked") },
        )
        state.onPlaybackActivity(true)
        state.onPlaybackActivity(false)
        assertFalse(state.held)
    }

    @Test
    fun `gate activity edges drive the lock through overlapping sessions`() {
        val gate = PlaybackResourceGate()
        var acquires = 0
        var releases = 0
        val state = PlaybackWifiLockState(acquire = { acquires++; true }, release = { releases++ })
        gate.addActivityListener(state::onPlaybackActivity)

        val preview = gate.begin("live-preview")
        val player = gate.begin("live-player")
        assertEquals(1, acquires)
        assertTrue(state.held)
        gate.end(preview)
        assertTrue(state.held)
        gate.end(player)
        assertFalse(state.held)
        assertEquals(1, releases)
        // A stale duplicate end must not toggle anything.
        gate.end(player)
        assertEquals(1, releases)
    }

    @Test
    fun `late registration sees an already open session`() {
        val gate = PlaybackResourceGate()
        val token = gate.begin("vod")
        val state = PlaybackWifiLockState(acquire = { true }, release = {})
        val handle = gate.addActivityListener(state::onPlaybackActivity)
        assertTrue(state.held)
        handle.close()
        gate.end(token)
        // Closed registration: no further edges are delivered.
        assertTrue(state.held)
    }
}
