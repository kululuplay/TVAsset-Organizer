package com.iptv.player.playback.android

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

/** Exercises the actual listener attached to each HTTP data source. */
@OptIn(markerClass = [UnstableApi::class])
class MeasuredPlaybackBufferTest {
    private var now = 1_000L
    private val buffer = MeasuredPlaybackBuffer({ now }, { false })
    private val source = mock(DataSource::class.java)
    private val spec = mock(DataSpec::class.java)

    private fun start(): TransferListener {
        buffer.wrap(DataSource.Factory { source }).createDataSource()
        val capture = ArgumentCaptor.forClass(TransferListener::class.java)
        verify(source).addTransferListener(capture.capture())
        return capture.value.apply { onTransferStart(source, spec, true) }
    }

    private fun TransferListener.bytes() = onBytesTransferred(source, spec, true, 1_024)

    @Test fun `actual active transfer gap is observed but normal loader idle is excluded`() {
        val listener = start()
        buffer.state(500, true)
        listener.bytes()
        now += 2_000
        listener.bytes()
        assertEquals(2_000L, buffer.snapshot().networkGapMs)
        buffer.reset()
        listener.onTransferStart(source, spec, true)
        buffer.state(8_000, true)
        listener.bytes()
        now += 4_000
        buffer.state(500, true)
        listener.bytes()
        assertEquals(0L, buffer.snapshot().networkGapMs)
    }

    @Test fun `pause or seek on a reused connection cannot be measured as a network gap`() {
        val listener = start()
        buffer.state(500, true)
        listener.bytes()
        buffer.discardSeekEvidence()
        now += 4_000
        buffer.state(500, true)
        listener.bytes()
        assertEquals(0L, buffer.snapshot().networkGapMs)
        now += 1_000
        listener.bytes()
        assertEquals(1_000L, buffer.snapshot().networkGapMs)
    }

    @Test fun `retired source callbacks do not contaminate the next channel`() {
        val listener = start()
        buffer.state(500, true)
        listener.bytes()
        buffer.reset()
        now += 3_000
        buffer.state(500, true)
        listener.bytes()
        assertEquals(0L, buffer.snapshot().networkGapMs)
    }
}
