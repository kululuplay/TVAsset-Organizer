package com.iptv.player.player

import kotlin.math.sqrt

/**
 * Pure RGB classifier for the classic decoder/compositor "solid green frame".
 *
 * A real programme may legitimately contain a football pitch or green graphics,
 * so green dominance alone is not enough. A frame is rejected only when almost
 * every sampled pixel is green-dominant *and* the whole image has very little
 * colour variance. The runtime monitor also requires this result twice.
 */
internal object FrameColorClassifier {

    fun isSolidGreen(argb: IntArray): Boolean {
        if (argb.size < MIN_PIXELS) return false

        var greenPixels = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumR2 = 0.0
        var sumG2 = 0.0
        var sumB2 = 0.0

        for (pixel in argb) {
            val r = (pixel ushr 16) and 0xff
            val g = (pixel ushr 8) and 0xff
            val b = pixel and 0xff
            if (g >= MIN_GREEN && g - r >= MIN_GREEN_LEAD && g - b >= MIN_GREEN_LEAD) {
                greenPixels++
            }
            sumR += r
            sumG += g
            sumB += b
            sumR2 += r * r
            sumG2 += g * g
            sumB2 += b * b
        }

        val count = argb.size.toDouble()
        val meanR = sumR / count
        val meanG = sumG / count
        val meanB = sumB / count
        val greenRatio = greenPixels / count
        if (greenRatio < MIN_GREEN_RATIO) return false
        if (meanG - maxOf(meanR, meanB) < MIN_MEAN_GREEN_LEAD) return false

        fun deviation(sum: Double, sumSquares: Double): Double {
            val mean = sum / count
            return sqrt((sumSquares / count - mean * mean).coerceAtLeast(0.0))
        }

        return deviation(sumR, sumR2) <= MAX_CHANNEL_DEVIATION &&
            deviation(sumG, sumG2) <= MAX_CHANNEL_DEVIATION &&
            deviation(sumB, sumB2) <= MAX_CHANNEL_DEVIATION
    }

    private const val MIN_PIXELS = 64
    private const val MIN_GREEN = 55
    private const val MIN_GREEN_LEAD = 32
    private const val MIN_GREEN_RATIO = 0.86
    private const val MIN_MEAN_GREEN_LEAD = 38.0
    private const val MAX_CHANNEL_DEVIATION = 30.0
}
