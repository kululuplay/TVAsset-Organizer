package com.iptv.player.player

/** A fresh, finite image comparison; historical video/clock evidence is not proof of motion. */
internal class SurfaceProgressProbePolicy {
    enum class Decision { WAIT, PROGRESS, FINISHED }

    private var previousHealthyPixels: IntArray? = null
    private var samples = 0
    private var finished = false

    fun onSample(pixels: IntArray?): Decision {
        if (finished) return Decision.FINISHED
        samples++
        if (pixels == null) {
            cancel()
            return Decision.FINISHED
        }
        val healthy = !FrameColorClassifier.isSolidGreen(pixels) &&
            !FrameColorClassifier.isVisuallyBlank(pixels)
        if (healthy) {
            val previous = previousHealthyPixels
            if (previous != null && !previous.contentEquals(pixels)) {
                cancel()
                return Decision.PROGRESS
            }
            previousHealthyPixels = pixels.copyOf()
        } else {
            // A bad image between captures invalidates the prior healthy image.
            previousHealthyPixels = null
        }
        if (samples >= MAX_SAMPLES) {
            cancel()
            return Decision.FINISHED
        }
        return Decision.WAIT
    }

    fun cancel() {
        finished = true
        previousHealthyPixels = null
    }

    private companion object {
        private const val MAX_SAMPLES = 4
    }
}
