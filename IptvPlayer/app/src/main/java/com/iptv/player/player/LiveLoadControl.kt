package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.iptv.player.data.model.BufferMode

/**
 * Grows ADAPTIVE's restart reserve on the playback thread without replacing the
 * player or reopening the provider connection. Media3's loader retains its own
 * hysteresis and a bounded byte target; both loading and restart use that same
 * target so a high-bitrate stream can never wait for an unreachable time reserve.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class LiveLoadControl(
    private val configured: BufferMode,
    private val constrainedDevice: Boolean,
    initialRebuffers: Int = 0,
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    reserve(configured, constrainedDevice).exoMinBufferMs,
    reserve(configured, constrainedDevice).exoMaxBufferMs,
    configured.exoPlaybackMs,
    configured.exoRebufferMs,
    targetBytes(constrainedDevice),
    false,
    0,
    false,
) {
    private var rebuffers = initialRebuffers.coerceIn(0, MAX_REBUFFERS)
    private var initialRebufferSeed = rebuffers
    private var lastRebufferRealtimeMs = C.TIME_UNSET

    override fun onPrepared(playerId: PlayerId) {
        super.onPrepared(playerId)
        // A reused engine may now be tuning a different channel. Retry history
        // supplied by its controller seeds only the first prepare of this engine.
        rebuffers = initialRebufferSeed
        initialRebufferSeed = 0
        lastRebufferRealtimeMs = C.TIME_UNSET
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (configured != BufferMode.ADAPTIVE) return super.shouldStartPlayback(parameters)

        // shouldStartPlayback is polled repeatedly during the same rebuffer.
        // Count the engine's monotonic event timestamp, never the number of polls.
        if (
            parameters.rebuffering &&
            parameters.lastRebufferRealtimeMs != C.TIME_UNSET &&
            parameters.lastRebufferRealtimeMs != lastRebufferRealtimeMs
        ) {
            lastRebufferRealtimeMs = parameters.lastRebufferRealtimeMs
            rebuffers = (rebuffers + 1).coerceAtMost(MAX_REBUFFERS)
        }
        val effective = AdaptiveBufferPolicy.resolve(configured, constrainedDevice, rebuffers)
        var requiredUs = 1_000L * if (parameters.rebuffering) {
            effective.exoRebufferMs
        } else {
            effective.exoPlaybackMs
        }
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            requiredUs = minOf(requiredUs, parameters.targetLiveOffsetUs / 2)
        }
        val playoutUs = Util.getPlayoutDurationForMediaDuration(
            parameters.bufferedDurationUs,
            parameters.playbackSpeed,
        )
        return requiredUs <= 0L || playoutUs >= requiredUs ||
            allocator.totalBytesAllocated >= targetBytes(constrainedDevice)
    }

    private companion object {
        private const val MAX_REBUFFERS = 6
        private const val MIB = 1_048_576

        // This bounds encoded samples only; decoder/surface memory is separate.
        private fun targetBytes(constrained: Boolean) = (if (constrained) 24 else 48) * MIB

        // Fill a useful reserve after the first frame. ADAPTIVE used to retain
        // LOW's 4s maximum for an entire engine lifetime, even after many stalls.
        private fun reserve(configured: BufferMode, constrained: Boolean): BufferMode =
            if (configured == BufferMode.ADAPTIVE) {
                if (constrained) BufferMode.NORMAL else BufferMode.HIGH
            } else {
                configured
            }
    }
}
