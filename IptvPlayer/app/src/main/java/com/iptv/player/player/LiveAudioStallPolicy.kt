package com.iptv.player.player

import com.iptv.player.playback.core.AudioFailureEvidence

/**
 * Pure AC-3 audio-health decision used by the Media3 live engine.
 *
 * AudioTrack underruns by themselves are not proof of an audio failure: a dead
 * network connection starves every renderer and produces the same callback. The
 * compatibility fallback is therefore allowed only after the stream has already
 * produced media and either:
 *
 *  - Media3 reports an audio sink/codec failure, or
 *  - repeated audio underruns happen while video is still advancing, or
 *  - an initialized AC-3 decoder never starts its audio clock and remains in
 *    buffering after video was produced.
 *
 * Only AC-3 on the PCM-safe path is targeted. E-AC-3 and every other codec remain
 * on their existing route, and an explicit passthrough choice is never overridden.
 */
internal object LiveAudioStallPolicy {

    enum class Decision { WAIT, FALLBACK_TO_VLC_PCM }

    data class Evidence(
        val codec: AudioFailureEvidence.Codec,
        val decoder: AudioFailureEvidence.Decoder,
        val outputMode: AudioFailureEvidence.OutputMode,
        val mediaProgressObserved: Boolean,
        val recentVideoProgress: Boolean,
        /** Input/buffer advanced after the suspected audio episode began. */
        val sourceProgressAfterIssue: Boolean = false,
        val decoderInitialized: Boolean,
        val audioClockStarted: Boolean,
        val underrunsInWindow: Int = 0,
        val bufferingDurationMs: Long = 0L,
        val sinkEvent: AudioFailureEvidence.SinkEvent? = null,
    ) {
        init {
            require(underrunsInWindow >= 0) { "underrunsInWindow must not be negative" }
            require(bufferingDurationMs >= 0L) { "bufferingDurationMs must not be negative" }
        }
    }

    fun decide(evidence: Evidence): Decision {
        if (evidence.codec != AudioFailureEvidence.Codec.AC3) return Decision.WAIT
        if (evidence.outputMode != AudioFailureEvidence.OutputMode.PCM) return Decision.WAIT
        if (!evidence.mediaProgressObserved) return Decision.WAIT

        if (
            evidence.sinkEvent == AudioFailureEvidence.SinkEvent.SINK_ERROR ||
            evidence.sinkEvent == AudioFailureEvidence.SinkEvent.CODEC_ERROR
        ) {
            return Decision.FALLBACK_TO_VLC_PCM
        }

        if (
            evidence.decoderInitialized &&
            evidence.audioClockStarted &&
            (evidence.recentVideoProgress || evidence.sourceProgressAfterIssue) &&
            evidence.underrunsInWindow >= MIN_UNDERRUNS
        ) {
            return Decision.FALLBACK_TO_VLC_PCM
        }

        if (
            evidence.decoderInitialized &&
            !evidence.audioClockStarted &&
            (evidence.recentVideoProgress || evidence.sourceProgressAfterIssue) &&
            evidence.bufferingDurationMs >= AUDIO_CLOCK_START_TIMEOUT_MS
        ) {
            return Decision.FALLBACK_TO_VLC_PCM
        }

        return Decision.WAIT
    }

    /** Exactly one Exo -> VLC PCM rescue; a tried target is never revisited. */
    fun fallbackStage(
        current: PlaybackRoutingPolicy.Stage,
        outputMode: AudioFailureEvidence.OutputMode,
        alreadyUsed: Boolean,
        triedStages: Set<PlaybackRoutingPolicy.Stage>,
        bypassVlcHardware: Boolean,
    ): PlaybackRoutingPolicy.Stage? {
        if (
            current != PlaybackRoutingPolicy.Stage.EXO ||
            outputMode != AudioFailureEvidence.OutputMode.PCM ||
            alreadyUsed
        ) {
            return null
        }
        val target = if (bypassVlcHardware) {
            PlaybackRoutingPolicy.Stage.VLC_SW
        } else {
            PlaybackRoutingPolicy.Stage.VLC_HW
        }
        return target.takeIf { it !in triedStages }
    }

    fun codecForMime(sampleMimeType: String?): AudioFailureEvidence.Codec = when (
        sampleMimeType?.lowercase()
    ) {
        "audio/ac3" -> AudioFailureEvidence.Codec.AC3
        "audio/eac3", "audio/eac3-joc" -> AudioFailureEvidence.Codec.E_AC3
        "audio/mp4a-latm", "audio/aac" -> AudioFailureEvidence.Codec.AAC
        "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> AudioFailureEvidence.Codec.MPEG_AUDIO
        null -> AudioFailureEvidence.Codec.UNKNOWN
        else -> AudioFailureEvidence.Codec.OTHER
    }

    /** Map a vendor decoder name to a closed, privacy-safe class. */
    fun decoderForName(decoderName: String?): AudioFailureEvidence.Decoder {
        val normalized = decoderName?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return AudioFailureEvidence.Decoder.UNKNOWN
        return if (
            normalized.startsWith("omx.google.") ||
            normalized.startsWith("c2.android.") ||
            normalized.contains("ffmpeg") ||
            normalized.contains("software") ||
            normalized.endsWith(".sw")
        ) {
            AudioFailureEvidence.Decoder.SOFTWARE
        } else {
            AudioFailureEvidence.Decoder.HARDWARE
        }
    }

    const val AUDIO_CLOCK_START_TIMEOUT_MS = 6_000L
    const val UNDERRUN_WINDOW_MS = 12_000L
    const val RECENT_VIDEO_PROGRESS_MS = 2_500L
    private const val MIN_UNDERRUNS = 2
}
