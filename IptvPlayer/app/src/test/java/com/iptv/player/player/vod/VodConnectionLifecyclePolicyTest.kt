package com.iptv.player.player.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class VodConnectionLifecyclePolicyTest {

    @Test
    fun `leaving the player releases the backend even before playback starts`() {
        assertEquals(
            VodConnectionLifecyclePolicy.Teardown.RELEASE,
            VodConnectionLifecyclePolicy.onStop(
                isFinishing = true,
                engineExists = true,
                connectionMayBeOpen = false,
            ),
        )
    }

    @Test
    fun `leaving active playback releases instead of merely stopping`() {
        assertEquals(
            VodConnectionLifecyclePolicy.Teardown.RELEASE,
            VodConnectionLifecyclePolicy.onStop(
                isFinishing = true,
                engineExists = true,
                connectionMayBeOpen = true,
            ),
        )
    }

    @Test
    fun `backgrounding active playback stops but keeps the backend`() {
        assertEquals(
            VodConnectionLifecyclePolicy.Teardown.STOP,
            VodConnectionLifecyclePolicy.onStop(
                isFinishing = false,
                engineExists = true,
                connectionMayBeOpen = true,
            ),
        )
    }

    @Test
    fun `backgrounding before a connection opens does nothing`() {
        assertEquals(
            VodConnectionLifecyclePolicy.Teardown.NONE,
            VodConnectionLifecyclePolicy.onStop(
                isFinishing = false,
                engineExists = true,
                connectionMayBeOpen = false,
            ),
        )
    }
}
