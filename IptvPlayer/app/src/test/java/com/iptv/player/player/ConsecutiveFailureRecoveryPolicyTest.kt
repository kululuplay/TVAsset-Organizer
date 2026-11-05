package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsecutiveFailureRecoveryPolicyTest {

    @Test
    fun `three consecutive non-authoritative terminal failures trigger process recovery`() {
        val policy = ConsecutiveFailureRecoveryPolicy(maxConsecutiveFailures = 3)
        assertFalse(policy.recordTerminalFailure(httpStatus = null))
        assertEquals(1, policy.count)
        assertFalse(policy.recordTerminalFailure(httpStatus = 502))
        assertEquals(2, policy.count)
        assertTrue(policy.recordTerminalFailure(httpStatus = null))
        assertEquals(0, policy.count)
    }

    @Test
    fun `stable playback resets consecutive terminal failure count`() {
        val policy = ConsecutiveFailureRecoveryPolicy(maxConsecutiveFailures = 3)
        assertFalse(policy.recordTerminalFailure(httpStatus = null))
        assertFalse(policy.recordTerminalFailure(httpStatus = null))
        assertEquals(2, policy.count)

        policy.onStablePlayback()
        assertEquals(0, policy.count)
        assertFalse(policy.recordTerminalFailure(httpStatus = null))
        assertEquals(1, policy.count)
    }

    @Test
    fun `authoritative HTTP rejections and HLS rejections never trigger process recovery`() {
        val policy = ConsecutiveFailureRecoveryPolicy(maxConsecutiveFailures = 3)
        listOf(401, 403, 404, 458).forEach { code ->
            repeat(5) {
                assertFalse(policy.recordTerminalFailure(httpStatus = code))
            }
        }
        repeat(5) {
            assertFalse(policy.recordTerminalFailure(httpStatus = null, hlsRejected = true))
        }
        assertEquals(0, policy.count)
    }

    @Test
    fun `quality-only stutter verdicts keep playing when no alternative stage exists`() {
        assertTrue(
            ConsecutiveFailureRecoveryPolicy.shouldKeepPlayingOnStutter(
                VideoVerdict.Check.FRAME_DROP_RATIO,
                hasAlternativeVideoStage = false,
            ),
        )
        assertTrue(
            ConsecutiveFailureRecoveryPolicy.shouldKeepPlayingOnStutter(
                VideoVerdict.Check.DECODER_HICCUPS,
                hasAlternativeVideoStage = false,
            ),
        )
        // When an untried stage exists, stutter verdicts are allowed to switch.
        assertFalse(
            ConsecutiveFailureRecoveryPolicy.shouldKeepPlayingOnStutter(
                VideoVerdict.Check.FRAME_DROP_RATIO,
                hasAlternativeVideoStage = true,
            ),
        )
        // Dead-picture verdicts still reconnect even when no other stage exists.
        listOf(
            VideoVerdict.Check.NO_FIRST_FRAME,
            VideoVerdict.Check.FRAME_STALL,
            VideoVerdict.Check.SOLID_GREEN,
            VideoVerdict.Check.PERSISTENT_BLANK,
            VideoVerdict.Check.PREPARE_STALL,
        ).forEach { check ->
            assertFalse(
                ConsecutiveFailureRecoveryPolicy.shouldKeepPlayingOnStutter(
                    check,
                    hasAlternativeVideoStage = false,
                ),
            )
        }
    }
}
