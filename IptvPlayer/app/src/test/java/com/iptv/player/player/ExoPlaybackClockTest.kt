package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Uses the same pinned Media3 timeline type constructed by HlsMediaSource. */
@OptIn(markerClass = [UnstableApi::class])
class ExoPlaybackClockTest {
    private val player = mock(Player::class.java)
    private val window = Timeline.Window()

    @Test
    fun `repeated HLS window slides without program date time stay stable`() {
        val policy = LivePlaybackProgressPolicy()
        snapshot(positionMs = 27_000L, windowOffsetMs = 0L)
        policy.start(0L, clock())
        for (timeMs in 3_000L..30_000L step 3_000L) {
            // Six-second playlist refreshes give raw positions 30, 27, 30, 27...
            val offsetMs = timeMs / 6_000L * 6_000L
            snapshot(positionMs = 27_000L + timeMs - offsetMs, windowOffsetMs = offsetMs)
            assertEquals(27_000L + timeMs, clock())
            assertEquals(
                if (timeMs == 15_000L) LivePlaybackProgressPolicy.Decision.STABLE
                else LivePlaybackProgressPolicy.Decision.WAIT,
                policy.sample(timeMs, clock(), buffering = false),
            )
        }
    }

    @Test
    fun `playlist refresh while output is frozen cannot manufacture progress`() {
        val policy = LivePlaybackProgressPolicy()
        snapshot(positionMs = 27_000L, windowOffsetMs = 0L)
        policy.start(0L, clock())
        for (timeMs in 3_000L..15_000L step 3_000L) {
            val offsetMs = timeMs / 6_000L * 6_000L
            snapshot(positionMs = 27_000L - offsetMs, windowOffsetMs = offsetMs)
            assertEquals(27_000L, clock())
            assertEquals(
                if (timeMs == 15_000L) LivePlaybackProgressPolicy.Decision.STALLED
                else LivePlaybackProgressPolicy.Decision.WAIT,
                policy.sample(timeMs, clock(), buffering = true),
            )
        }
    }

    @Test
    fun `progressive TS position is unchanged by zero window offset`() {
        snapshot(positionMs = 12_500L, windowOffsetMs = 0L)
        assertEquals(12_500L, clock())
    }

    @Test
    fun `empty timeline uses the available player clock`() {
        `when`(player.currentTimeline).thenReturn(Timeline.EMPTY)
        `when`(player.currentPosition).thenReturn(4_000L)
        assertEquals(4_000L, clock())
    }

    @Test
    fun `missing player or clock remains unavailable`() {
        assertEquals(-1L, exoPlaybackClockPositionMs(null, window))
        `when`(player.currentPosition).thenReturn(C.TIME_UNSET)
        assertEquals(-1L, clock())
    }

    @Test
    fun `transient invalid media index cannot crash the watchdog`() {
        snapshot(positionMs = 12_000L, windowOffsetMs = 0L)
        `when`(player.currentMediaItemIndex).thenReturn(C.INDEX_UNSET)
        assertEquals(-1L, clock())
    }

    private fun clock() = exoPlaybackClockPositionMs(player, window)

    private fun snapshot(positionMs: Long, windowOffsetMs: Long) {
        val timeline = SinglePeriodTimeline(
            C.TIME_UNSET, // period duration
            36_000_000L, // window duration
            windowOffsetMs * 1_000L,
            27_000_000L, // default live position
            true,
            true,
            true,
            null,
            MediaItem.EMPTY,
        )
        `when`(player.currentTimeline).thenReturn(timeline)
        `when`(player.currentMediaItemIndex).thenReturn(0)
        `when`(player.currentPosition).thenReturn(positionMs)
    }
}
