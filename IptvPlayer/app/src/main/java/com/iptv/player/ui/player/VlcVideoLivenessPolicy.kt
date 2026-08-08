package com.iptv.player.ui.player

/**
 * Pure reducer for libVLC's cumulative media statistics.
 *
 * displayedPictures is the output heartbeat. decodedVideo distinguishes an
 * output path that stopped presenting frames, while playedAudioBuffers and the
 * media clock prove the source/session is still alive if video decode itself
 * stalls. A video cadence must first be observed; streams that are genuinely a
 * single still image therefore never enter the failure state.
 */
internal object VlcVideoLivenessPolicy {

    enum class Decision {
        WAIT,
        VIDEO_STALL,
    }

    data class Sample(
        val nowMs: Long,
        val playbackActive: Boolean,
        val inputBuffering: Boolean,
        val verifiedVideo: Boolean,
        val positionMs: Long,
        val decodedVideo: Int?,
        val displayedPictures: Int?,
        val playedAudioBuffers: Int?,
        val readBytes: Int?,
        val expectedVideoFps: Float?,
    )

    data class State(
        val initialized: Boolean = false,
        val cadenceEstablished: Boolean = false,
        val lastNowMs: Long = 0L,
        val lastPositionMs: Long = 0L,
        val lastDecodedVideo: Int = 0,
        val lastDisplayedPictures: Int = 0,
        val lastPlayedAudioBuffers: Int = 0,
        val lastReadBytes: Int = 0,
        val displayAdvanceAtMs: Long = 0L,
        val positionAtDisplayAdvanceMs: Long = 0L,
        val decodedAtDisplayAdvance: Int = 0,
        val audioAtDisplayAdvance: Int = 0,
        val readBytesAtDisplayAdvance: Int = 0,
    )

    data class Result(
        val state: State,
        val decision: Decision,
    )

    fun reduce(
        previous: State,
        sample: Sample,
        frameTimeoutMs: Long,
        minimumClockAdvanceMs: Long,
    ): Result {
        require(frameTimeoutMs > 0L) { "frameTimeoutMs must be positive" }
        require(minimumClockAdvanceMs >= 0L) {
            "minimumClockAdvanceMs must not be negative"
        }
        val decoded = sample.decodedVideo ?: return Result(previous, Decision.WAIT)
        val displayed = sample.displayedPictures ?: return Result(previous, Decision.WAIT)
        val audio = sample.playedAudioBuffers ?: return Result(previous, Decision.WAIT)
        val readBytes = sample.readBytes ?: return Result(previous, Decision.WAIT)
        if (decoded < 0 || displayed < 0 || audio < 0 || readBytes < 0) {
            return Result(State(), Decision.WAIT)
        }

        fun baseline(cadence: Boolean = false): State = State(
            initialized = true,
            cadenceEstablished = cadence,
            lastNowMs = sample.nowMs,
            lastPositionMs = sample.positionMs,
            lastDecodedVideo = decoded,
            lastDisplayedPictures = displayed,
            lastPlayedAudioBuffers = audio,
            lastReadBytes = readBytes,
            displayAdvanceAtMs = sample.nowMs,
            positionAtDisplayAdvanceMs = sample.positionMs,
            decodedAtDisplayAdvance = decoded,
            audioAtDisplayAdvance = audio,
            readBytesAtDisplayAdvance = readBytes,
        )

        if (
            !sample.playbackActive ||
            sample.inputBuffering ||
            !sample.verifiedVideo ||
            !previous.initialized
        ) {
            return Result(baseline(), Decision.WAIT)
        }
        // Seek, counter wrap/media replacement, or a non-monotonic clock starts a
        // fresh proof window instead of inheriting stale failure evidence.
        if (
            sample.nowMs < previous.lastNowMs ||
            sample.positionMs + POSITION_BACKWARD_TOLERANCE_MS < previous.lastPositionMs ||
            decoded < previous.lastDecodedVideo ||
            displayed < previous.lastDisplayedPictures ||
            audio < previous.lastPlayedAudioBuffers ||
            readBytes < previous.lastReadBytes
        ) {
            return Result(baseline(), Decision.WAIT)
        }

        if (displayed > previous.lastDisplayedPictures) {
            return Result(
                baseline(cadence = true),
                Decision.WAIT,
            )
        }

        val next = previous.copy(
            lastNowMs = sample.nowMs,
            lastPositionMs = sample.positionMs,
            lastDecodedVideo = decoded,
            lastDisplayedPictures = displayed,
            lastPlayedAudioBuffers = audio,
            lastReadBytes = readBytes,
        )
        if (!previous.cadenceEstablished) return Result(next, Decision.WAIT)

        val stalledForMs = sample.nowMs - previous.displayAdvanceAtMs
        val mediaClockAdvanceMs =
            (sample.positionMs - previous.positionAtDisplayAdvanceMs).coerceAtLeast(0L)
        if (
            stalledForMs < frameTimeoutMs ||
            mediaClockAdvanceMs < minimumClockAdvanceMs
        ) {
            return Result(next, Decision.WAIT)
        }

        val decoderStillProducing = decoded > previous.decodedAtDisplayAdvance
        val audioStillProducing = audio > previous.audioAtDisplayAdvance
        val inputStillReading = readBytes > previous.readBytesAtDisplayAdvance
        val normalFrameCadence = (sample.expectedVideoFps ?: 0f) >= MIN_EXPECTED_VIDEO_FPS
        val confirmed = decoderStillProducing ||
            (normalFrameCadence && audioStillProducing && inputStillReading)
        return Result(
            next,
            if (confirmed) Decision.VIDEO_STALL else Decision.WAIT,
        )
    }

    private const val POSITION_BACKWARD_TOLERANCE_MS = 1_000L
    private const val MIN_EXPECTED_VIDEO_FPS = 2f
}
