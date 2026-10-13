package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.iptv.player.data.model.BufferMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual Media3 load-control adapter used by the live engine. */
@OptIn(markerClass = [UnstableApi::class])
class LiveLoadControlTest {
    private val timeline = streamingLoadControlTimeline(live = true)
    private val periodId = MediaPeriodId(timeline.getUidOfPeriod(0))

    @Test
    fun `same player grows restart reserve after distinct rebuffer events`() {
        val control = control(BufferMode.ADAPTIVE)
        // NORMAL from the first second (1.6.1): 1.5 s to start, 2.5 s after a rebuffer.
        assertFalse(control.shouldStartPlayback(parameters(500)))
        assertTrue(control.shouldStartPlayback(parameters(1_500)))
        assertFalse(control.shouldStartPlayback(parameters(1_000, rebufferAt = 10)))
        repeat(20) { assertTrue(control.shouldStartPlayback(parameters(2_500, rebufferAt = 10))) }
        assertTrue(control.shouldStartPlayback(parameters(2_500, rebufferAt = 20)))
        // The third distinct rebuffer proves a weak link: HIGH's 5 s reserve.
        assertFalse(control.shouldStartPlayback(parameters(2_500, rebufferAt = 30)))
        assertTrue(control.shouldStartPlayback(parameters(5_000, rebufferAt = 30)))
    }

    @Test
    fun `legacy adaptive reserves normal from startup and high only after repeated rebuffers`() {
        val control = control(BufferMode.ADAPTIVE, constrained = true)
        assertFalse(control.shouldStartPlayback(parameters(500)))
        assertTrue(control.shouldStartPlayback(parameters(1_500)))
        // First two rebuffers keep the NORMAL 2.5 s restart reserve.
        repeat(2) { index ->
            assertTrue(control.shouldStartPlayback(parameters(2_500, rebufferAt = index.toLong())))
        }
        // The third proves a weak link: HIGH's 5 s reserve is now required.
        assertFalse(control.shouldStartPlayback(parameters(2_500, rebufferAt = 2)))
        assertTrue(control.shouldStartPlayback(parameters(5_000, rebufferAt = 2)))
    }

    @Test
    fun `adaptive fills beyond the former four second limit without reconnect`() {
        val control = control(BufferMode.ADAPTIVE)
        assertTrue(control.shouldContinueLoading(parameters(500)))
        assertTrue(control.shouldContinueLoading(parameters(5_000)))
        assertFalse(control.shouldContinueLoading(parameters(20_000)))
    }

    @Test
    fun `explicit low stays low across repeated rebuffers`() {
        val control = control(BufferMode.LOW)
        // LOW (1.6.1): 1 s to start, 2 s after a rebuffer, 2 s / 8 s loading window.
        assertFalse(control.shouldStartPlayback(parameters(500)))
        assertTrue(control.shouldStartPlayback(parameters(1_000)))
        repeat(10) { index ->
            assertFalse(control.shouldStartPlayback(parameters(1_000, rebufferAt = index.toLong())))
            assertTrue(control.shouldStartPlayback(parameters(2_000, rebufferAt = index.toLong())))
        }
        assertTrue(control.shouldContinueLoading(parameters(1_000)))
        assertFalse(control.shouldContinueLoading(parameters(8_000)))
    }

    @Test
    fun `new channel on reused engine starts with fresh adaptive history`() {
        val control = control(BufferMode.ADAPTIVE, initialRebuffers = 6)
        // Six rebuffers: HIGH's 3 s start reserve.
        assertFalse(control.shouldStartPlayback(parameters(1_500)))
        control.onStopped(PlayerId.UNSET)
        control.onPrepared(PlayerId.UNSET)
        // Fresh history: NORMAL's 1.5 s start reserve.
        assertFalse(control.shouldStartPlayback(parameters(500)))
        assertTrue(control.shouldStartPlayback(parameters(1_500)))
    }

    @Test
    fun `short live window caps restart wait instead of buffering forever`() {
        val control = control(BufferMode.ADAPTIVE, initialRebuffers = 6)
        assertTrue(control.shouldStartPlayback(parameters(1_500, rebufferAt = 1, liveOffsetMs = 3_000)))
        assertFalse(control.shouldStartPlayback(parameters(1_000, rebufferAt = 1, liveOffsetMs = 3_000)))
    }

    @Test
    fun `hitting sample memory target stops loading and allows playback at high bitrate`() {
        val control = control(BufferMode.ADAPTIVE, constrained = true)
        val allocator = control.getAllocator(PlayerId.UNSET)
        val allocations = List(32 * 1_048_576 / C.DEFAULT_BUFFER_SEGMENT_SIZE) { allocator.allocate() }
        assertFalse(control.shouldContinueLoading(parameters(800)))
        assertTrue(control.shouldStartPlayback(parameters(800, rebufferAt = 1)))
        allocations.forEach(allocator::release)
        control.onReleased(PlayerId.UNSET)
    }

    private fun control(
        mode: BufferMode,
        constrained: Boolean = false,
        initialRebuffers: Int = 0,
    ) = LiveLoadControl(mode, constrained, initialRebuffers).apply { onPrepared(PlayerId.UNSET) }

    private fun parameters(
        bufferedMs: Long,
        rebufferAt: Long = C.TIME_UNSET,
        liveOffsetMs: Long = C.TIME_UNSET,
    ) = LoadControl.Parameters(
        PlayerId.UNSET, timeline, periodId,
        0L, bufferedMs * 1_000L, 1f, true,
        rebufferAt != C.TIME_UNSET,
        if (liveOffsetMs == C.TIME_UNSET) C.TIME_UNSET else liveOffsetMs * 1_000L,
        rebufferAt,
    )
}
