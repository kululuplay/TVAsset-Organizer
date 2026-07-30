package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameColorClassifierTest {

    @Test
    fun `solid decoder green is detected`() {
        val pixels = IntArray(32 * 18) { rgb(8, 176, 18) }
        assertTrue(FrameColorClassifier.isSolidGreen(pixels))
    }

    @Test
    fun `small codec noise still counts as a solid green frame`() {
        val pixels = IntArray(32 * 18) { index ->
            val noise = (index % 9) - 4
            rgb(12 + noise, 164 + noise, 20 + noise)
        }
        assertTrue(FrameColorClassifier.isSolidGreen(pixels))
    }

    @Test
    fun `mixed green programme content is not rejected`() {
        val pixels = IntArray(32 * 18) { index ->
            when (index % 5) {
                0 -> rgb(230, 230, 230)
                1 -> rgb(20, 90, 24)
                2 -> rgb(30, 145, 45)
                3 -> rgb(12, 72, 18)
                else -> rgb(170, 55, 30)
            }
        }
        assertFalse(FrameColorClassifier.isSolidGreen(pixels))
    }

    @Test
    fun `black and ordinary frames are not green failures`() {
        assertFalse(FrameColorClassifier.isSolidGreen(IntArray(32 * 18) { rgb(0, 0, 0) }))
        assertFalse(FrameColorClassifier.isSolidGreen(IntArray(32 * 18) { rgb(80, 95, 110) }))
    }

    @Test
    fun `empty black decoder surface is visually blank`() {
        assertTrue(
            FrameColorClassifier.isVisuallyBlank(
                IntArray(32 * 18) { index ->
                    val noise = index % 3
                    rgb(noise, noise, noise)
                },
            ),
        )
    }

    @Test
    fun `dark programme detail is not mistaken for an empty surface`() {
        val pixels = IntArray(32 * 18) { index ->
            when (index % 4) {
                0 -> rgb(4, 4, 5)
                1 -> rgb(12, 14, 18)
                2 -> rgb(28, 20, 16)
                else -> rgb(44, 38, 32)
            }
        }

        assertFalse(FrameColorClassifier.isVisuallyBlank(pixels))
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xff shl 24) or (r shl 16) or (g shl 8) or b
}
