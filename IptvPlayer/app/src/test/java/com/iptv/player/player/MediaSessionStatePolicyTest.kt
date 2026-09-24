package com.iptv.player.player

import com.iptv.player.player.MediaSessionStatePolicy.Published
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionStatePolicyTest {

    private val playing = Published(state = 3, actions = 0x7L, positionMs = -1L)

    @Test
    fun `first publish and any change go out`() {
        assertTrue(MediaSessionStatePolicy.shouldPublish(last = null, next = playing))
        assertTrue(MediaSessionStatePolicy.shouldPublish(last = playing, next = playing.copy(state = 2)))
        assertTrue(MediaSessionStatePolicy.shouldPublish(last = playing, next = playing.copy(actions = 0x3L)))
        assertTrue(MediaSessionStatePolicy.shouldPublish(last = playing, next = playing.copy(positionMs = 1_000L)))
    }

    @Test
    fun `an identical state is not republished`() {
        assertFalse(MediaSessionStatePolicy.shouldPublish(last = playing, next = playing.copy()))
    }
}
