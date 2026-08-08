package com.iptv.player.ui.player

/**
 * Conservative video-only liveness decision for VOD.
 *
 * The policy requires three independent facts before declaring a decoder/output
 * freeze: playback is actively expected, the media clock has advanced, and a
 * backend-native rendered-frame heartbeat has stopped beyond a bounded grace.
 * Network buffering, pause, seek/rebuffer grace and audio-only/unknown outputs all
 * remain WAIT states.
 */
internal object VodVideoLivenessPolicy {

    enum class Decision {
        WAIT,
        VIDEO_STALL,
    }

    data class Evidence(
        val playbackActive: Boolean,
        val inputBuffering: Boolean,
        val verifiedVideo: Boolean,
        val mediaClockAdvanceMs: Long,
        val lastFrameAgeMs: Long?,
        val graceElapsedMs: Long,
    )

    fun classify(
        evidence: Evidence,
        frameTimeoutMs: Long,
        minimumClockAdvanceMs: Long,
    ): Decision {
        require(frameTimeoutMs > 0L) { "frameTimeoutMs must be positive" }
        require(minimumClockAdvanceMs >= 0L) {
            "minimumClockAdvanceMs must not be negative"
        }
        val frameAge = evidence.lastFrameAgeMs ?: return Decision.WAIT
        if (
            !evidence.playbackActive ||
            evidence.inputBuffering ||
            !evidence.verifiedVideo ||
            evidence.graceElapsedMs < frameTimeoutMs ||
            evidence.mediaClockAdvanceMs < minimumClockAdvanceMs ||
            frameAge < frameTimeoutMs
        ) {
            return Decision.WAIT
        }
        return Decision.VIDEO_STALL
    }
}
