package com.iptv.player.playback.core

import com.iptv.player.player.AfrMode
import com.iptv.player.player.DisplayModeInfo
import com.iptv.player.playback.core.FrameRateMatchSession.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateMatchSessionTest {

    private val modes = listOf(
        DisplayModeInfo(1, 1920, 1080, 60f),
        DisplayModeInfo(2, 1920, 1080, 50f),
        DisplayModeInfo(3, 1920, 1080, 24f),
    )

    @Test
    fun `first request for a family switches once and repeats are ignored`() {
        val session = FrameRateMatchSession()
        assertEquals(Decision.Switch(2), request(session, now = 0L, fps = 25f, current = 1))
        assertTrue(session.switched)
        // The zap re-check and the late-fps re-polls see the same family.
        assertEquals(Decision.Ignore, request(session, now = 100L, fps = 25f, current = 2))
        assertEquals(Decision.Ignore, request(session, now = 5_000L, fps = 25f, current = 2))
        // 50 fps is a different family, but 50 Hz already fits.
        assertEquals(Decision.Keep, request(session, now = 5_000L, fps = 50f, current = 2))
    }

    @Test
    fun `a family change within the debounce window is deferred not dropped`() {
        val session = FrameRateMatchSession(minSwitchIntervalMs = 2_000L)
        assertEquals(Decision.Switch(2), request(session, now = 0L, fps = 25f, current = 1))
        assertEquals(Decision.Defer(1_500L), request(session, now = 500L, fps = 24f, current = 2))
        // The deferred retry is evaluated afresh once the window has elapsed.
        assertEquals(Decision.Switch(3), request(session, now = 2_000L, fps = 24f, current = 2))
    }

    @Test
    fun `off and unknown fps are ignored without touching state`() {
        val session = FrameRateMatchSession()
        assertEquals(Decision.Ignore, request(session, now = 0L, fps = 25f, current = 1, afr = AfrMode.OFF))
        assertEquals(Decision.Ignore, request(session, now = 0L, fps = 0f, current = 1))
        assertEquals(Decision.Ignore, request(session, now = 0L, fps = -1f, current = 1))
        assertFalse(session.switched)
        // A real fps afterwards still switches (unknown did not claim the family).
        assertEquals(Decision.Switch(2), request(session, now = 0L, fps = 25f, current = 1))
    }

    @Test
    fun `keep is remembered so the same family is not re-evaluated`() {
        val session = FrameRateMatchSession()
        assertEquals(Decision.Keep, request(session, now = 0L, fps = 30f, current = 1))
        assertEquals(Decision.Ignore, request(session, now = 0L, fps = 29.97f, current = 1))
        assertFalse(session.switched)
    }

    @Test
    fun `restore reports whether a switch was applied and clears everything`() {
        val session = FrameRateMatchSession()
        assertFalse(session.restore())
        assertEquals(Decision.Switch(2), request(session, now = 0L, fps = 25f, current = 1))
        assertTrue(session.restore())
        assertFalse(session.switched)
        assertFalse(session.restore())
        // Fresh session semantics: same family switches again, no debounce carry-over.
        assertEquals(Decision.Switch(2), request(session, now = 10L, fps = 25f, current = 1))
    }

    @Test
    fun `invalidate re-evaluates the handled family without undoing the switch`() {
        val session = FrameRateMatchSession()
        assertEquals(Decision.Switch(2), request(session, now = 0L, fps = 25f, current = 1))
        session.invalidate()
        assertTrue(session.switched)
        assertEquals(Decision.Keep, request(session, now = 3_000L, fps = 25f, current = 2))
    }

    @Test
    fun `changing the afr mode is a new family key`() {
        val session = FrameRateMatchSession()
        assertEquals(Decision.Keep, request(session, now = 0L, fps = 60f, current = 1))
        // Same fps, different mode: evaluated again rather than ignored.
        assertEquals(
            Decision.Keep,
            request(session, now = 0L, fps = 60f, current = 1, afr = AfrMode.REFRESH_AND_RESOLUTION),
        )
    }

    private fun request(
        session: FrameRateMatchSession,
        now: Long,
        fps: Float,
        current: Int,
        afr: AfrMode = AfrMode.REFRESH_ONLY,
    ) = session.request(
        nowMs = now,
        afrMode = afr,
        fps = fps,
        width = 1920,
        height = 1080,
        modes = modes,
        currentModeId = current,
    )
}
