package com.iptv.player.player

import com.iptv.player.player.LivePlaybackProgressPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaybackProgressPolicyTest {
    private val policy = LivePlaybackProgressPolicy()

    @Test fun `clock alone cannot cover lost surface or a nonadvancing audio sink`() {
        policy.start(0, 0)
        for (t in 3_000L..12_000L step 3_000L) {
            assertEquals(Decision.WAIT, policy.sample(t, t, buffering = false, outputHealthy = false))
        }
        assertEquals(Decision.STALLED, policy.sample(15_000, 15_000, false, false))
    }

    @Test fun `brief output interruption resumes without reopening the connection`() {
        policy.start(0, 0)
        assertEquals(Decision.WAIT, policy.sample(3_000, 3_000, false, true))
        assertEquals(Decision.WAIT, policy.sample(6_000, 6_000, true, false))
        assertEquals(Decision.WAIT, policy.sample(9_000, 9_000, false, true))
        for (t in 12_000L..18_000L step 3_000L) {
            assertEquals(Decision.WAIT, policy.sample(t, t, false, true))
        }
        assertEquals(Decision.STABLE, policy.sample(21_000, 21_000, false, true))
    }

    @Test
    fun `EOS near former stability deadline cannot mark ended attempt recovered`() {
        policy.start(nowMs = 0L, positionMs = 0L)
        for (timeMs in listOf(3_000L, 6_000L, 9_000L)) {
            assertEquals(Decision.WAIT, policy.sample(timeMs, timeMs, buffering = false))
        }
        // At 9.5s the controller retires the attempt before scheduling its retry.
        policy.reset()
        assertEquals(Decision.WAIT, policy.sample(10_000L, 9_500L, buffering = false))
        assertEquals(Decision.WAIT, policy.sample(30_000L, 9_500L, buffering = false))

        // A replacement must prove its own progress rather than inherit 9s.
        policy.start(nowMs = 10_500L, positionMs = 0L)
        for (elapsedMs in listOf(3_000L, 6_000L, 9_000L, 12_000L)) {
            assertEquals(
                Decision.WAIT,
                policy.sample(10_500L + elapsedMs, elapsedMs, buffering = false),
            )
        }
        assertEquals(Decision.STABLE, policy.sample(25_500L, 15_000L, buffering = false))
    }

    @Test
    fun `a reconnect with one frame then frozen clock stalls before being forgiven`() {
        policy.start(nowMs = 50_000L, positionMs = 500L)
        for (elapsedMs in listOf(3_000L, 6_000L, 9_000L, 12_000L)) {
            assertEquals(
                Decision.WAIT,
                policy.sample(50_000L + elapsedMs, 500L, buffering = false),
            )
        }
        assertEquals(Decision.STALLED, policy.sample(65_000L, 500L, buffering = false))
        assertEquals(Decision.WAIT, policy.sample(68_000L, 500L, buffering = false))
    }

    @Test
    fun `a brief rebuffer between polls restarts the uninterrupted stable window`() {
        policy.start(nowMs = 0L, positionMs = 0L)
        for (timeMs in listOf(3_000L, 6_000L, 9_000L)) {
            assertEquals(Decision.WAIT, policy.sample(timeMs, timeMs, buffering = false))
        }
        // BUFFERING and READY may both happen before the next watchdog poll.
        policy.onBuffering()
        for (timeMs in listOf(12_000L, 15_000L, 18_000L, 21_000L)) {
            assertEquals(Decision.WAIT, policy.sample(timeMs, timeMs, buffering = false))
        }
        assertEquals(Decision.STABLE, policy.sample(24_000L, 24_000L, buffering = false))
    }

    @Test
    fun `clock advancing behind permanent buffering cannot keep loading forever`() {
        policy.start(nowMs = 0L, positionMs = 0L)
        for (timeMs in 3_000L..12_000L step 3_000L) {
            assertEquals(Decision.WAIT, policy.sample(timeMs, timeMs, buffering = true))
        }
        assertEquals(Decision.STALLED, policy.sample(15_000, 15_000, buffering = true))
    }

    @Test
    fun `healthy forward playback recovers after live window moves backwards`() {
        policy.start(nowMs = 0L, positionMs = 60_000L)
        assertEquals(Decision.WAIT, policy.sample(3_000L, 63_000L, buffering = false))
        assertEquals(Decision.WAIT, policy.sample(6_000L, 3_000L, buffering = false))
        assertEquals(Decision.WAIT, policy.sample(9_000L, 6_000L, buffering = false))
        assertEquals(Decision.WAIT, policy.sample(12_000L, 9_000L, buffering = false))
        for (timeMs in listOf(15_000L, 18_000L, 21_000L)) {
            assertEquals(Decision.WAIT, policy.sample(timeMs, timeMs - 3_000L, buffering = false))
        }
        assertEquals(Decision.STABLE, policy.sample(24_000L, 21_000L, buffering = false))
    }

    @Test
    fun `bouncing timestamps cannot refresh the stall deadline indefinitely`() {
        policy.start(nowMs = 0L, positionMs = 0L)
        assertEquals(Decision.WAIT, policy.sample(3_000L, 3_000L, buffering = false))
        for (timeMs in listOf(6_000L, 9_000L, 12_000L, 15_000L)) {
            val positionMs = if (timeMs % 6_000L == 0L) 0L else 3_000L
            assertEquals(Decision.WAIT, policy.sample(timeMs, positionMs, buffering = false))
        }
        assertEquals(Decision.STALLED, policy.sample(18_000L, 0L, buffering = false))
    }

    @Test
    fun `unknown clock after first frame has a bounded failure deadline`() {
        policy.start(nowMs = 0L, positionMs = -1L)
        assertEquals(Decision.WAIT, policy.sample(12_000L, -1L, buffering = true))
        assertEquals(Decision.STALLED, policy.sample(15_000L, -1L, buffering = true))
    }

    @Test
    fun `long scheduler gap cannot turn two observations into stable playback`() {
        policy.start(nowMs = 0L, positionMs = 0L)
        assertEquals(Decision.WAIT, policy.sample(3_000L, 3_000L, buffering = false))
        assertEquals(Decision.WAIT, policy.sample(90_000L, 90_000L, buffering = false))
    }

    @Test
    fun `radio ready clock earns stability once without a video callback`() {
        // Radio enters the same policy from Playing instead of video output.
        policy.start(nowMs = 0L, positionMs = 0L)
        for (timeMs in listOf(3_000L, 6_000L, 9_000L, 12_000L)) {
            assertEquals(Decision.WAIT, policy.sample(timeMs, timeMs, buffering = false))
        }
        assertEquals(Decision.STABLE, policy.sample(15_000L, 15_000L, buffering = false))
        assertEquals(Decision.WAIT, policy.sample(18_000L, 18_000L, buffering = false))
    }
}
