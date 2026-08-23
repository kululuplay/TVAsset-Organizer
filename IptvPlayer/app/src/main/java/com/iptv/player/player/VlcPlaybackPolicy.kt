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

private const val VLC_BUFFERING_COMPLETE_PERCENT = 99.5f
