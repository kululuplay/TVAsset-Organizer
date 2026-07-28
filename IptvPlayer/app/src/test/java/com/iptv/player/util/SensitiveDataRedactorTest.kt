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
    fun `safe diagnostic text is unchanged and redaction is idempotent`() {
        val input = "Controller: fallback EXO --AUDIO--> VLC_HW"
        val once = SensitiveDataRedactor.redact(input)

        assertEquals(input, once)
        assertEquals(once, SensitiveDataRedactor.redact(once))
    }
}
