package com.iptv.player.player

import androidx.media3.extractor.MpegAudioUtil
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class MpegPcmCoreTest {
    @Test fun `encoded MP2 stereo tone matches FFmpeg PCM and continuous decoding`() {
        verifyTone("stereo-48k.mp2", "stereo-48k.pcm", 48000, 2)
    }

    @Test fun `encoded MP2 mono tone with padded frames matches FFmpeg PCM`() {
        verifyTone("mono-44k.mp2", "mono-44k.pcm", 44100, 1)
    }

    @Test fun `MP3 bit reservoir survives packet boundaries`() {
        verifyTone("stereo-48k.mp3", "stereo-48k-mp3.pcm", 48000, 2)
    }

    @Test fun `MP2 stereo produces 1152 PCM samples per channel without native decoder`() {
        val pcm = MpegPcmCore(48000, 2).decode(frame(layer = 2))
        assertEquals(2, pcm.layer)
        assertEquals(2304, pcm.samples.size)
        assertTrue(pcm.samples.all { it == 0.toShort() })
    }

    @Test fun `all three layers accept mono and stereo frames`() {
        for (layer in 1..3) for (channels in 1..2) {
            val result = MpegPcmCore(48000, channels).decode(frame(layer, channels))
            assertEquals(layer, result.layer)
            assertEquals((if (layer == 1) 384 else 1152) * channels, result.samples.size)
        }
    }

    @Test fun `successive non silent packets exactly match continuous reference decoding`() {
        val frames = (0 until 30).map { frame(layer = 1, channels = 1, signal = it % 3 + 1) }
        val core = MpegPcmCore(48000, 1)
        val actual = frames.flatMap { core.decode(it).samples.toList() }.toShortArray()
        val expected = reference(frames.reduce(ByteArray::plus))
        assertTrue("Fixture must not be silent", expected.any { it != 0.toShort() })
        assertArrayEquals(expected, actual)
    }

    @Test fun `multiple frames in one sample retain synthesis state`() {
        val frames = (1..5).map { frame(1, 1, it % 3 + 1) }.reduce(ByteArray::plus)
        assertArrayEquals(reference(frames), MpegPcmCore(48000, 1).decode(frames).samples)
    }

    @Test fun `reset removes previous stream synthesis history`() {
        val core = MpegPcmCore(48000, 1)
        repeat(10) { core.decode(frame(1, 1, 1)) }
        core.reset()
        val silence = frame(1, 1)
        assertArrayEquals(MpegPcmCore(48000, 1).decode(silence).samples, core.decode(silence).samples)
    }

    @Test fun `truncated invalid free format and oversized samples fail boundedly`() {
        val valid = frame(2)
        for (bad in listOf(ByteArray(0), byteArrayOf(1, 2, 3), valid.copyOf(valid.size - 1),
            ByteArray(MpegPcmCore.MAX_INPUT_BYTES + 1), valid + byteArrayOf(1),
            valid.clone().apply { this[2] = 4 })) {
            assertThrows(IllegalArgumentException::class.java) { MpegPcmCore(48000, 2).decode(bad) }
        }
    }

    @Test fun `format changes and excessive frame counts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MpegPcmCore(44100, 2).decode(frame(2)) }
        assertThrows(IllegalArgumentException::class.java) { MpegPcmCore(48000, 1).decode(frame(2)) }
        val core = MpegPcmCore(48000, 2)
        core.decode(frame(2))
        assertThrows(IllegalArgumentException::class.java) { core.decode(frame(1)) }
        val tooMany = (0..MpegPcmCore.MAX_FRAMES).map { frame(2) }.reduce(ByteArray::plus)
        assertThrows(IllegalArgumentException::class.java) { MpegPcmCore(48000, 2).decode(tooMany) }
    }

    @Test fun `unvalidated MPEG2 layer II profile fails before upstream parser`() {
        val data = frame(2).clone().apply { this[1] = 0xf5.toByte() }
        // MPEG-2 uses a different bitrate table/frame size; regenerate its payload.
        val bits = 0xfff59400.toInt()
        val header = MpegAudioUtil.Header().also { assertTrue(it.setForHeaderData(bits)) }
        val sample = data.copyOf(header.frameSize)
        val core = MpegPcmCore(24000, 2)
        assertThrows(IllegalArgumentException::class.java) { core.decode(sample) }
    }

    private fun reference(data: ByteArray): ShortArray {
        val stream = Bitstream(ByteArrayInputStream(data))
        val decoder = Decoder()
        val samples = ArrayList<Short>()
        while (true) {
            val header = stream.readFrame() ?: break
            val pcm = decoder.decodeFrame(header, stream) as SampleBuffer
            samples.addAll(pcm.buffer.take(pcm.bufferLength))
            stream.closeFrame()
        }
        stream.close()
        return samples.toShortArray()
    }

    private fun verifyTone(encodedName: String, pcmName: String, rate: Int, channels: Int) {
        val encoded = resource(encodedName)
        val header = MpegAudioUtil.Header()
        val core = MpegPcmCore(rate, channels)
        val actualList = ArrayList<Short>()
        var offset = 0
        while (offset < encoded.size) {
            val bits = ByteBuffer.wrap(encoded, offset, 4).int
            assertTrue(header.setForHeaderData(bits))
            val packet = encoded.copyOfRange(offset, offset + header.frameSize)
            actualList.addAll(core.decode(packet).samples.toList())
            offset += header.frameSize
        }
        val actual = actualList.toShortArray()
        assertArrayEquals("Packet feeding must equal continuous JLayer decoding", reference(encoded), actual)
        val referenceBytes = ByteBuffer.wrap(resource(pcmName)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val expected = ShortArray(referenceBytes.remaining()).also(referenceBytes::get)
        assertEquals("Decoded duration/sample count", expected.size, actual.size)
        assertTrue("Independent reference must be audible", expected.any { it > 1000 })
        // Different synthesis implementations have slightly different rounding
        // and gain. Bound RMS error to 3% of the independent signal's RMS.
        val error = sqrt(actual.indices.sumOf { i -> (actual[i].toDouble() - expected[i]).let { it * it } } / actual.size)
        val signal = sqrt(expected.sumOf { it.toDouble() * it } / expected.size)
        assertTrue("PCM RMS error=$error reference RMS=$signal", error < signal * 0.03)
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/mpeg-audio/$name")).use { it.readBytes() }

    companion object {
        // Synthetic, self-generated MPEG-1 frames, 48kHz. Zero allocation gives
        // silence; Layer I can also carry a nonzero first subband for continuity.
        internal fun frame(layer: Int, channels: Int = 2, signal: Int = 0): ByteArray {
            val bits = 0xffe00000.toInt() or (3 shl 19) or ((4 - layer) shl 17) or
                (1 shl 16) or (9 shl 12) or (1 shl 10) or (if (channels == 1) 3 shl 6 else 0)
            val header = MpegAudioUtil.Header().also { check(it.setForHeaderData(bits)) }
            val data = ByteArray(header.frameSize)
            repeat(4) { data[it] = (bits ushr (24 - it * 8)).toByte() }
            if (signal != 0) {
                check(layer == 1 && channels == 1)
                data[4] = 0x10 // first subband: two-bit samples, all others unallocated
                // 32*4 allocation bits, then six-bit scale factor (zero), then samples.
                var position = 32 + 128 + 6
                repeat(12) {
                    for (shift in 1 downTo 0) {
                        val index = position / 8
                        data[index] = (data[index].toInt() or (((signal ushr shift) and 1) shl (7 - position % 8))).toByte()
                        position++
                    }
                }
            }
            return data
        }
    }
}
