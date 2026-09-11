package com.iptv.player.player

import androidx.media3.extractor.MpegAudioUtil
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.InputStream
import java.util.ArrayDeque

/** Consumes demuxed MPEG audio frames only; never opens a URL or audio device. */
@androidx.media3.common.util.UnstableApi
internal class MpegPcmCore(private val sampleRate: Int, private val channels: Int) {
    data class Pcm(val samples: ShortArray, val layer: Int, val concealedFrames: Int = 0)

    private data class Frame(val start: Int, val size: Int, val samplesPerChannel: Int)
    private data class Concealment(val position: Long, val samples: Int)

    private var packets = PacketInputStream()
    private var bitstream: Bitstream? = null
    private var decoder = Decoder()
    private var streamMime: String? = null
    private var samplePosition = 0L
    private var hasDecodedAudio = false
    private var consecutiveConcealments = 0
    private var concealedSamples = 0
    private val concealments = ArrayDeque<Concealment>()

    fun reset() {
        resetDecoderState()
        streamMime = null
        samplePosition = 0L
        hasDecodedAudio = false
        consecutiveConcealments = 0
        concealedSamples = 0
        concealments.clear()
    }

    private fun resetDecoderState() {
        bitstream?.close()
        bitstream = null
        packets = PacketInputStream()
        decoder = Decoder()
    }

    fun decode(data: ByteArray): Pcm {
        require(data.isNotEmpty() && data.size <= MAX_INPUT_BYTES) { "Invalid MPEG sample size" }
        // Validate *all* boundaries before feeding the persistent bitstream. JLayer
        // otherwise scans past malformed input and can hide truncation as silence.
        val frames = ArrayList<Frame>()
        var offset = 0
        var totalSamples = 0
        val header = MpegAudioUtil.Header()
        while (offset < data.size) {
            require(data.size - offset >= 4 && frames.size < MAX_FRAMES) { "Truncated MPEG sample" }
            val bits = ((data[offset].toInt() and 255) shl 24) or
                ((data[offset + 1].toInt() and 255) shl 16) or
                ((data[offset + 2].toInt() and 255) shl 8) or (data[offset + 3].toInt() and 255)
            require(header.setForHeaderData(bits) && header.frameSize <= data.size - offset) {
                "Invalid MPEG frame"
            }
            // The unmodified JLayer release mis-sizes MPEG-2 Layer II frames.
            // Advertise/decode only validated MPEG-1 rates, not untested profiles.
            require(header.version == 3) { "Unsupported MPEG version" }
            require(header.sampleRate == sampleRate && header.channels == channels) {
                "MPEG output format changed"
            }
            require(streamMime == null || streamMime == header.mimeType) { "MPEG layer changed" }
            streamMime = header.mimeType
            frames += Frame(offset, header.frameSize, header.samplesPerFrame)
            totalSamples += header.samplesPerFrame * channels
            offset += header.frameSize
        }
        val pcm = ShortArray(totalSamples)
        var written = 0
        var layer = 0
        var concealedFrames = 0
        for ((start, size, samplesPerChannel) in frames) {
            packets.setPacket(data, start, size)
            // Layer decoders retain their original Bitstream/Header references.
            // Recreating Bitstream per packet corrupts subsequent frames, so both
            // it and the synthesis filters/reservoir live until flush/seek/release.
            val stream = bitstream ?: Bitstream(packets).also { bitstream = it }
            val frameHeader = requireNotNull(stream.readFrame()) { "Missing MPEG frame" }
            var recovered = false
            try {
                val output = decoder.decodeFrame(frameHeader, stream) as SampleBuffer
                require(output.sampleFrequency == sampleRate && output.channelCount == channels)
                require(output.bufferLength <= pcm.size - written) { "Unexpected MPEG PCM length" }
                output.buffer.copyInto(pcm, written, 0, output.bufferLength)
                written += output.bufferLength
                if (output.bufferLength > 0) hasDecodedAudio = true
                consecutiveConcealments = 0
            } catch (error: ArrayIndexOutOfBoundsException) {
                // A damaged Layer-II grouped sample can index beyond JLayer's
                // quantization table even when the frame header/size are valid.
                // Recover only after proven audio, and only a short, bounded gap.
                // Header/format errors, other layers and sustained damage still
                // fail through the existing player recovery path.
                if (frameHeader.layer() != 2 || !reserveConcealment(samplesPerChannel)) throw error
                val count = samplesPerChannel * channels
                pcm.fill(0, written, written + count)
                written += count
                concealedFrames++
                recovered = true
            } finally {
                stream.closeFrame()
            }
            layer = frameHeader.layer()
            if (recovered) resetDecoderState()
            samplePosition += samplesPerChannel
        }
        // MP3 reservoir warmup may produce no samples after a mid-stream seek.
        return Pcm(if (written == pcm.size) pcm else pcm.copyOf(written), layer, concealedFrames)
    }

    private fun reserveConcealment(samples: Int): Boolean {
        if (!hasDecodedAudio || consecutiveConcealments >= MAX_CONSECUTIVE_CONCEALMENTS) return false
        val oldest = samplePosition - sampleRate * CONCEALMENT_WINDOW_SECONDS.toLong()
        while (concealments.peekFirst()?.let { it.position <= oldest } == true) {
            concealedSamples -= concealments.removeFirst().samples
        }
        if (concealedSamples + samples > sampleRate * MAX_CONCEALED_MS / 1000) return false
        concealments.addLast(Concealment(samplePosition, samples))
        concealedSamples += samples
        consecutiveConcealments++
        return true
    }

    /** Bounded packet source; EOF means this demuxed frame has been consumed. */
    private class PacketInputStream : InputStream() {
        private var bytes = ByteArray(0)
        private var position = 0
        private var end = 0
        fun setPacket(data: ByteArray, start: Int, length: Int) {
            check(position == end) { "Unconsumed MPEG input" }
            bytes = data
            position = start
            end = start + length
        }
        override fun read(): Int = if (position < end) bytes[position++].toInt() and 255 else -1
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == end) return -1
            val count = minOf(length, end - position)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
    }

    companion object {
        const val MAX_FRAMES = 32
        const val MAX_INPUT_BYTES = MAX_FRAMES * 4096
        private const val MAX_CONSECUTIVE_CONCEALMENTS = 2
        private const val MAX_CONCEALED_MS = 100
        private const val CONCEALMENT_WINDOW_SECONDS = 5
    }
}
