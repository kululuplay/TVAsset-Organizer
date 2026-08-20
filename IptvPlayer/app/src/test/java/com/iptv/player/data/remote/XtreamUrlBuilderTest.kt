package com.iptv.player.data.remote

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamUrlBuilderTest {
    @Test
    fun `playback URL preserves scheme host port and panel prefix`() {
        assertEquals(
            "https://panel.example:8443/root/movie/user/pass/42.mkv",
            XtreamUrlBuilder.movieUrl(
                "https://panel.example:8443/root",
                "user",
                "pass",
                "42",
                "mkv",
            ),
        )
    }

    @Test
    fun `credentials and ids are encoded as path segments`() {
        assertEquals(
            "https://panel.example/movie/a%2Fb/password/12%2F34.mp4",
            XtreamUrlBuilder.movieUrl(
                "https://panel.example",
                "a/b",
                "password",
                "12/34",
                "mp4",
            ),
        )
    }

    @Test
    fun `query parameters are encoded without changing explicit port`() {
        val expected = "http://panel.example:8080/panel/xmltv.php?username=" +
            "a%26b" + "&password=" + "password"
        assertEquals(
            expected,
            XtreamUrlBuilder.xmltvUrl(
                "http://panel.example:8080/panel",
                "a&b",
                "password",
            ),
        )
    }

    @Test
    fun `absolute direct source wins unchanged`() {
        assertEquals(
            "https://cdn.example:9443/video/file.mp4?token=a%20b",
            XtreamUrlBuilder.movieUrl(
                "https://panel.example:8443/root",
                "u",
                "p",
                "1",
                "mp4",
                "https://cdn.example:9443/video/file.mp4?token=a%20b",
            ),
        )
    }

    @Test
    fun `relative direct source resolves against panel path`() {
        assertEquals(
            "https://panel.example:8443/root/cdn/file.mp4",
            XtreamUrlBuilder.resolveDirectSource(
                "https://panel.example:8443/root",
                "cdn/file.mp4",
            ),
        )
    }

    @Test
    fun `custom scheme direct source is rejected`() {
        assertNull(XtreamUrlBuilder.resolveDirectSource("https://panel.example", "file:///tmp/a.mp4"))
    }

    @Test
    fun `HTTPS panel direct source cannot downgrade credentials to HTTP`() {
        assertNull(
            XtreamUrlBuilder.resolveDirectSource(
                "https://panel.example",
                "http://cdn.example/video.mp4",
            ),
        )
    }

    @Test
    fun `public panel cannot redirect playback into a private literal`() {
        assertNull(
            XtreamUrlBuilder.resolveDirectSource(
                "https://panel.example",
                "https://192.168.1.10/video.mp4",
            ),
        )
    }

    @Test
    fun `same-host private panel remains supported`() {
        assertEquals(
            "http://192.168.1.10/video.mp4",
            XtreamUrlBuilder.resolveDirectSource(
                "http://192.168.1.10",
                "/video.mp4",
            ),
        )
    }

    @Test
    fun `direct source fields deserialize from Xtream payloads`() {
        val vod = Gson().fromJson(
            """{"stream_id":"7","direct_source":"https://cdn.example/v.mp4"}""",
            XtreamVodStream::class.java,
        )
        assertEquals("https://cdn.example/v.mp4", vod.directSource)
    }
}
