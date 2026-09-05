package com.iptv.player.player

import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.ByteBuffer

class MonitoredAudioSinkTest {
    private val delegate = mock(AudioSink::class.java)
    private val monitor = AudioUnderrunMonitor()
    private var timeMs = 1_000L
    private val sink = MonitoredAudioSink(delegate, monitor) { timeMs }

    @Test
    fun `sink position is returned unchanged and actual advancement ends the episode`() {
        `when`(delegate.getCurrentPositionUs(false)).thenReturn(1_000_000L, 1_300_000L)
        assertEquals(1_000_000L, sink.getCurrentPositionUs(false))
        monitor.onUnderrun(timeMs)
        timeMs += 500L
        assertEquals(1_300_000L, sink.getCurrentPositionUs(false))
        assertTrue(monitor.poll(timeMs).recovered)
    }

    @Test
    fun `unset position is preserved but never mistaken for a frozen audio clock`() {
        `when`(delegate.getCurrentPositionUs(true)).thenReturn(AudioSink.CURRENT_POSITION_NOT_SET)
        monitor.onPosition(1_000_000L, timeMs)
        monitor.onUnderrun(timeMs)
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(true))
        assertFalse(monitor.poll(timeMs).pending)
        verify(delegate).getCurrentPositionUs(true)
    }

    @Test
    fun `PCM buffers and timestamps pass through unchanged`() {
        val buffer = ByteBuffer.allocate(32)
        `when`(delegate.handleBuffer(buffer, 345_000L, 2)).thenReturn(true)
        assertTrue(sink.handleBuffer(buffer, 345_000L, 2))
        verify(delegate).handleBuffer(buffer, 345_000L, 2)
    }

    @Test
    fun `every lifecycle boundary resets evidence and reaches the underlying sink`() {
        val boundaries: List<() -> Unit> = listOf(
            { sink.pause() }, { sink.flush() }, { sink.handleDiscontinuity() },
            { sink.reset() }, { sink.release() },
        )
        boundaries.forEach { boundary ->
            monitor.onPosition(1_000_000L, timeMs)
            monitor.onUnderrun(timeMs)
            boundary()
            assertFalse(monitor.poll(timeMs).pending)
        }
        verify(delegate).pause()
        verify(delegate).flush()
        verify(delegate).handleDiscontinuity()
        verify(delegate).reset()
        verify(delegate).release()
    }
}
