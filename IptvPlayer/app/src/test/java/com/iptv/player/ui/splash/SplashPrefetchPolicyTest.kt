package com.iptv.player.ui.splash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashPrefetchPolicyTest {

    @Test
    fun `fresh EPG cache avoids repeated cold-start work`() {
        val nowMs = 20_000_000L

        assertFalse(
            shouldRefreshEpg(
                lastUpdatedAtMs = nowMs - (2L * 60L * 60L * 1_000L),
                nowMs = nowMs,
            ),
        )
    }

    @Test
    fun `missing stale or future EPG timestamp refreshes`() {
        val nowMs = 20_000_000L

        assertTrue(shouldRefreshEpg(lastUpdatedAtMs = 0L, nowMs = nowMs))
        assertTrue(
            shouldRefreshEpg(
                lastUpdatedAtMs = nowMs - (4L * 60L * 60L * 1_000L),
                nowMs = nowMs,
            ),
        )
        assertTrue(shouldRefreshEpg(lastUpdatedAtMs = nowMs + 1L, nowMs = nowMs))
    }
}
