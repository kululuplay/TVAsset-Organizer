package com.iptv.player.player

import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteOrder

@androidx.media3.common.util.UnstableApi
internal class MpegAudioDecoder(val sampleRate: Int, val channels: Int) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, MpegAudioException>(
        emptyBuffers<DecoderInputBuffer>(8), emptyBuffers<SimpleDecoderOutputBuffer>(8),
    ) {
    private val core = MpegPcmCore(sampleRate, channels)

    init { setInitialInputBufferSize(4096) }

    override fun getName() = NAME
    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    override fun createOutputBuffer() = SimpleDecoderOutputBuffer(::releaseOutputBuffer)
    override fun createUnexpectedDecodeException(error: Throwable) =
        MpegAudioException("MPEG audio decode failed", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): MpegAudioException? = try {
        if (reset) core.reset()
        val input = requireNotNull(inputBuffer.data)
        require(input.remaining() <= MpegPcmCore.MAX_INPUT_BYTES)
        val bytes = ByteArray(input.remaining())
        input.get(bytes)
        val pcm = core.decode(bytes)
        val output = outputBuffer.init(inputBuffer.timeUs, pcm.samples.size * 2).order(ByteOrder.nativeOrder())
        for (sample in pcm.samples) output.putShort(sample)
        output.flip()
        if (pcm.samples.isEmpty()) outputBuffer.shouldBeSkipped = true
        null
    } catch (error: Exception) {
        // Let the existing bounded player recovery handle genuinely broken input;
        // never hide persistent decode failure behind endless silent PCM.
        MpegAudioException("MPEG audio frame rejected", error)
    }

    override fun release() {
        super.release() // Join Media3's decoder thread before clearing its state.
        core.reset()
    }

    companion object {
        const val NAME = "kululu-software-mpeg-audio-pcm"
        // SimpleDecoder fills both arrays through createInput/OutputBuffer in its
        // constructor, but its Java nullability annotation describes them as full.
        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> emptyBuffers(count: Int): Array<T> =
            arrayOfNulls<T>(count) as Array<T>
    }
}

@androidx.media3.common.util.UnstableApi
internal class MpegAudioException(message: String, cause: Throwable) : DecoderException(message, cause)
