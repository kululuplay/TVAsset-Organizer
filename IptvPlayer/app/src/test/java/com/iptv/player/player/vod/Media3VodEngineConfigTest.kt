package com.iptv.player.player.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Media3VodEngineConfigTest {

    @Test
    fun `IPTV VOD defaults use bounded connect and read timeouts`() {
        val config = Media3VodEngineConfig()

        assertEquals(15_000, config.connectTimeoutMs)
        assertEquals(20_000, config.readTimeoutMs)
    }

    @Test
    fun `invalid network timeouts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Media3VodEngineConfig(connectTimeoutMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Media3VodEngineConfig(readTimeoutMs = 121_000)
        }
    }
}
