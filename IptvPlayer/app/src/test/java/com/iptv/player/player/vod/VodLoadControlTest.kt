package com.iptv.player.player.vod

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises Media3's actual loader, not a second implementation of its policy. */
@OptIn(markerClass = [UnstableApi::class])
class VodLoadControlTest {
    @Test
    fun `legacy budget stops loading and permits high bitrate startup and rebuffer`() {
        val control = control(constrained = true)
        withAllocatedMiB(control, 24) {
            assertFalse(control.shouldContinueLoading(parameters(800)))
            assertTrue(control.shouldStartPlayback(parameters(800)))
            assertTrue(control.shouldStartPlayback(parameters(800, rebuffering = true)))
        }
    }

    @Test
    fun `legacy loader resumes filling after samples have been consumed`() {
        val control = control(constrained = true)
        withAllocatedMiB(control, 24) {
            assertFalse(control.shouldContinueLoading(parameters(800)))
        }
        assertTrue(control.shouldContinueLoading(parameters(800)))
        assertFalse(control.shouldStartPlayback(parameters(800, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(parameters(2_500, rebuffering = true)))
    }

    @Test
    fun `capable devices keep normal track derived buffering`() {
        val control = control(constrained = false)
        withAllocatedMiB(control, 24) {
            assertTrue(control.shouldContinueLoading(parameters(800)))
            assertFalse(control.shouldStartPlayback(parameters(800)))
            assertTrue(control.shouldStartPlayback(parameters(1_200)))
        }
    }

    @Test
    fun `seek waits for fresh samples and preserves configured startup threshold`() {
        val control = control(constrained = true)
        assertFalse(control.shouldStartPlayback(parameters(0)))
        assertFalse(control.shouldStartPlayback(parameters(1_199)))
        assertTrue(control.shouldStartPlayback(parameters(1_200)))
        assertEquals(0L, control.getBackBufferDurationUs(PlayerId.UNSET))
    }

    @Test
    fun `slow bitrate source keeps useful reserve without exceeding duration target`() {
        val control = control(constrained = true)
        assertTrue(control.shouldContinueLoading(parameters(500)))
        assertTrue(control.shouldContinueLoading(parameters(20_000)))
        assertFalse(control.shouldContinueLoading(parameters(50_000)))
    }

    private fun control(constrained: Boolean): DefaultLoadControl {
        val video = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        val audio = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        return VodLoadControl.create(VodBufferConfig(), constrained).apply {
            onPrepared(PlayerId.UNSET)
            onTracksSelected(parameters(0), TrackGroupArray(video, audio), arrayOf(
                FixedTrackSelection(video, 0), FixedTrackSelection(audio, 0),
            ))
        }
    }

    private fun withAllocatedMiB(control: DefaultLoadControl, mib: Int, block: () -> Unit) {
        val allocator = control.getAllocator(PlayerId.UNSET)
        val allocations = List(mib * 1_048_576 / C.DEFAULT_BUFFER_SEGMENT_SIZE) {
            allocator.allocate()
        }
        try {
            block()
        } finally {
            allocations.forEach(allocator::release)
        }
    }

    private fun parameters(bufferedMs: Long, rebuffering: Boolean = false) = LoadControl.Parameters(
        PlayerId.UNSET, Timeline.EMPTY, MediaPeriodId("vod"),
        0L, bufferedMs * 1_000L, 1f, true, rebuffering, C.TIME_UNSET, C.TIME_UNSET,
    )
}
