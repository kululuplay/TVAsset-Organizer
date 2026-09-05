package com.iptv.player.player

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

class MpegAudioRendererTest {
    @Test fun `MPEG1 layer I II and III mono stereo can be selected as PCM audio`() {
        val renderer = renderer()
        for (mime in listOf(MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2)) {
            for (rate in listOf(32000, 44100, 48000)) for (channels in 1..2) {
                assertEquals(C.FORMAT_HANDLED, support(renderer, format(mime, rate, channels)))
            }
        }
    }

    @Test fun `AAC AC3 EAC3 DTS and video are never claimed by the MPEG fallback`() {
        for (mime in listOf("audio/mp4a-latm", "audio/ac3", "audio/eac3", "audio/vnd.dts", "video/avc")) {
            assertEquals(C.FORMAT_UNSUPPORTED_TYPE, support(renderer(), format(mime)))
        }
    }

    @Test fun `DRM low rate unknown and multichannel formats retain existing recovery`() {
        val renderer = renderer()
        assertEquals(C.FORMAT_UNSUPPORTED_DRM, support(renderer,
            format().buildUpon().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build()))
        for (invalid in listOf(format(rate = 24000), format(rate = Format.NO_VALUE),
            format(channels = 6), format(channels = Format.NO_VALUE))) {
            assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(renderer, invalid))
        }
    }

    @Test fun `sink must accept decoded PCM before MPEG support is advertised`() {
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(renderer(false), format()))
    }

    private fun renderer(pcmSupported: Boolean = true): MpegAudioRenderer {
        val sink = mock(AudioSink::class.java)
        `when`(sink.supportsFormat(any(Format::class.java))).thenReturn(pcmSupported)
        return MpegAudioRenderer(mock(Handler::class.java), mock(AudioRendererEventListener::class.java), sink)
    }
    private fun support(renderer: MpegAudioRenderer, format: Format) =
        RendererCapabilities.getFormatSupport(renderer.supportsFormat(format))

    private fun format(mime: String = MimeTypes.AUDIO_MPEG_L2, rate: Int = 48000, channels: Int = 2) =
        Format.Builder().setSampleMimeType(mime).setSampleRate(rate).setChannelCount(channels).build()
}
