package com.iptv.player.player

import com.iptv.player.R
import com.iptv.player.playback.core.FailureSignal
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackFailureClassifier
import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class LiveFailureRecoveryTest {
    private fun http(status: Int) = PlaybackFailureClassifier.classify(FailureSignal.Http(status))
    private fun offline() = PlaybackFailureClassifier.classify(FailureSignal.Network(FailureSignal.NetworkKind.UNAVAILABLE))

    @Test fun `rate limiting waits instead of hammering the server every second`() {
        assertEquals(30_000L, LiveSourceRecoveryPolicy.retryDelayMs(http(429), 1_000))
        assertEquals(1_000L, LiveSourceRecoveryPolicy.retryDelayMs(http(503), 1_000))
    }

    @Test fun `denials missing streams and ordinary client errors stop without decoder trials`() {
        listOf(400, 401, 403, 404, 410, 415).forEach {
            assertEquals(LiveSourceRecoveryPolicy.Action.STOP, LiveSourceRecoveryPolicy.action(http(it)))
        }
    }

    @Test fun `repeated server rate limit and network errors stay on the same player`() {
        listOf(http(503), http(500), http(408), http(429), offline()).forEach { failure ->
            repeat(10) {
                assertEquals(LiveSourceRecoveryPolicy.Action.RECONNECT_SOURCE, LiveSourceRecoveryPolicy.action(failure))
            }
        }
    }

    @Test fun `decoder and container faults retain player recovery instead of network retry`() {
        val decode = PlaybackFailureClassifier.classify(FailureSignal.Decoder(FailureSignal.DecoderKind.RUNTIME, PlaybackFailure.Component.VIDEO))
        assertEquals(LiveSourceRecoveryPolicy.Action.RECOVER_PLAYER, LiveSourceRecoveryPolicy.action(decode))
        assertFalse(LiveSourceRecoveryPolicy.canRecoverOnNetworkReturn(decode))
    }

    @Test fun `deadline and unknown fallback preserve the last concrete server cause`() {
        val state = LiveFailureRecovery().apply { beginRequest(); record(http(503)) }
        state.record(PlaybackFailureClassifier.classify(FailureSignal.Unknown))
        state.record(PlaybackFailureClassifier.classify(FailureSignal.Timeout(FailureSignal.TimeoutKind.STARTUP)))
        assertEquals(503, state.failure?.httpStatus)
        state.record(http(403))
        assertEquals(403, state.failure?.httpStatus)
    }

    @Test fun `network return retries a terminated network failure only once per real edge`() {
        val state = LiveFailureRecovery().apply { beginRequest(); record(offline()); suspend(); terminal() }
        assertFalse(state.networkChanged(true, true)) // first online snapshot is not a reconnect
        assertFalse(state.networkChanged(false, true))
        assertTrue(state.networkChanged(true, true))
        assertFalse(state.networkChanged(true, true))
    }

    @Test fun `initial offline state is remembered before a playback request exists`() {
        val state = LiveFailureRecovery()
        assertFalse(state.networkChanged(false, true))
        state.beginRequest()
        state.record(offline())
        assertTrue(state.networkChanged(true, true))
    }

    @Test fun `denials and server failures never auto retry just because wifi returns`() {
        listOf(401, 403, 404, 429, 503).forEach { code ->
            val state = LiveFailureRecovery().apply { beginRequest(); record(http(code)); suspend(); terminal() }
            state.networkChanged(false, true)
            assertFalse(state.networkChanged(true, true))
        }
    }

    @Test fun `pause background cast handoff and release disable network reopening`() {
        val state = LiveFailureRecovery().apply { beginRequest(); record(offline()); networkChanged(false, true); suspend() }
        assertFalse(state.networkChanged(true, true))
    }

    @Test fun `healthy playback is not restarted on a wifi handoff`() {
        val state = LiveFailureRecovery().apply { beginRequest(); record(offline()); networkChanged(false, false) }
        assertFalse(state.networkChanged(true, false))
        state.stable()
        assertNull(state.failure)
        state.networkChanged(false, true)
        assertFalse(state.networkChanged(true, true))
    }

    @Test fun `manual retry and channel change discard old denial messages`() {
        val state = LiveFailureRecovery().apply { beginRequest(); record(http(403)) }
        state.beginRequest()
        assertNull(state.failure)
    }

    @Test fun `repeated silent source recovery still reaches absolute terminal deadline`() {
        var now = 0L
        data class Task(val at: Long, val action: () -> Unit)
        val tasks = PriorityQueue<Task>(compareBy { it.at })
        val terminal = mutableListOf<Long>()
        val state = LiveFailureRecovery().apply { beginRequest(); record(http(503)) }
        val deadline = LivePlaybackDeadline({ now }, { delay, action -> tasks.add(Task(now + delay, action)) },
            { if (it) terminal.add(now) })
        deadline.beginAttempt()
        for (time in 5_000L..85_000L step 5_000L) {
            now = time
            assertEquals(LiveSourceRecoveryPolicy.Action.RECONNECT_SOURCE, LiveSourceRecoveryPolicy.action(state.failure!!))
            deadline.waitingForRetry()
            deadline.beginAttempt()
        }
        while (tasks.isNotEmpty()) {
            val task = tasks.remove()
            now = maxOf(now, task.at)
            task.action()
        }
        assertEquals(listOf(90_000L), terminal)
        assertEquals(503, state.failure?.httpStatus)
    }

    @Test fun `visible errors distinguish access server congestion missing source and player`() {
        assertEquals(R.string.live_error_access, LivePlaybackMessages.resource(http(403)))
        assertEquals(R.string.live_error_server, LivePlaybackMessages.resource(http(503)))
        assertEquals(R.string.live_error_busy, LivePlaybackMessages.resource(http(429)))
        assertEquals(R.string.live_error_missing, LivePlaybackMessages.resource(http(404)))
        assertEquals(R.string.error_no_internet, LivePlaybackMessages.resource(offline()))
        assertEquals(R.string.error_live_playback_failed, LivePlaybackMessages.resource(null))
    }
}
