package com.iptv.player.player

/**
 * libVLC occasionally finishes a buffering cycle at 99.9 rather than exactly
 * 100. Treat that as complete so a healthy channel cannot remain behind the
 * buffering UI forever. Invalid native values remain buffering/fail-safe.
 */
internal fun isVlcBuffering(bufferingPercent: Float): Boolean =
    !bufferingPercent.isFinite() ||
        bufferingPercent < VLC_BUFFERING_COMPLETE_PERCENT

/**
 * In software mode libVLC's displayedPictures counter is direct proof that its
 * CPU-decoded frame reached the vout. PixelCopy on some Fire TV SurfaceViews can
 * still return one stale/blank sample and must not override that native evidence.
 * Hardware mode retains strict pixel validation for green-frame detection.
 */
internal fun shouldTrustVlcNativeFrame(
    forceSoftware: Boolean,
    hasDisplayedPictureEvidence: Boolean,
): Boolean = forceSoftware && hasDisplayedPictureEvidence

/**
 * Some libVLC builds never emit Buffering(100) or a second Playing event after a
 * cache top-up. Once the current surface was already verified, advancing native
 * displayed-picture counters are stronger evidence that playback resumed.
 */
internal fun shouldSignalVlcFrameProgressResumed(
    surfaceWasVerified: Boolean,
    bufferingActive: Boolean,
    displayedFrameAdvanced: Boolean,
): Boolean = surfaceWasVerified && bufferingActive && displayedFrameAdvanced

/**
 * Compatibility fallback for VLC builds whose video statistics remain absent or
 * all-zero. A clock alone never proves video progress: after initial surface
 * verification, sustained clock movement merely permits a bounded fresh-image
 * probe. Only that probe or native picture evidence may clear buffering.
 */
internal class VlcBufferingClockRecoveryGate {
    private var previousPositionMs = UNSET
    private var previousSampleAtMs = UNSET
    private var progressStartedAtMs = UNSET
    private var progressingIntervals = 0

    fun shouldProbe(
        surfaceWasVerified: Boolean,
        bufferingActive: Boolean,
        videoStatsUsable: Boolean,
        playbackPositionMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!surfaceWasVerified || !bufferingActive || videoStatsUsable || playbackPositionMs < 0L) {
            reset()
            return false
        }

        val previousPosition = previousPositionMs
        val previousSampleAt = previousSampleAtMs
        previousPositionMs = playbackPositionMs
        previousSampleAtMs = nowMs
        if (previousPosition == UNSET || previousSampleAt == UNSET) return false

        val sampleGapMs = nowMs - previousSampleAt
        val positionAdvanceMs = playbackPositionMs - previousPosition
        if (
            sampleGapMs !in 1L..MAX_SAMPLE_GAP_MS ||
            positionAdvanceMs < MIN_POSITION_ADVANCE_MS ||
            // A timestamp jump/seek is not sustained real-time playback.
            positionAdvanceMs > sampleGapMs * MAX_CLOCK_RATE
        ) {
            progressStartedAtMs = UNSET
            progressingIntervals = 0
            return false
        }

        if (progressStartedAtMs == UNSET) progressStartedAtMs = previousSampleAt
        progressingIntervals++
        val probeAllowed = progressingIntervals >= REQUIRED_PROGRESS_INTERVALS &&
            nowMs - progressStartedAtMs >= MIN_PROGRESS_DURATION_MS
        if (probeAllowed) reset()
        return probeAllowed
    }

    fun reset() {
        previousPositionMs = UNSET
        previousSampleAtMs = UNSET
        progressStartedAtMs = UNSET
        progressingIntervals = 0
    }

    private companion object {
        private const val UNSET = -1L
        private const val MIN_POSITION_ADVANCE_MS = 250L
        private const val MIN_PROGRESS_DURATION_MS = 3_000L
        private const val MAX_SAMPLE_GAP_MS = 4_500L
        private const val MAX_CLOCK_RATE = 2L
        private const val REQUIRED_PROGRESS_INTERVALS = 2
    }
}

private const val VLC_BUFFERING_COMPLETE_PERCENT = 99.5f
