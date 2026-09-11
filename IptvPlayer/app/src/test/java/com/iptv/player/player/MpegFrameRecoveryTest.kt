package com.iptv.player.player

import androidx.media3.extractor.MpegAudioUtil
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class MpegFrameRecoveryTest {
    @Test fun `one bit of payload damage reproduces the stick grouped sample exception`() {
        val failure = assertThrows(ArrayIndexOutOfBoundsException::class.java) {
            MpegPcmCore(48000, 2).decode(damagedFrame())
        }
        assertTrue(failure.stackTrace.any {
            it.className.contains("LayerIIDecoder") && it.methodName == "read_sampledata"
        })
    }

    @Test fun `isolated damaged frame preserves duration and resets poisoned synthesis state`() {
        val core = warmCore()
        val recovered = core.decode(damagedFrame())
        assertEquals(1, recovered.concealedFrames)
        assertEquals(2, recovered.layer)
        assertEquals(2304, recovered.samples.size)
        assertTrue(recovered.samples.all { it == 0.toShort() })
        val next = core.decode(goodFrame())
        assertEquals(0, next.concealedFrames)
        assertArrayEquals(MpegPcmCore(48000, 2).decode(goodFrame()).samples, next.samples)
    }

    @Test fun `damaged frame in a multi frame packet does not erase surrounding audio`() {
        val core = warmCore()
        val result = core.decode(goodFrame() + damagedFrame() + goodFrame())
        assertEquals(2304 * 3, result.samples.size)
        assertEquals(1, result.concealedFrames)
        assertTrue(result.samples.take(2304).any { it != 0.toShort() })
        assertTrue(result.samples.sliceArray(2304 until 4608).all { it == 0.toShort() })
        assertArrayEquals(MpegPcmCore(48000, 2).decode(goodFrame()).samples,
            result.samples.copyOfRange(4608, 6912))
    }

    @Test fun `third consecutive damaged frame remains a fatal decode error`() {
        val core = warmCore()
        repeat(2) { assertEquals(1, core.decode(damagedFrame()).concealedFrames) }
        assertThrows(ArrayIndexOutOfBoundsException::class.java) { core.decode(damagedFrame()) }
    }

    @Test fun `interleaving healthy frames cannot bypass the rolling damage budget`() {
        val core = warmCore()
        repeat(4) {
            assertEquals(1, core.decode(damagedFrame()).concealedFrames)
            core.decode(goodFrame())
        }
        assertThrows(ArrayIndexOutOfBoundsException::class.java) { core.decode(damagedFrame()) }
    }

    @Test fun `sustained healthy playback expires the rolling damage budget`() {
        val core = warmCore()
        repeat(4) {
            core.decode(damagedFrame())
            core.decode(goodFrame())
        }
        repeat(210) { assertEquals(0, core.decode(goodFrame()).concealedFrames) }
        assertEquals(1, core.decode(damagedFrame()).concealedFrames)
    }

    @Test fun `seek reset clears damage budget and requires newly proven audio`() {
        val core = warmCore()
        repeat(2) { core.decode(damagedFrame()) }
        core.reset()
        assertThrows(ArrayIndexOutOfBoundsException::class.java) { core.decode(damagedFrame()) }
        core.reset()
        core.decode(goodFrame())
        assertEquals(1, core.decode(damagedFrame()).concealedFrames)
    }

    @Test fun `truncated frame and format change are not concealed even after healthy audio`() {
        assertThrows(IllegalArgumentException::class.java) { warmCore().decode(goodFrame().dropLast(1).toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { warmCore().decode(MpegPcmCoreTest.frame(2, 1)) }
    }

    private fun warmCore() = MpegPcmCore(48000, 2).apply { decode(goodFrame()) }

    companion object {
        internal fun goodFrame(): ByteArray {
            val encoded = requireNotNull(MpegFrameRecoveryTest::class.java
                .getResourceAsStream("/mpeg-audio/stereo-48k.mp2")).use { it.readBytes() }
            val header = MpegAudioUtil.Header().also { check(it.setForHeaderData(ByteBuffer.wrap(encoded).int)) }
            return encoded.copyOf(header.frameSize)
        }

        // Synthetic project-owned tone, one flipped payload bit. The MPEG header
        // and frame length stay valid; the grouped Layer-II sample becomes invalid.
        internal fun damagedFrame() = goodFrame().apply { this[289] = (this[289].toInt() xor 4).toByte() }
    }
}
