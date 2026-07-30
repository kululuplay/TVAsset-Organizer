package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

/**
 * Pure decisions for ExoPlayer failures and resolved track support.
 *
 * Keeping these decisions outside [ExoPlayerEngine] makes the important routing
 * contract explicit:
 *
 *  - Audio renderer/AudioTrack failures use VLC's audio compatibility path.
 *  - Video (or otherwise unidentified) codec failures use the decode fallback.
 *  - Network, source and manifest failures remain ordinary source errors.
 *
 * [TrackGroupState] is a tiny Media3-free snapshot so the selected/supported
 * track rules can be exercised by local JVM tests.
 */
@OptIn(markerClass = [UnstableApi::class])
internal object ExoPlaybackFailureClassifier {

    enum class Failure {
        AUDIO,
        DECODE,
        ERROR,
    }

    data class TrackGroupState(
        val type: Int,
        val selected: List<Boolean>,
        val supported: List<Boolean>,
    ) {
        init {
            require(selected.size == supported.size) {
                "selected and supported track state must have equal sizes"
            }
        }

        val hasSelectedSupportedTrack: Boolean
            get() = selected.indices.any { selected[it] && supported[it] }
    }

    /**
     * Classify a fatal Media3 error. Decoder errors are attributed to audio only
     * when Media3 identifies the failing renderer as audio; otherwise they are
     * decode/video compatibility failures.
     */
    fun classifyError(errorCode: Int, rendererType: Int?): Failure = when {
        errorCode in AUDIO_OUTPUT_ERROR_CODES -> Failure.AUDIO
        errorCode in VIDEO_PROCESSING_ERROR_CODES -> Failure.DECODE
        errorCode in DECODER_ERROR_CODES && rendererType == C.TRACK_TYPE_AUDIO ->
            Failure.AUDIO
        errorCode in DECODER_ERROR_CODES -> Failure.DECODE
        else -> Failure.ERROR
    }

    /**
     * Return the compatibility failure visible in a settled Media3 track snapshot.
     *
     * An empty snapshot or a snapshot whose video group has not appeared yet is
     * treated as unresolved. Live extractors often publish audio first; the
     * engine's longer no-frame deadline handles a genuinely missing video path.
     * Once video groups exist, a lack of any selected/playable path is a decode
     * compatibility failure.
     */
    fun classifyTracks(
        groups: List<TrackGroupState>,
        expectsVideo: Boolean,
    ): Failure? {
        if (groups.isEmpty()) return null

        val videoGroups = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (
            expectsVideo &&
            videoGroups.isNotEmpty() &&
            videoGroups.none { it.hasSelectedSupportedTrack }
        ) {
            return Failure.DECODE
        }

        val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isNotEmpty() && audioGroups.none { it.hasSelectedSupportedTrack }) {
            return Failure.AUDIO
        }

        return null
    }

    private val DECODER_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    )

    private val AUDIO_OUTPUT_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
    )

    private val VIDEO_PROCESSING_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
    )
}
