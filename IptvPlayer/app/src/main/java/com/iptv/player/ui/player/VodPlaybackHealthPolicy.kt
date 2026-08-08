package com.iptv.player.ui.player

/** Pure VOD stall classification, independent from Android/libVLC. */
internal object VodPlaybackHealthPolicy {

    enum class DisplayEvidence {
        UNKNOWN,
        HEALTHY,
        FAILED,
    }

    enum class Decision {
        WAIT,
        SOURCE_STALL,
        DECODER_STALL,
    }

    data class Evidence(
        val stalledForMs: Long,
        /** libVLC says input is actively buffering/starved. */
        val inputBuffering: Boolean,
        /** A video pipeline/vout has been created for this media session. */
        val decodedVideoSeen: Boolean,
        /** PixelCopy-backed display verification for the current output. */
        val display: DisplayEvidence,
    )

    fun classify(evidence: Evidence, timeoutMs: Long): Decision {
        if (evidence.display == DisplayEvidence.FAILED) return Decision.DECODER_STALL
        if (evidence.stalledForMs < timeoutMs) return Decision.WAIT
        if (evidence.inputBuffering) return Decision.SOURCE_STALL

        // A decoder pipeline that never produced one verifiable frame is stronger
        // decoder evidence than a generic network failure. Once a healthy frame has
        // existed, a later frozen playback clock without buffering is treated as a
        // half-open source/demuxer; the dedicated surface monitor independently
        // catches green/blank display regressions while the clock still advances.
        return if (
            evidence.decodedVideoSeen &&
            evidence.display == DisplayEvidence.UNKNOWN
        ) {
            Decision.DECODER_STALL
        } else {
            Decision.SOURCE_STALL
        }
    }
}
