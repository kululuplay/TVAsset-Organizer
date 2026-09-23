package com.iptv.player.playback.android

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.iptv.player.playback.core.PlaybackStartupTiming
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

@OptIn(markerClass = [UnstableApi::class])
class PlaybackSupportEvidenceTest {
    private var now = 0L
    private val evidence = PlaybackSupportEvidence { now }
    private val source = mock(DataSource::class.java)
    private val spec = mock(DataSpec::class.java)
    private fun listener(): TransferListener {
        val wrapped = evidence.wrap(DataSource.Factory { source }).createDataSource()
        assertSame(source, wrapped) // Socket-close ownership and the actual factory remain untouched.
        val capture = ArgumentCaptor.forClass(TransferListener::class.java)
        verify(source).addTransferListener(capture.capture())
        verify(source, never()).open(any(DataSpec::class.java))
        return capture.value
    }
    @Test fun `same source carries observed startup with no additional request or byte consumption`() {
        evidence.startup.reset()
        val listener = listener()
        now = 100
        listener.onTransferInitializing(source, spec, true)
        now = 300
        listener.onTransferStart(source, spec, true)
        now = 500
        listener.onBytesTransferred(source, spec, true, 32)
        now = 1_000
        evidence.startup.firstFrame(1_000)
        assertEquals(PlaybackStartupTiming(200, 500, 500), evidence.startup.snapshot())
        verifyNoMoreInteractions(source)
    }
    @Test fun `source belonging to retired generation cannot populate next playback timing`() {
        evidence.startup.reset()
        val listener = listener()
        listener.onTransferInitializing(source, spec, true)
        now = 500
        evidence.startup.reset()
        listener.onTransferStart(source, spec, true)
        listener.onBytesTransferred(source, spec, true, 32)
        assertNull(evidence.startup.snapshot())
    }
    @Test fun `range or retry reopen before first frame is unmeasured rather than misattributed`() {
        evidence.startup.reset()
        val listener = listener()
        listener.onTransferInitializing(source, spec, true)
        now = 100
        listener.onTransferStart(source, spec, true)
        listener.onBytesTransferred(source, spec, true, 1)
        assertNotNull(evidence.startup.snapshot())
        now = 300
        listener.onTransferInitializing(source, spec, true)
        listener.onTransferStart(source, spec, true)
        listener.onBytesTransferred(source, spec, true, 1)
        evidence.startup.firstFrame(300)
        assertNull(evidence.startup.snapshot())
    }
}
