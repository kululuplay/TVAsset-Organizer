package com.iptv.player.ui.home

import com.iptv.player.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeZapTargetTest {

    private val channels = listOf("a", "b", "c").map { Channel(id = it, name = it, streamUrl = "http://$it") }

    @Test
    fun `zap wraps around both ends of the scope`() {
        assertEquals(1, HomeZapTarget.nextIndex(channels, currentId = "a", direction = +1))
        assertEquals(0, HomeZapTarget.nextIndex(channels, currentId = "c", direction = +1))
        assertEquals(2, HomeZapTarget.nextIndex(channels, currentId = "a", direction = -1))
    }

    @Test
    fun `unknown current channel lands on the first row`() {
        assertEquals(0, HomeZapTarget.nextIndex(channels, currentId = null, direction = +1))
        assertEquals(0, HomeZapTarget.nextIndex(channels, currentId = "zz", direction = -1))
    }
}
