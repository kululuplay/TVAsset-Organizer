/*
 * VodPlaybackHealthWatch.kt
 *
 * Architecture note (VOD player decomposition):
 *   Mid-stream health detection for the on-demand player, split out of
 *   VodPlayerActivity. It owns the stall/liveness bookkeeping (last observed
 *   position, input-buffering start, renderer-heartbeat generations) and turns
 *   the pure policies (VodPlaybackHealthPolicy, VodVideoLivenessPolicy,
 *   VlcVideoLivenessPolicy) into a single recovery request. It never touches a
 *   backend: every engine/state read and the failure dispatch go through [Host],
 *   so the Activity keeps its foreground/recovery guards and threading intact.
 *
 * Moved verbatim from VodPlayerActivity; log messages are unchanged.
 */
package com.iptv.player.ui.player

import android.os.SystemClock
import com.iptv.player.player.vod.VodEngine
import com.iptv.player.player.vod.VodVideoLiveness

/** Strength of the evidence behind a recovery request. */
internal enum class RecoveryEvidence {
    GENERIC,
    CONFIRMED_VIDEO_FAILURE,
}

internal class VodPlaybackHealthWatch(private val host: Host) {

    interface Host {
        val foreground: Boolean
        val playbackStarted: Boolean
        val userSeeking: Boolean
        /** `seekTimeline.targetMs != null` */
        val seekPending: Boolean
        val recoveryInProgress: Boolean
        val media3Route: Boolean
        val media3Generation: Long
        val coordinatorGeneration: Long
        val media3CoordinatorGeneration: Long
        /** Generation of the current native VLC owner, or null when none. */
        val nativeOwnerGeneration: Long?
        val decodedVideoSeen: Boolean
        val surfaceOutputConfirmed: Boolean
        fun activeIsPlaying(): Boolean
        fun media3VideoLiveness(): VodVideoLiveness?
        fun currentVlcSnapshot(): VlcPlaybackSnapshot?
        fun handlePlaybackError(evidence: RecoveryEvidence)
        fun log(message: String)
    }

    companion object {
        // After playback has started, a half-open HTTP connection can freeze
        // without EOF/error. Position must advance inside this window.
        const val STALL_TIMEOUT_MS = 30_000L
        // Media3's renderer heartbeat catches the inverse failure: audio/media
        // time advances while video frames stop. Keep the window conservative so
        // seek/rebuffer transitions cannot be mistaken for a decoder freeze.
        const val VIDEO_FRAME_STALL_TIMEOUT_MS = 12_000L
        const val VIDEO_CLOCK_EVIDENCE_MS = 4_000L
    }

    var lastObservedPositionMs = -1L
    private var lastPositionAdvanceAtMs = 0L
    var bufferingSinceMs = 0L
    private var videoLivenessGeneration = VodEngine.NO_GENERATION
    private var videoLivenessWatchStartedAtMs = 0L
    private var videoLivenessStartPositionMs = 0L
    private var vlcVideoLivenessOwnerGeneration = -1L
    private var vlcVideoLivenessState = VlcVideoLivenessPolicy.State()

    fun resetStallWatch(positionMs: Long) {
        lastObservedPositionMs = positionMs
        lastPositionAdvanceAtMs = SystemClock.uptimeMillis()
        bufferingSinceMs = 0L
        resetVideoLivenessWatch(positionMs)
    }

    fun resetVideoLivenessWatch(positionMs: Long) {
        val media3 = host.media3Route
        videoLivenessGeneration = if (media3) {
            host.media3Generation
        } else {
            VodEngine.NO_GENERATION
        }
        videoLivenessWatchStartedAtMs = SystemClock.elapsedRealtime()
        videoLivenessStartPositionMs = positionMs.coerceAtLeast(0L)
        vlcVideoLivenessOwnerGeneration = if (media3) {
            -1L
        } else {
            host.nativeOwnerGeneration ?: -1L
        }
        vlcVideoLivenessState = VlcVideoLivenessPolicy.State()
    }

    private fun detectVideoLiveness(positionMs: Long) {
        if (!host.media3Route) {
            detectVlcVideoLiveness(positionMs)
            return
        }
        val sample = host.media3VideoLiveness() ?: return
        if (
            sample.generation != host.media3Generation ||
            host.media3CoordinatorGeneration != host.coordinatorGeneration
        ) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (
            videoLivenessGeneration != sample.generation ||
            videoLivenessWatchStartedAtMs <= 0L
        ) {
            videoLivenessGeneration = sample.generation
            videoLivenessWatchStartedAtMs = now
            videoLivenessStartPositionMs = positionMs.coerceAtLeast(0L)
            return
        }
        val decision = VodVideoLivenessPolicy.classify(
            evidence = VodVideoLivenessPolicy.Evidence(
                playbackActive = host.activeIsPlaying(),
                inputBuffering = bufferingSinceMs > 0L,
                verifiedVideo = host.playbackStarted,
                mediaClockAdvanceMs =
                    (positionMs - videoLivenessStartPositionMs).coerceAtLeast(0L),
                lastFrameAgeMs =
                    (now - sample.lastFrameRealtimeMs).coerceAtLeast(0L),
                graceElapsedMs =
                    (now - videoLivenessWatchStartedAtMs).coerceAtLeast(0L),
            ),
            frameTimeoutMs = VIDEO_FRAME_STALL_TIMEOUT_MS,
            minimumClockAdvanceMs = VIDEO_CLOCK_EVIDENCE_MS,
        )
        if (decision == VodVideoLivenessPolicy.Decision.VIDEO_STALL) {
            host.log(
                "renderer heartbeat stalled while media clock advanced " +
                    "frames=${sample.frameSequence}",
            )
            // Reset before dispatch so a rejected/stale failure cannot spin every
            // 500 ms; the coordinator still owns the bounded route ladder.
            resetVideoLivenessWatch(positionMs)
            host.handlePlaybackError(RecoveryEvidence.CONFIRMED_VIDEO_FAILURE)
        }
    }

    private fun detectVlcVideoLiveness(positionMs: Long) {
        val ownerGeneration = host.nativeOwnerGeneration ?: return
        val snapshot = host.currentVlcSnapshot() ?: return
        if (vlcVideoLivenessOwnerGeneration != ownerGeneration) {
            vlcVideoLivenessOwnerGeneration = ownerGeneration
            vlcVideoLivenessState = VlcVideoLivenessPolicy.State()
        }
        val result = VlcVideoLivenessPolicy.reduce(
            previous = vlcVideoLivenessState,
            sample = VlcVideoLivenessPolicy.Sample(
                nowMs = SystemClock.elapsedRealtime(),
                playbackActive = snapshot.playing,
                inputBuffering = bufferingSinceMs > 0L,
                verifiedVideo = host.playbackStarted &&
                    host.decodedVideoSeen &&
                    (snapshot.displayedPictures ?: 0) > 0,
                positionMs = positionMs,
                decodedVideo = snapshot.decodedVideo,
                displayedPictures = snapshot.displayedPictures,
                playedAudioBuffers = snapshot.playedAudioBuffers,
                readBytes = snapshot.readBytes,
                expectedVideoFps = snapshot.streamInfo?.fps,
            ),
            frameTimeoutMs = VIDEO_FRAME_STALL_TIMEOUT_MS,
            minimumClockAdvanceMs = VIDEO_CLOCK_EVIDENCE_MS,
        )
        vlcVideoLivenessState = result.state
        if (result.decision == VlcVideoLivenessPolicy.Decision.VIDEO_STALL) {
            host.log(
                "VLC video counters stalled while audio/input advanced " +
                    "decoded=${snapshot.decodedVideo} " +
                    "displayed=${snapshot.displayedPictures}",
            )
            vlcVideoLivenessState = VlcVideoLivenessPolicy.State()
            host.handlePlaybackError(RecoveryEvidence.CONFIRMED_VIDEO_FAILURE)
        }
    }

    fun detectPlaybackStall(positionMs: Long) {
        if (!host.foreground || !host.playbackStarted || host.userSeeking || host.seekPending || host.recoveryInProgress) return
        detectVideoLiveness(positionMs)
        if (host.recoveryInProgress) return
        val now = SystemClock.uptimeMillis()
        if (
            lastObservedPositionMs < 0L ||
            kotlin.math.abs(positionMs - lastObservedPositionMs) >= 250L
        ) {
            lastObservedPositionMs = positionMs
            lastPositionAdvanceAtMs = now
            return
        }
        val expectedToAdvance = host.activeIsPlaying() || bufferingSinceMs > 0L
        if (!expectedToAdvance || lastPositionAdvanceAtMs <= 0L) return
        val stalledForMs = now - lastPositionAdvanceAtMs
        val decision = VodPlaybackHealthPolicy.classify(
            VodPlaybackHealthPolicy.Evidence(
                stalledForMs = stalledForMs,
                inputBuffering = bufferingSinceMs > 0L,
                decodedVideoSeen = host.decodedVideoSeen,
                display = if (host.surfaceOutputConfirmed) {
                    VodPlaybackHealthPolicy.DisplayEvidence.HEALTHY
                } else {
                    VodPlaybackHealthPolicy.DisplayEvidence.UNKNOWN
                },
            ),
            timeoutMs = STALL_TIMEOUT_MS,
        )
        when (decision) {
            VodPlaybackHealthPolicy.Decision.WAIT -> Unit
            VodPlaybackHealthPolicy.Decision.SOURCE_STALL -> {
                host.log("source/input playback stall")
                host.handlePlaybackError(RecoveryEvidence.GENERIC)
            }
            VodPlaybackHealthPolicy.Decision.DECODER_STALL -> {
                host.log("decoded/display playback stall")
                host.handlePlaybackError(RecoveryEvidence.CONFIRMED_VIDEO_FAILURE)
            }
        }
    }
}
