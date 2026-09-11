package com.iptv.player.player

import androidx.media3.common.C
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MpegAudioDecoderTest {
    @Test fun `PCM output keeps exact media timestamp and mono sample size`() {
        withDecoder { decoder ->
            queue(decoder, MpegPcmCoreTest.frame(1, 1, 2), 7_123_456)
            val output = awaitOutput(decoder)
            assertEquals(7_123_456, output.timeUs)
            assertEquals(384 * 2, output.data!!.remaining())
            val expected = MpegPcmCore(48000, 1).decode(MpegPcmCoreTest.frame(1, 1, 2)).samples
            val samples = ShortArray(expected.size)
            output.data!!.order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)
            assertArrayEquals(expected, samples)
            output.release()
        }
    }

    @Test fun `flush discards queued old-channel output and resets synthesis`() {
        withDecoder { decoder ->
            queue(decoder, MpegPcmCoreTest.frame(1, 1, 2), 1000)
            queue(decoder, MpegPcmCoreTest.frame(1, 1, 3), 2000)
            decoder.flush()
            queue(decoder, MpegPcmCoreTest.frame(1, 1), 9_000_000)
            val output = awaitOutput(decoder)
            assertEquals(9_000_000, output.timeUs)
            while (output.data!!.hasRemaining()) assertEquals(0, output.data!!.short.toInt())
            output.release()
        }
    }

    @Test fun `end of stream is propagated without attempting MPEG decode`() {
        withDecoder { decoder ->
            val input = requireNotNull(decoder.dequeueInputBuffer())
            input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            decoder.queueInputBuffer(input)
            val output = awaitOutput(decoder)
            assertTrue(output.isEndOfStream)
            output.release()
        }
    }

    @Test fun `nonzero input position respects sample boundaries`() {
        withDecoder { decoder ->
            val frame = MpegPcmCoreTest.frame(1, 1)
            val input = requireNotNull(decoder.dequeueInputBuffer())
            input.data = ByteBuffer.wrap(byteArrayOf(99, 98) + frame + byteArrayOf(97)).apply {
                position(2)
                limit(2 + frame.size)
            }
            input.timeUs = 4000
            decoder.queueInputBuffer(input)
            val output = awaitOutput(decoder)
            assertEquals(768, output.data!!.remaining())
            output.release()
        }
    }

    @Test fun `bad sample produces bounded decode error not endless silent output`() {
        withDecoder { decoder ->
            queue(decoder, byteArrayOf(0, 1, 2), 0)
            assertThrows(MpegAudioException::class.java) { awaitOutput(decoder) }
        }
    }

    @Test fun `isolated Layer II damage keeps the decoder and media timestamps advancing`() {
        withDecoder(channels = 2) { decoder ->
            val good = MpegFrameRecoveryTest.goodFrame()
            queue(decoder, good, 7_000_000)
            awaitOutput(decoder).release()
            queue(decoder, MpegFrameRecoveryTest.damagedFrame(), 7_024_000)
            val gap = awaitOutput(decoder)
            assertEquals(7_024_000, gap.timeUs)
            assertEquals(2304 * 2, gap.data!!.remaining())
            assertFalse(gap.shouldBeSkipped)
            while (gap.data!!.hasRemaining()) assertEquals(0, gap.data!!.short.toInt())
            gap.release()
            queue(decoder, good, 7_048_000)
            val next = awaitOutput(decoder)
            assertEquals(7_048_000, next.timeUs)
            assertEquals(2304 * 2, next.data!!.remaining())
            next.release()
        }
    }

    private fun queue(decoder: MpegAudioDecoder, bytes: ByteArray, timestamp: Long) {
        val input = requireNotNull(decoder.dequeueInputBuffer())
        input.ensureSpaceForWrite(bytes.size)
        input.data!!.put(bytes)
        input.timeUs = timestamp
        input.flip()
        decoder.queueInputBuffer(input)
    }

    private fun awaitOutput(decoder: MpegAudioDecoder): SimpleDecoderOutputBuffer {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            decoder.dequeueOutputBuffer()?.let { return it }
            Thread.sleep(1)
        }
        throw AssertionError("MPEG decoder did not return PCM/EOS within 2s")
    }

    private fun withDecoder(channels: Int = 1, block: (MpegAudioDecoder) -> Unit) {
        val decoder = MpegAudioDecoder(48000, channels)
        try { block(decoder) } finally { decoder.release() }
    }
}
