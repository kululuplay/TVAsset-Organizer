package com.iptv.player.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePreviewPressPolicyTest {

    @Test
    fun `first OK starts preview`() {
        assertEquals(
            LivePreviewPressPolicy.Action.START_PREVIEW,
            LivePreviewPressPolicy.decide(
                sameChannel = false,
                phase = LivePreviewPressPolicy.Phase.IDLE,
            ),
        )
    }

    @Test
    fun `rapid second OK queues fullscreen while preview starts`() {
        assertEquals(
            LivePreviewPressPolicy.Action.QUEUE_FULLSCREEN,
            LivePreviewPressPolicy.decide(
                sameChannel = true,
                phase = LivePreviewPressPolicy.Phase.STARTING,
            ),
        )
    }

    @Test
    fun `second OK enters fullscreen only when ready`() {
        assertEquals(
            LivePreviewPressPolicy.Action.ENTER_FULLSCREEN,
            LivePreviewPressPolicy.decide(
                sameChannel = true,
                phase = LivePreviewPressPolicy.Phase.READY,
            ),
        )
    }

    @Test
    fun `OK retries a failed preview`() {
        assertEquals(
            LivePreviewPressPolicy.Action.START_PREVIEW,
            LivePreviewPressPolicy.decide(
                sameChannel = true,
                phase = LivePreviewPressPolicy.Phase.FAILED,
            ),
        )
    }
}
