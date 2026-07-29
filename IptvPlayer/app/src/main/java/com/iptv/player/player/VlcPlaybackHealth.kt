package com.iptv.player.player

/**
 * Pure state machine for libVLC media statistics.
 *
 * Playback time can advance while video is frozen because audio is still alive.
 * These counters distinguish real displayed-picture progress from that state and
 * from a software decoder that continuously loses too many frames.
 */
internal class VlcPlaybackHealth(
    private val softwareDecode: Boolean,
) {
    data class Sample(
        val readBytes: Long,
        val decodedVideo: Long,
        val displayedPictures: Long,
        val lostPictures: Long,
        val playbackTimeMs: Long,
    )

    data class Decision(
        val firstDisplayedFrame: Boolean = false,
        val videoFrozen: Boolean = false,
        val softwareTooSlow: Boolean = false,
    )

    private var previous: Sample? = null
    private var lastDisplayedProgressAtMs = 0L
    private var firstFrameReported = false
    private var failureReported = false
    private var highLossWindows = 0
    private var accumulatedLossWindowFrames = 0L
    private var accumulatedLostPictures = 0L

    fun reset() {
        previous = null
        lastDisplayedProgressAtMs = 0L
        firstFrameReported = false
        failureReported = false
        highLossWindows = 0
        accumulatedLossWindowFrames = 0L
        accumulatedLostPictures = 0L
    }

    fun evaluate(
        sample: Sample,
        nowMs: Long,
        videoActivelyPlaying: Boolean = true,
    ): Decision {
        val before = previous
        previous = sample

        val firstFrame = !firstFrameReported && sample.displayedPictures > 0
        if (firstFrame) firstFrameReported = true

        if (before == null) {
            lastDisplayedProgressAtMs = nowMs
            return Decision(firstDisplayedFrame = firstFrame)
        }

        val displayedDelta = positiveDelta(sample.displayedPictures, before.displayedPictures)
        val lostDelta = positiveDelta(sample.lostPictures, before.lostPictures)
        val decodedDelta = positiveDelta(sample.decodedVideo, before.decodedVideo)
        val bytesDelta = positiveDelta(sample.readBytes, before.readBytes)
        val timeDelta = positiveDelta(sample.playbackTimeMs, before.playbackTimeMs)

        if (displayedDelta > 0) lastDisplayedProgressAtMs = nowMs

        // Network buffering is not a decoder/output failure. Pause the freeze
        // deadline and break the "consecutive high-loss windows" sequence until
        // libVLC reports active playback again.
        if (!videoActivelyPlaying) {
            lastDisplayedProgressAtMs = nowMs
            highLossWindows = 0
            accumulatedLossWindowFrames = 0L
            accumulatedLostPictures = 0L
            return Decision(firstDisplayedFrame = firstFrame)
        }

        val inputStillProgressing =
            decodedDelta > 0 || bytesDelta >= MIN_INPUT_PROGRESS_BYTES || timeDelta >= MIN_TIME_PROGRESS_MS
        val frozen = !failureReported &&
            firstFrameReported &&
            displayedDelta == 0L &&
            inputStillProgressing &&
            nowMs - lastDisplayedProgressAtMs >= VIDEO_FREEZE_MS

        var tooSlow = false
        if (softwareDecode && !failureReported) {
            // A 25/30 fps channel produces fewer than 60 pictures during the
            // 1.5-second health poll. Accumulate adjacent deltas into a real
            // analysis window instead of silently never evaluating those streams.
            accumulatedLossWindowFrames += displayedDelta + lostDelta
            accumulatedLostPictures += lostDelta
            if (accumulatedLossWindowFrames >= MIN_LOSS_WINDOW_FRAMES) {
                val lossRatio =
                    accumulatedLostPictures.toDouble() / accumulatedLossWindowFrames
                highLossWindows = if (lossRatio >= MAX_ACCEPTABLE_LOSS_RATIO) {
                    highLossWindows + 1
                } else {
                    0
                }
                accumulatedLossWindowFrames = 0L
                accumulatedLostPictures = 0L
                tooSlow = highLossWindows >= REQUIRED_HIGH_LOSS_WINDOWS
            }
        }

        if (frozen || tooSlow) failureReported = true
        return Decision(
            firstDisplayedFrame = firstFrame,
            videoFrozen = frozen,
            softwareTooSlow = tooSlow,
        )
    }

    private fun positiveDelta(current: Long, old: Long): Long =
        if (current >= old) current - old else 0L

    private companion object {
        private const val VIDEO_FREEZE_MS = 7_000L
        private const val MIN_INPUT_PROGRESS_BYTES = 64L * 1024L
        private const val MIN_TIME_PROGRESS_MS = 500L
        private const val MIN_LOSS_WINDOW_FRAMES = 60L
        private const val MAX_ACCEPTABLE_LOSS_RATIO = 0.25
        private const val REQUIRED_HIGH_LOSS_WINDOWS = 2
    }
}
