package com.iptv.player.playback.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProcessRecoveryPolicyTest {

    @Test
    fun `first recovery is allowed`() {
        assertTrue(PlaybackProcessRecoveryPolicy.mayRecover(10_000L, 0L))
    }

    @Test
    fun `rapid second recovery is blocked`() {
        assertFalse(
            PlaybackProcessRecoveryPolicy.mayRecover(
                nowMs = 10_000L + PlaybackProcessRecoveryPolicy.MIN_RECOVERY_INTERVAL_MS - 1L,
                lastRecoveryMs = 10_000L,
            ),
        )
    }

    @Test
    fun `recovery is allowed after the safety interval`() {
        assertTrue(
            PlaybackProcessRecoveryPolicy.mayRecover(
                nowMs = 10_000L + PlaybackProcessRecoveryPolicy.MIN_RECOVERY_INTERVAL_MS,
                lastRecoveryMs = 10_000L,
            ),
        )
    }

    @Test
    fun `clock rollback does not create a permanent lockout`() {
        assertTrue(PlaybackProcessRecoveryPolicy.mayRecover(1_000L, 10_000L))
    }
}
