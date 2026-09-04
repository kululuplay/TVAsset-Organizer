package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
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
    @Test
    fun `same player grows restart reserve after distinct rebuffer events`() {
        val control = control(BufferMode.ADAPTIVE)
        assertTrue(control.shouldStartPlayback(parameters(500)))
        assertFalse(control.shouldStartPlayback(parameters(1_000, rebufferAt = 10)))
        repeat(20) { assertTrue(control.shouldStartPlayback(parameters(2_500, rebufferAt = 10))) }
        assertTrue(control.shouldStartPlayback(parameters(2_500, rebufferAt = 20)))
        assertFalse(control.shouldStartPlayback(parameters(2_500, rebufferAt = 30)))
        assertTrue(control.shouldStartPlayback(parameters(5_000, rebufferAt = 30)))
    }

    @Test
    fun `legacy adaptive reserves enough from startup but never requires high buffer`() {
        val control = control(BufferMode.ADAPTIVE, constrained = true)
        assertFalse(control.shouldStartPlayback(parameters(500)))
        assertTrue(control.shouldStartPlayback(parameters(1_500)))
        repeat(10) { index ->
            assertTrue(control.shouldStartPlayback(parameters(2_500, rebufferAt = index.toLong())))
        }
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
        repeat(10) { index ->
            assertTrue(control.shouldStartPlayback(parameters(1_000, rebufferAt = index.toLong())))
        }
        assertFalse(control.shouldContinueLoading(parameters(4_000)))
    }

    @Test
    fun `new channel on reused engine starts with fresh adaptive history`() {
        val control = control(BufferMode.ADAPTIVE, initialRebuffers = 6)
        assertFalse(control.shouldStartPlayback(parameters(500)))
        control.onStopped(PlayerId.UNSET)
        control.onPrepared(PlayerId.UNSET)
        assertTrue(control.shouldStartPlayback(parameters(500)))
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
        val allocator = control.allocator
        val allocations = List(24 * 1_048_576 / C.DEFAULT_BUFFER_SEGMENT_SIZE) { allocator.allocate() }
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
        PlayerId.UNSET, Timeline.EMPTY, MediaPeriodId("live"),
        0L, bufferedMs * 1_000L, 1f, true,
        rebufferAt != C.TIME_UNSET,
        if (liveOffsetMs == C.TIME_UNSET) C.TIME_UNSET else liveOffsetMs * 1_000L,
        rebufferAt,
    )
}
