package com.iptv.player.ui.home

import com.iptv.player.data.model.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveEpgOverlayPolicyTest {

    @Test
    fun `resolves current next progress and rounded-up remaining minutes`() {
        val current = program("Current", start = 0L, stop = 10 * MINUTE)
        val next = program("Next", start = 10 * MINUTE, stop = 20 * MINUTE)

        val state = LiveEpgOverlayPolicy.resolve(
            programs = listOf(next, current),
            nowMs = 4 * MINUTE + 30_000L,
        )

        assertEquals(current, state.current)
        assertEquals(next, state.next)
        assertEquals(45, state.progressPercent)
        assertEquals(6, state.remainingMinutes)
    }

    @Test
    fun `rollover promotes the next programme to current`() {
        val first = program("First", start = 0L, stop = 10 * MINUTE)
        val second = program("Second", start = 10 * MINUTE, stop = 20 * MINUTE)
        val third = program("Third", start = 20 * MINUTE, stop = 30 * MINUTE)

        val state = LiveEpgOverlayPolicy.resolve(
            programs = listOf(first, second, third),
            nowMs = 10 * MINUTE,
        )

        assertEquals(second, state.current)
        assertEquals(third, state.next)
        assertEquals(0, state.progressPercent)
        assertEquals(10, state.remainingMinutes)
    }

    @Test
    fun `future guide without a current programme still exposes next`() {
        val future = program("Future", start = 5 * MINUTE, stop = 15 * MINUTE)

        val state = LiveEpgOverlayPolicy.resolve(listOf(future), nowMs = 0L)

        assertNull(state.current)
        assertEquals(future, state.next)
        assertEquals(0, state.progressPercent)
        assertNull(state.remainingMinutes)
    }

    @Test
    fun `invalid and expired entries do not leak into overlay`() {
        val invalid = program("Invalid", start = 10L, stop = 10L)
        val expired = program("Expired", start = 0L, stop = 100L)

        val state = LiveEpgOverlayPolicy.resolve(
            programs = listOf(invalid, expired),
            nowMs = 1_000L,
        )

        assertNull(state.current)
        assertNull(state.next)
        assertEquals(0, state.progressPercent)
        assertNull(state.remainingMinutes)
    }

    private fun program(title: String, start: Long, stop: Long) = Program(
        epgChannelId = "epg",
        title = title,
        description = null,
        startMs = start,
        stopMs = stop,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
