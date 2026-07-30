package com.iptv.player.player

/**
 * Pure state machine for libVLC media statistics.
 *
 * Playback time can advance while video is frozen because audio is still alive.
 * These counters distinguish real displayed-picture progress from that state.
 *
 * libVLC's lostPictures counter is diagnostic only. On broadcast/interlaced
 * streams it can include discarded fields and frames that were intentionally
 * dropped by deinterlacing or clock correction. A high ratio must therefore
 * never terminate playback while displayedPictures is still advancing.
 */
internal class VlcPlaybackHealth {
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
    )

    private var previous: Sample? = null
    private var lastDisplayedProgressAtMs = 0L
    private var firstFrameReported = false
    private var failureReported = false

    fun reset() {
        previous = null
        lastDisplayedProgressAtMs = 0L
        firstFrameReported = false
        failureReported = false
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
        val decodedDelta = positiveDelta(sample.decodedVideo, before.decodedVideo)
        val bytesDelta = positiveDelta(sample.readBytes, before.readBytes)
        val timeDelta = positiveDelta(sample.playbackTimeMs, before.playbackTimeMs)

        val inputStillProgressing =
            decodedDelta > 0 ||
                bytesDelta >= MIN_INPUT_PROGRESS_BYTES ||
                timeDelta >= MIN_TIME_PROGRESS_MS

        if (displayedDelta > 0) {
            lastDisplayedProgressAtMs = nowMs
        }

        // Network buffering is not a decoder/output failure. Pause the freeze
        // deadline until libVLC reports active playback again.
        if (!videoActivelyPlaying) {
            lastDisplayedProgressAtMs = nowMs
            return Decision(firstDisplayedFrame = firstFrame)
        }

        val frozen = !failureReported &&
            firstFrameReported &&
            displayedDelta == 0L &&
            // Attribute the failure to decode/output only while input, decoded
            // frames or the playback clock are still advancing. If the whole
            // pipeline stopped, that is an ambiguous network/source stall and
            // the controller's source reconnect watchdog must handle it instead
            // of poisoning the channel's learned decoder route.
            inputStillProgressing &&
            nowMs - lastDisplayedProgressAtMs >= VIDEO_FREEZE_MS

        if (frozen) failureReported = true
        return Decision(
            firstDisplayedFrame = firstFrame,
            videoFrozen = frozen,
        )
    }

    private fun positiveDelta(current: Long, old: Long): Long =
        if (current >= old) current - old else 0L

    private companion object {
        private const val VIDEO_FREEZE_MS = 7_000L
        private const val MIN_INPUT_PROGRESS_BYTES = 64L * 1024L
        private const val MIN_TIME_PROGRESS_MS = 500L
    }
}
