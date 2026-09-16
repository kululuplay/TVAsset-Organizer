package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRedactorTest {

    @Test
    fun `redacts Xtream credentials in path`() {
        val output = SensitiveDataRedactor.redact(
            "GET http://tv.example/live/alice/s3cr3t/42.ts",
        )

        assertFalse(output.contains("alice"))
        assertFalse(output.contains("s3cr3t"))
        assertTrue(output.contains("/live/<redacted>/<redacted>/42.ts"))
    }

    @Test
    fun `redacts sensitive query values without removing safe fields`() {
        val output = SensitiveDataRedactor.redact(
            "http://tv.example/player_api.php?username=alice&password=s3cr3t&action=get_live_streams",
        )

        assertFalse(output.contains("alice"))
        assertFalse(output.contains("s3cr3t"))
        assertTrue(output.contains("action=get_live_streams"))
    }

    @Test
    fun `redacts headers and json payload fields`() {
        val output = SensitiveDataRedactor.redact(
            "Authorization: Bearer abc\nX-Kululu-Key: ingest\n" +
                """{"username":"alice","password":"s3cr3t","event":"play"}""",
        )

        assertFalse(output.contains("Bearer abc"))
        assertFalse(output.contains("ingest"))
        assertFalse(output.contains("alice"))
        assertFalse(output.contains("s3cr3t"))
        assertTrue(output.contains("\"event\":\"play\""))
    }

    @Test
    fun `redacts timeshift and hls path forms`() {
        val timeshift = SensitiveDataRedactor.redact(
            "http://tv.example/timeshift/alice/s3cr3t/120/2026-09-01:20-00/42.ts",
        )
        assertFalse(timeshift.contains("alice"))
        assertTrue(timeshift.contains("/timeshift/<redacted>/<redacted>/120/"))

        val hls = SensitiveDataRedactor.redact("https://tv.example:8443/hls/alice/s3cr3t/42.m3u8")
        assertFalse(hls.contains("s3cr3t"))
        assertTrue(hls.contains("/hls/<redacted>/<redacted>/42.m3u8"))
    }

    @Test
    fun `redacts bare host user pass forms`() {
        for (url in listOf(
            "http://tv.example/alice/s3cr3t/42.ts",
            "http://tv.example:8080/alice/s3cr3t/42",
            "https://tv.example/alice/s3cr3t/42/",
            "http://10.0.0.5/alice/s3cr3t/1.m3u8?token=abc",
        )) {
            val output = SensitiveDataRedactor.redact("GET $url")
            assertFalse(url, output.contains("alice"))
            assertFalse(url, output.contains("s3cr3t"))
            assertTrue(url, output.contains("/<redacted>/<redacted>/"))
        }
        assertTrue(
            SensitiveDataRedactor.redact("http://tv.example/alice/s3cr3t/42.ts")
                .endsWith("/42.ts"),
        )
    }

    @Test
    fun `ordinary urls with two segments are untouched`() {
        for (url in listOf(
            "https://api.github.com/repos/kululuplay/TVAsset-Organizer/releases?per_page=10",
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            "https://raw.githubusercontent.com/kululuplay/TVAsset-Organizer/main/update-rollout.json",
            "http://tv.example/player_api.php?action=get_live_streams",
        )) {
            assertEquals(url, url, SensitiveDataRedactor.redact(url))
        }
    }

    @Test
    fun `redacts percent-encoded query credentials`() {
        val output = SensitiveDataRedactor.redact(
            "redirect=http%3A%2F%2Ftv.example%2Fplayer_api.php%3Fusername%3Dalice%26password%3Ds3cr3t%26action%3Dget_live_streams",
        )

        assertFalse(output.contains("alice"))
        assertFalse(output.contains("s3cr3t"))
        assertTrue(output.contains("action%3Dget_live_streams"))
    }

    @Test
    fun `redacts headers that do not start the line`() {
        val output = SensitiveDataRedactor.redact(
            "12:00:01 I/Http: request headers={Authorization: Bearer abc, Accept: */*}\n" +
                "Cookie: session=xyz; theme=dark",
        )

        assertFalse(output.contains("Bearer abc"))
        assertFalse(output.contains("session=xyz"))
        assertTrue(output.contains("Accept: */*"))
        assertTrue(output.contains("Authorization: <redacted>"))
    }

    @Test
    fun `safe diagnostic text is unchanged and redaction is idempotent`() {
        val input = "Controller: fallback EXO --AUDIO--> VLC_HW"
        val once = SensitiveDataRedactor.redact(input)

        assertEquals(input, once)
        assertEquals(once, SensitiveDataRedactor.redact(once))
    }
}
