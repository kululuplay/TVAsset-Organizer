package com.iptv.player.player

import androidx.media3.common.Player

/** Media3 readiness and downloading are independent; only readiness gates output health. */
internal fun liveVideoEvidence(
    player: Player,
    firstFrameRendered: Boolean,
    videoFailureReported: Boolean,
    clockAdvanceMs: Long,
    frameAgeMs: Long?,
): LiveVideoLivenessPolicy.Evidence = LiveVideoLivenessPolicy.Evidence(
    playbackReady = !videoFailureReported && firstFrameRendered &&
        player.playWhenReady && player.playbackState == Player.STATE_READY,
    inputBuffering = player.playbackState == Player.STATE_BUFFERING,
    mediaClockAdvanceMs = clockAdvanceMs,
    lastFrameAgeMs = frameAgeMs,
)
