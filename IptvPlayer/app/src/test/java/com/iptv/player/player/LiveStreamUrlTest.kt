package com.iptv.player.player

import com.iptv.player.data.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveStreamUrlTest {

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
            "https://example.test/live/42.ts?token=a.ts&mode=1#edge",
            LiveStreamUrl.applyFormat(
                "https://example.test/live/42.ts?token=a.ts&mode=1#edge",
                StreamFormat.TS,
            ),
        )
    }

    @Test
    fun `extension matching is case insensitive while path casing is preserved`() {
        assertEquals(
            "https://EXAMPLE.test/Live/Channel.ts?Token=ABC",
            LiveStreamUrl.applyFormat(
                "https://EXAMPLE.test/Live/Channel.TS?Token=ABC",
                StreamFormat.TS,
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
            assertEquals(url, LiveStreamUrl.applyFormat(url, StreamFormat.TS))
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
            "https://example.test/live/42.ts?token=abc",
            LiveStreamUrl.applyFormat(
                "https://example.test/live/42.M3U8?token=abc",
                StreamFormat.TS,
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
    fun `route key separates providers and uses the new policy generation`() {
        val ts = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.TS,
            "https://one.example/live/42.ts",
        )
        val hls = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.TS,
            "https://one.example/live/42.m3u8",
        )
        val otherProvider = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.TS,
            "https://two.example/live/42.ts",
        )

        assertEquals(ts, hls)
        assertNotEquals(ts, otherProvider)
        assertEquals(true, ts.startsWith("p4|"))
    }

    @Test
    fun `transport memory uses the TS-only policy generation`() {
        val tsPreference = LiveStreamUrl.transportKey(
            "channel-42",
            "https://one.example/live/42.ts",
            StreamFormat.TS,
        )
        val hlsPreference = LiveStreamUrl.transportKey(
            "channel-42",
            "https://one.example/live/42.ts",
            StreamFormat.TS,
        )

        assertEquals(tsPreference, hlsPreference)
        assertEquals(true, tsPreference.startsWith("t3|"))
    }

    @Test
    fun `endpoint fingerprint normalizes scheme host and effective default port`() {
        val implicit = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.TS,
            "HTTPS://Example.Test/live/42.ts",
        )
        val explicit = LiveStreamUrl.routeKey(
            "channel-42",
            StreamFormat.TS,
            "https://example.test:443/live/42.ts",
        )

        assertEquals(implicit, explicit)
    }

    @Test
    fun `endpoint fingerprint separates schemes and non-default ports`() {
        val https = LiveStreamUrl.transportKey(
            "channel-42",
            "https://example.test/live/42.ts",
            StreamFormat.TS,
        )
        val http = LiveStreamUrl.transportKey(
            "channel-42",
            "http://example.test/live/42.ts",
            StreamFormat.TS,
        )
        val alternatePort = LiveStreamUrl.transportKey(
            "channel-42",
            "https://example.test:8443/live/42.ts",
            StreamFormat.TS,
        )

        assertNotEquals(https, http)
        assertNotEquals(https, alternatePort)
    }
}
