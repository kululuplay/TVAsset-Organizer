package com.iptv.player.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastDevicePolicyTest {

    @Test
    fun `television playback devices do not initialize sender stack`() {
        assertFalse(CastDevicePolicy.shouldInitialize(isTelevision = true))
    }

    @Test
    fun `phones and tablets keep cast sender support`() {
        assertTrue(CastDevicePolicy.shouldInitialize(isTelevision = false))
    }
}
