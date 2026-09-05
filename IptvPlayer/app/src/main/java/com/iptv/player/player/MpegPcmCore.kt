package com.iptv.player.player

import androidx.media3.extractor.MpegAudioUtil
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.InputStream

/** Consumes demuxed MPEG audio frames only; never opens a URL or audio device. */
@androidx.media3.common.util.UnstableApi
internal class MpegPcmCore(private val sampleRate: Int, private val channels: Int) {
    data class Pcm(val samples: ShortArray, val layer: Int)

    private var packets = PacketInputStream()
    private var bitstream: Bitstream? = null
    private var decoder = Decoder()
    private var streamMime: String? = null

    fun reset() {
        bitstream?.close()
        bitstream = null
        packets = PacketInputStream()
        decoder = Decoder()
        streamMime = null
    }

    fun decode(data: ByteArray): Pcm {
        require(data.isNotEmpty() && data.size <= MAX_INPUT_BYTES) { "Invalid MPEG sample size" }
        // Validate *all* boundaries before feeding the persistent bitstream. JLayer
        // otherwise scans past malformed input and can hide truncation as silence.
        val frames = ArrayList<Pair<Int, Int>>()
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
            frames += offset to header.frameSize
            totalSamples += header.samplesPerFrame * channels
            offset += header.frameSize
        }
        val pcm = ShortArray(totalSamples)
        var written = 0
        var layer = 0
        for ((start, size) in frames) {
            packets.setPacket(data, start, size)
            // Layer decoders retain their original Bitstream/Header references.
            // Recreating Bitstream per packet corrupts subsequent frames, so both
            // it and the synthesis filters/reservoir live until flush/seek/release.
            val stream = bitstream ?: Bitstream(packets).also { bitstream = it }
            val frameHeader = requireNotNull(stream.readFrame()) { "Missing MPEG frame" }
            try {
                val output = decoder.decodeFrame(frameHeader, stream) as SampleBuffer
                require(output.sampleFrequency == sampleRate && output.channelCount == channels)
                require(output.bufferLength <= pcm.size - written) { "Unexpected MPEG PCM length" }
                output.buffer.copyInto(pcm, written, 0, output.bufferLength)
                written += output.bufferLength
                layer = frameHeader.layer()
            } finally {
                stream.closeFrame()
            }
        }
        // MP3 reservoir warmup may produce no samples after a mid-stream seek.
        return Pcm(if (written == pcm.size) pcm else pcm.copyOf(written), layer)
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
    }
}
