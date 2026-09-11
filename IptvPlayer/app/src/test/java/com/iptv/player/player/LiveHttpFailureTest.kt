package com.iptv.player.player

import com.iptv.player.data.model.StreamFormat
import org.junit.Assert.*
import org.junit.Test

class LiveHttpFailureTest {
    @Test fun `authoritative failures cannot change transport`() {
        for (status in listOf(400, 401, 403, 404, 410, 416)) {
            assertTrue(LiveTransportPolicy.isAuthoritativeHttpFailure(status))
        }
    }
    @Test fun `transient and unknown failures retain recovery`() {
        for (status in listOf(null, 408, 429, 500, 503)) {
            assertFalse(LiveTransportPolicy.isAuthoritativeHttpFailure(status))
        }
    }
}
