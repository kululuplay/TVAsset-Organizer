package com.iptv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VodChromeFocusChainTest {

    @Test
    fun `all track buttons enabled builds full five-action chain`() {
        val plan = VodChromeFocusChain.plan(
            backId = 1,
            audioId = 2,
            subtitleId = 3,
            aspectId = 4,
            sleepId = 5,
            castId = 6,
            hasAudio = true,
            hasSubtitles = true,
            noId = -1,
        )
        assertEquals(2, plan.backNextFocusRightId)
        assertEquals(4, plan.playPauseNextFocusUpId)
        assertEquals(
            listOf(
                VodChromeFocusChain.ChainLink(2, 1, 3),
                VodChromeFocusChain.ChainLink(3, 2, 4),
                VodChromeFocusChain.ChainLink(4, 3, 5),
                VodChromeFocusChain.ChainLink(5, 4, 6),
                VodChromeFocusChain.ChainLink(6, 5, -1),
            ),
            plan.links,
        )
    }

    @Test
    fun `disabled audio and subtitle buttons are skipped in focus chain`() {
        val plan = VodChromeFocusChain.plan(
            backId = 1,
            audioId = 2,
            subtitleId = 3,
            aspectId = 4,
            sleepId = 5,
            castId = 6,
            hasAudio = false,
            hasSubtitles = false,
            noId = -1,
        )
        assertEquals(4, plan.backNextFocusRightId)
        assertEquals(5, plan.playPauseNextFocusUpId)
        assertEquals(
            listOf(
                VodChromeFocusChain.ChainLink(4, 1, 5),
                VodChromeFocusChain.ChainLink(5, 4, 6),
                VodChromeFocusChain.ChainLink(6, 5, -1),
            ),
            plan.links,
        )
    }
}
