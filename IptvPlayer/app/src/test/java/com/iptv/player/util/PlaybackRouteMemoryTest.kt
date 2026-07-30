package com.iptv.player.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRouteMemoryTest {

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
