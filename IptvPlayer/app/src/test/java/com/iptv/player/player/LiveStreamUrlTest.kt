package com.iptv.player.player

import com.iptv.player.data.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveStreamUrlTest {

    @Test
    fun `TS path is rewritten to HLS`() {
        assertEquals(
            "https://example.test/live/user/pass/42.m3u8",
            LiveStreamUrl.applyFormat(
                "https://example.test/live/user/pass/42.ts",
                StreamFormat.HLS,
            ),
        )
    }

    @Test
    fun `HLS path is rewritten to TS`() {
        assertEquals(
            "https://example.test/live/user/pass/42.ts",
            LiveStreamUrl.applyFormat(
                "https://example.test/live/user/pass/42.m3u8",
                StreamFormat.TS,
            ),
        )
    }

    @Test
    fun `query and fragment are preserved verbatim`() {
        assertEquals(
            "https://example.test/live/42.m3u8?token=a.ts&mode=1#edge",
            LiveStreamUrl.applyFormat(
                "https://example.test/live/42.ts?token=a.ts&mode=1#edge",
                StreamFormat.HLS,
            ),
        )
    }

    @Test
    fun `extension matching is case insensitive while path casing is preserved`() {
        assertEquals(
            "https://EXAMPLE.test/Live/Channel.m3u8?Token=ABC",
            LiveStreamUrl.applyFormat(
                "https://EXAMPLE.test/Live/Channel.TS?Token=ABC",
                StreamFormat.HLS,
            ),
        )
    }

    @Test
    fun `unknown and extensionless paths remain unchanged`() {
        val urls = listOf(
            "https://example.test/movie/42.mp4?token=abc",
            "https://example.test/live/user/pass/42?token=abc",
            "https://example.test/live/42.ts/segment",
            "",
        )

        urls.forEach { url ->
            assertEquals(url, LiveStreamUrl.applyFormat(url, StreamFormat.HLS))
            assertEquals(url, LiveStreamUrl.applyFormat(url, StreamFormat.TS))
        }
    }

    @Test
    fun `same selected format remains canonical and keeps suffix`() {
        assertEquals(
            "https://example.test/live/42.ts#primary",
            LiveStreamUrl.applyFormat(
                "https://example.test/live/42.TS#primary",
                StreamFormat.TS,
            ),
        )
        assertEquals(
            "https://example.test/live/42.m3u8?token=abc",
            LiveStreamUrl.applyFormat(
                "https://example.test/live/42.M3U8?token=abc",
                StreamFormat.HLS,
            ),
        )
    }

    @Test
    fun `route key is stable and excludes URL credentials`() {
        val first = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.TS,
            "https://example.test/live/user/pass/42.ts?token=secret",
        )
        val rotatedCredentials = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.TS,
            "https://example.test/live/example/sample/42.ts?token=new-secret",
        )

        assertEquals(first, rotatedCredentials)
        assertFalse(first.contains("user"))
        assertFalse(first.contains("sample"))
        assertFalse(first.contains("secret"))
    }

    @Test
    fun `route key separates providers formats and policy generation`() {
        val ts = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.TS,
            "https://one.example/live/42.ts",
        )
        val hls = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.HLS,
            "https://one.example/live/42.m3u8",
        )
        val otherProvider = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.TS,
            "https://two.example/live/42.ts",
        )

        assertNotEquals(ts, hls)
        assertNotEquals(ts, otherProvider)
        assertEquals(true, ts.startsWith("p2|"))
    }
}
