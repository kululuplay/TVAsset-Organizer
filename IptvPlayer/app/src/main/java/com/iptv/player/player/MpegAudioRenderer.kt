package com.iptv.player.player

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer

/** Last-choice audio renderer: working platform decoders always remain first. */
@androidx.media3.common.util.UnstableApi
internal class MpegAudioRenderer(
    handler: Handler,
    listener: AudioRendererEventListener,
    sink: AudioSink,
) : DecoderAudioRenderer<MpegAudioDecoder>(handler, listener, sink) {
    override fun getName() = "KululuMpegAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        if (!supportsMime(format.sampleMimeType)) return C.FORMAT_UNSUPPORTED_TYPE
        if (format.cryptoType != C.CRYPTO_TYPE_NONE || format.drmInitData != null) {
            return C.FORMAT_UNSUPPORTED_DRM
        }
        if (format.channelCount !in 1..2 || format.sampleRate !in SAMPLE_RATES) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        return if (sinkSupportsFormat(pcmFormat(format.sampleRate, format.channelCount))) {
            C.FORMAT_HANDLED
        } else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): MpegAudioDecoder {
        check(cryptoConfig == null)
        return MpegAudioDecoder(format.sampleRate, format.channelCount)
    }

    override fun getOutputFormat(decoder: MpegAudioDecoder): Format =
        pcmFormat(decoder.sampleRate, decoder.channels)

    companion object {
        internal fun supportsMime(mime: String?): Boolean =
            mime == MimeTypes.AUDIO_MPEG || mime == MimeTypes.AUDIO_MPEG_L1 || mime == MimeTypes.AUDIO_MPEG_L2

        // MPEG-1 only. Do not claim the low-rate MPEG-2/2.5 profiles supported:
        // the upstream Layer-II parser has a known frame-size limitation there.
        private val SAMPLE_RATES = setOf(32000, 44100, 48000)
        private fun pcmFormat(sampleRate: Int, channels: Int): Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(sampleRate)
            .setChannelCount(channels)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
    }
}
