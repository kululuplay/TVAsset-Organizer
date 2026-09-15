package com.iptv.player.player

import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class LivePlaybackDeadlineTest {
    private class Clock {
        var now = 0L
        data class Task(val at: Long, val action: () -> Unit)
        val queue = PriorityQueue<Task>(compareBy { it.at })
        fun schedule(delay: Long, action: () -> Unit) { queue.add(Task(now + delay, action)) }
        fun advance(to: Long) {
            while (queue.peek()?.at?.let { it <= to } == true) {
                val task = queue.remove()
                now = task.at
                task.action()
            }
            now = to
        }
    }

    @Test fun `no submission callback still times out from user request`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        val deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, failures::add)
        deadline.beginAttempt()
        clock.advance(44_999)
        assertTrue(failures.isEmpty())
        clock.advance(45_000)
        assertEquals(listOf(false), failures)
        clock.advance(80_000)
        assertEquals(1, failures.size)
    }

    @Test fun `silent retries and decoder fallbacks share one absolute episode`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        lateinit var deadline: LivePlaybackDeadline
        deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, { terminal ->
            failures += terminal
            if (!terminal) deadline.beginAttempt() else deadline.reset()
        })
        deadline.beginAttempt()
        clock.advance(180_000)
        assertEquals(listOf(false, true), failures)
    }

    @Test fun `buffering ready and first frame without sustained progress cannot forgive start`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        val deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, failures::add)
        val progress = LivePlaybackProgressPolicy()
        deadline.beginAttempt()
        progress.start(1_000, 0)
        for (t in 3_000L..42_000L step 3_000L) {
            clock.advance(t)
            progress.onBuffering()
            assertNotEquals(LivePlaybackProgressPolicy.Decision.STABLE, progress.sample(t, t, true))
        }
        clock.advance(45_000)
        assertEquals(listOf(false), failures)
    }

    @Test fun `server starting for twenty seconds then actual output can become stable`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        val deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, failures::add)
        val progress = LivePlaybackProgressPolicy()
        deadline.beginAttempt()
        clock.advance(23_000)
        progress.start(clock.now, 0)
        for (t in 26_000L..38_000L step 3_000L) {
            clock.advance(t)
            if (progress.sample(t, t - 23_000, false) == LivePlaybackProgressPolicy.Decision.STABLE) {
                deadline.reset()
            }
        }
        clock.advance(100_000)
        assertTrue(failures.isEmpty())
    }

    @Test fun `preview fullscreen handoff cannot postpone recovery forever`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        val deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, failures::add)
        deadline.beginAttempt()
        for (t in listOf(20_000L, 40_000L, 60_000L, 80_000L)) {
            clock.advance(t)
            deadline.beginAttempt()
        }
        clock.advance(90_000)
        assertEquals(listOf(true), failures)
    }

    @Test fun `fast channel changes invalidate every older queued deadline`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        val deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, failures::add)
        repeat(100) {
            deadline.reset()
            deadline.beginAttempt()
            clock.advance(clock.now + 100)
        }
        clock.advance(54_899)
        assertTrue(failures.isEmpty())
        clock.advance(54_900)
        assertEquals(listOf(false), failures)
    }

    @Test fun `suspension is not a fault and resume gets a new foreground budget`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        val deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, failures::add)
        deadline.beginAttempt()
        clock.advance(20_000)
        deadline.reset()
        clock.advance(3_600_000)
        assertTrue(failures.isEmpty())
        deadline.beginAttempt()
        clock.advance(3_645_000)
        assertEquals(listOf(false), failures)
    }

    @Test fun `backoff after an error cannot outlive the episode`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        val deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, failures::add)
        deadline.beginAttempt()
        clock.advance(30_000)
        deadline.waitingForRetry()
        clock.advance(89_000)
        deadline.waitingForRetry()
        clock.advance(90_000)
        assertEquals(listOf(true), failures)
    }

    @Test fun `terminal denial or release cancels pending retry timers`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        val deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, failures::add)
        deadline.beginAttempt()
        deadline.waitingForRetry()
        deadline.reset()
        clock.advance(1_000_000)
        assertTrue(failures.isEmpty())
    }

    @Test fun `separate outage after stable output gets its own finite budget`() {
        val clock = Clock()
        val failures = mutableListOf<Boolean>()
        val deadline = LivePlaybackDeadline({ clock.now }, clock::schedule, failures::add)
        deadline.beginAttempt()
        clock.advance(15_000)
        deadline.reset()
        clock.advance(300_000)
        deadline.waitingForRetry()
        clock.advance(302_000)
        deadline.beginAttempt()
        clock.advance(347_000)
        assertEquals(listOf(false), failures)
    }
}
