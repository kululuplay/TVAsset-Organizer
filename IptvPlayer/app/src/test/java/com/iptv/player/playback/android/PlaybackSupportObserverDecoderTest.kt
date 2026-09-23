package com.iptv.player.playback.android

import com.iptv.player.playback.core.PlaybackVideoDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Media3 1.11 order for one codec: onVideoDecoderInitialized, then onVideoInputFormatChanged(format, null). */
class PlaybackSupportObserverDecoderTest {
    private val kinds = mapOf(
        "c2.qti.avc.decoder" to PlaybackVideoDecoder.HARDWARE,
        "c2.android.avc.decoder" to PlaybackVideoDecoder.SOFTWARE,
    )
    private fun lookup(name: String) = kinds[name] ?: PlaybackVideoDecoder.UNKNOWN

    @Test fun `format change without a reuse evaluation keeps the initialized decoder`() {
        var decoder = PlaybackVideoDecoder.UNKNOWN
        decoder = nextObservedDecoder(decoder, "c2.qti.avc.decoder", ::lookup)
        assertEquals(PlaybackVideoDecoder.HARDWARE, decoder)
        decoder = nextObservedDecoder(decoder, null, ::lookup)
        assertEquals(PlaybackVideoDecoder.HARDWARE, decoder)
    }

    @Test fun `only an evaluation naming a decoder changes the observed kind`() {
        var decoder = nextObservedDecoder(PlaybackVideoDecoder.HARDWARE, "c2.android.avc.decoder", ::lookup)
        assertEquals(PlaybackVideoDecoder.SOFTWARE, decoder)
        decoder = nextObservedDecoder(decoder, "omx.vendor.unlisted", ::lookup)
        assertEquals(PlaybackVideoDecoder.UNKNOWN, decoder)
        assertEquals(PlaybackVideoDecoder.UNKNOWN, nextObservedDecoder(PlaybackVideoDecoder.UNKNOWN, null, ::lookup))
    }
}
