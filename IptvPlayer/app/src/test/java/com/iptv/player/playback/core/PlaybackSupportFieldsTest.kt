package com.iptv.player.playback.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackSupportFieldsTest {
    @Test fun `new spool fields and nested objects cannot leak into support contract`() {
        val source = mapOf("schema" to 1, "state" to "PLAYING", "future_qoe_field" to "private",
            "request_url" to "https://user:password@example.test", "video_width" to mapOf("nested" to "unsafe"))
        assertEquals(mapOf("schema" to 1, "state" to "PLAYING"), PlaybackSupportFields.project(source))
        assertTrue(source.containsKey("future_qoe_field"))
    }
    @Test fun `orphaned session is ended with unknown measurements and unchanged abandoned reason`() {
        val record = PlaybackQoeRecorder.abandoned(PlaybackSession(PlaybackSessionId.random(), PlaybackContentKind.LIVE_TV,
            1_000, PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS), 10_000)
        val fields = PlaybackSupportFields.from(record)
        assertEquals("ABANDONED", fields["end_reason"])
        assertEquals("ENDED", fields["state"])
        assertEquals(0L, fields["session_duration_ms"])
        assertEquals(false, fields["frames_known"])
        assertFalse(fields.containsKey("rendered_frames"))
        assertFalse(fields.containsKey("time_to_first_frame_ms"))
    }
}
