package com.iptv.player.player

import com.iptv.player.data.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTransportPolicyTest {

    @Test
    fun `source startup switches TS to HLS once on the same endpoint`() {
        val result = LiveTransportPolicy.alternate(
            currentUrl = "https://kululu.live/stream/12.ts?token=x",
            currentFormat = StreamFormat.TS,
            failure = LiveTransportPolicy.Failure.SOURCE_STARTUP,
            alreadyTried = false,
        )
        assertEquals(StreamFormat.HLS, result?.format)
        assertEquals("https://kululu.live/stream/12.m3u8?token=x", result?.url)
        assertNull(
            LiveTransportPolicy.alternate(
                result!!.url,
                result.format,
                LiveTransportPolicy.Failure.SOURCE_STARTUP,
                alreadyTried = true,
            ),
        )
    }

    @Test
    fun `auth and not found never change format`() {
        listOf(401, 403, 404).forEach { status ->
            assertNull(
                LiveTransportPolicy.alternate(
                    "https://kululu.live/live/12.ts",
                    StreamFormat.TS,
                    LiveTransportPolicy.Failure.MANIFEST,
                    httpStatus = status,
                    alreadyTried = false,
                ),
            )
        }
    }

    @Test
    fun `HLS fallback preserves the exact HTTP endpoint and suffix data`() {
        val result = LiveTransportPolicy.alternate(
            currentUrl = "http://media.example:8080/stream/12.m3u8?token=x#live",
            currentFormat = StreamFormat.HLS,
            failure = LiveTransportPolicy.Failure.MANIFEST,
            alreadyTried = false,
        )

        assertEquals(StreamFormat.TS, result?.format)
        assertEquals(
            "http://media.example:8080/stream/12.ts?token=x#live",
            result?.url,
        )
    }

    @Test
    fun `codec errors and non rewriteable urls stay untouched`() {
        assertNull(
            LiveTransportPolicy.alternate(
                "https://kululu.live/live/12.ts",
                StreamFormat.TS,
                LiveTransportPolicy.Failure.DECODE,
                alreadyTried = false,
            ),
        )
        assertNull(
            LiveTransportPolicy.alternate(
                "https://kululu.live/live/12",
                null,
                LiveTransportPolicy.Failure.SOURCE_STARTUP,
                alreadyTried = false,
            ),
        )
    }
}
