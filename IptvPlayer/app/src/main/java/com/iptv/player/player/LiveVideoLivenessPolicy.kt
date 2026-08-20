package com.iptv.player.player

/** Pure evidence gate that separates a decoder/output freeze from an input stall. */
internal object LiveVideoLivenessPolicy {

    enum class Decision { WAIT, VIDEO_STALL }

    data class Evidence(
        val playbackReady: Boolean,
        val inputBuffering: Boolean,
        val mediaClockAdvanceMs: Long,
        val lastFrameAgeMs: Long?,
    )

    fun classify(
        evidence: Evidence,
        frameTimeoutMs: Long,
        minimumClockAdvanceMs: Long,
    ): Decision {
        val frameAge = evidence.lastFrameAgeMs ?: return Decision.WAIT
        return if (
            evidence.playbackReady &&
            !evidence.inputBuffering &&
            evidence.mediaClockAdvanceMs >= minimumClockAdvanceMs &&
            frameAge >= frameTimeoutMs
        ) {
            Decision.VIDEO_STALL
        } else {
            Decision.WAIT
        }
    }
}
