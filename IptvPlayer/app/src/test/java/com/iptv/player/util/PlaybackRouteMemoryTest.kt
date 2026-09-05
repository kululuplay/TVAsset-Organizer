package com.iptv.player.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackRouteMemoryTest {

    @Test
    fun `old software routes are re-evaluated but working hardware routes are retained`() {
        assertFalse(PlaybackRouteMemory.acceptsStoredRoute(4, "VLC_SW"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(4, "EXO"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(4, "VLC_HW"))
        assertFalse(PlaybackRouteMemory.acceptsStoredRoute(5, "VLC_SW"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(5, "EXO"))
        assertTrue(PlaybackRouteMemory.acceptsStoredRoute(5, "VLC_HW"))
    }

    @Test
    fun `new proven software fallback can still be remembered`() {
        for (stage in listOf("EXO", "VLC_HW", "VLC_SW")) {
            assertTrue(PlaybackRouteMemory.acceptsStoredRoute(6, stage))
            assertFalse(PlaybackRouteMemory.acceptsStoredRoute(3, stage))
            assertFalse(PlaybackRouteMemory.acceptsStoredRoute(7, stage))
        }
        assertFalse(PlaybackRouteMemory.acceptsStoredRoute(6, "unknown"))
    }

    @After
    fun tearDown() {
        PlaybackRouteMemory.clear()
    }

    @Test
    fun `quality failure immediately forgets matching learned route`() {
        PlaybackRouteMemory.markStable("channel-key", "EXO")

        PlaybackRouteMemory.forget("channel-key", "EXO")

        assertNull(PlaybackRouteMemory.bestStage("channel-key"))
    }

    @Test
    fun `failure from another stage does not erase learned route`() {
        PlaybackRouteMemory.markStable("channel-key", "VLC_HW")

        PlaybackRouteMemory.forget("channel-key", "EXO")

        assertEquals("VLC_HW", PlaybackRouteMemory.bestStage("channel-key"))
    }
}
