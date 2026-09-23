package com.iptv.player.player

import androidx.media3.common.PlaybackException
import com.iptv.player.playback.core.FailureSignal
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackFailureClassifier

/**
 * Media3 1.9+ arms a StuckPlayerDetector on every ExoPlayer.Builder. Its four
 * detectors stop the player with ERROR_CODE_TIMEOUT (a StuckPlayerException),
 * so each engine must decide which of them may pre-empt the app's own watchdogs.
 * The values live here, per engine, so they are documented and asserted
 * (ExoStuckDetectionPolicyTest) instead of being implicit library defaults.
 *
 * Verified against media3 1.11.1: ExoPlayer.Builder defaults (10 s playing,
 * 60 s not-ending, 600 s buffering, 600 s suppressed) and the setters
 * setStuckPlayingDetectionTimeoutMs / setStuckPlayingNotEndingTimeoutMs /
 * setStuckBufferingDetectionTimeoutMs / setStuckSuppressedDetectionTimeoutMs.
 * The builder only requires a positive int; Integer.MAX_VALUE disables a
 * detector in practice because StuckPlayerDetector schedules its timeout
 * message that far ahead (~24.8 days). Note that any MAX_VALUE timeout also
 * flips the builder's DEFAULT wake mode from LOCAL to NONE, so engines pin the
 * wake mode explicitly.
 */
internal object ExoStuckDetectionPolicy {

    /** Media3 has no "off" switch per detector; MAX_VALUE never fires. */
    const val DISABLED_MS: Int = Int.MAX_VALUE

    /** media3 1.11.1 ExoPlayer.Builder defaults (non-emulator values). */
    const val MEDIA3_DEFAULT_PLAYING_NO_PROGRESS_MS: Int = 10_000
    const val MEDIA3_DEFAULT_PLAYING_NOT_ENDING_MS: Int = 60_000
    const val MEDIA3_DEFAULT_BUFFERING_NO_PROGRESS_MS: Int = 600_000
    const val MEDIA3_DEFAULT_SUPPRESSED_MS: Int = 600_000

    data class Timeouts(
        /** isPlaying() while getCurrentPosition() does not advance. */
        val playingNoProgressMs: Int,
        /** Position at/after the declared period duration without STATE_ENDED. */
        val playingNotEndingMs: Int,
        /** BUFFERING + playWhenReady while the buffered position does not grow. */
        val bufferingNoProgressMs: Int,
        /** Playback suppressed (focus loss / unsuitable output) without change. */
        val suppressedMs: Int,
    ) {
        init {
            // ExoPlayer.Builder rejects anything else with checkArgument(> 0).
            require(playingNoProgressMs > 0) { "playingNoProgressMs must be positive" }
            require(playingNotEndingMs > 0) { "playingNotEndingMs must be positive" }
            require(bufferingNoProgressMs > 0) { "bufferingNoProgressMs must be positive" }
            require(suppressedMs > 0) { "suppressedMs must be positive" }
        }
    }

    /**
     * Live TS (ExoPlayerEngine). The controller already owns the frozen-clock
     * case: LivePlaybackProgressPolicy (STALL_TIMEOUT_MS 15 s) and
     * LiveBufferStarvationPolicy (6 s) reconnect the same stage, and
     * LiveAudioStallPolicy needs its own window to prove an AC-3 sink stall
     * before the PCM rescue. Media3's 10 s no-progress detector would fire
     * first and degrade all of them into a generic error, so it is disabled.
     * "Not ending" is disabled too: a live TS has no trustworthy duration and
     * must never be declared ended by a guess. Stuck-buffering (and the
     * suppressed-playback detector) keep Media3's defaults; the controller's
     * starvation watchdog fires long before either could.
     */
    val LIVE = Timeouts(
        playingNoProgressMs = DISABLED_MS,
        playingNotEndingMs = DISABLED_MS,
        bufferingNoProgressMs = MEDIA3_DEFAULT_BUFFERING_NO_PROGRESS_MS,
        suppressedMs = MEDIA3_DEFAULT_SUPPRESSED_MS,
    )

    /**
     * VOD (Media3VodEngine). The 10 s no-progress detector stays on: it beats
     * the activity's 30 s position watch and is classified as a same-route
     * stall (see [stallFailure]). "Not ending" is disabled so a file whose
     * declared duration is wrong keeps playing to its real EOF, exactly as on
     * Media3 1.8.1. The two 10-minute detectors keep Media3's defaults.
     */
    val VOD = Timeouts(
        playingNoProgressMs = MEDIA3_DEFAULT_PLAYING_NO_PROGRESS_MS,
        playingNotEndingMs = DISABLED_MS,
        bufferingNoProgressMs = MEDIA3_DEFAULT_BUFFERING_NO_PROGRESS_MS,
        suppressedMs = MEDIA3_DEFAULT_SUPPRESSED_MS,
    )

    /**
     * ERROR_CODE_TIMEOUT is raised only by Media3's own watchdogs: the
     * StuckPlayerDetector callback and the ExoTimeoutException cases in
     * ExoPlayerImpl (renderer release, foreground mode, surface detach). None
     * is decoder, sink or transport evidence, so it must never enter a decoder
     * ladder; a same-route retry is the right answer for all of them.
     */
    fun isStuckPlayerError(errorCode: Int): Boolean =
        errorCode == PlaybackException.ERROR_CODE_TIMEOUT

    /**
     * A stuck detection is a recoverable stall on the SAME route:
     * TIMEOUT / PLAYBACK_STALL / RETRY_SAME_ROUTE, the kind the live controller
     * already emits for its own stall watchdog. VodPlaybackRoutingPolicy maps it
     * to the source ladder, whose first step is a same-route retry from the
     * current position.
     */
    fun stallFailure(phase: PlaybackFailure.Phase): PlaybackFailure =
        PlaybackFailureClassifier.classify(
            FailureSignal.Timeout(FailureSignal.TimeoutKind.STALL),
            phase,
        )
}
