package com.iptv.player.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackAttemptTraceTest {
    @Test fun `request identifier accepts only internally generated opaque UUIDs`() {
        val attempt = PlaybackAttemptTrace(1_000)
        assertEquals(attempt.id, PlaybackAttemptTrace.safeId(attempt.id))
        listOf("https://server/user/password/123.ts", "token=secret", "hello\r\nX-Test:bad", "").forEach {
            assertNull(PlaybackAttemptTrace.safeId(it))
        }
        assertNotEquals(attempt.id, PlaybackAttemptTrace(1_000).id)
    }

    @Test fun `repeated callback storms emit each phase once with monotonic elapsed time`() {
        val attempt = PlaybackAttemptTrace(1_000)
        assertTrue(attempt.event(PlaybackAttemptTrace.Phase.SUBMITTED, 2_000)!!.contains("elapsedMs=1000"))
        repeat(100) { assertNull(attempt.event(PlaybackAttemptTrace.Phase.SUBMITTED, 3_000)) }
        assertTrue(attempt.event(PlaybackAttemptTrace.Phase.FAILURE, 5_000, 403)!!.endsWith("http=403"))
        assertFalse(attempt.event(PlaybackAttemptTrace.Phase.TERMINAL, 6_000, -1)!!.contains("http="))
    }
}
