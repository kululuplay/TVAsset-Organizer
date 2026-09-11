package com.iptv.player.player

import com.iptv.player.data.model.StreamFormat
import org.junit.Assert.*
import org.junit.Test

class LiveTransportPolicyTest {
    @Test fun `old HLS settings and invalid values migrate to TS`() {
        for (value in listOf(null, "HLS", "hls", "TS", "other")) {
            assertEquals(StreamFormat.TS, StreamFormat.fromName(value))
        }
        assertEquals(listOf(StreamFormat.TS), StreamFormat.entries.toList())
    }
}
