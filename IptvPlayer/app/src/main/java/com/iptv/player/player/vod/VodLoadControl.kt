package com.iptv.player.player.vod

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.iptv.player.playback.core.BufferMeasurements
import com.iptv.player.playback.core.MeasuredBufferPolicy

/** Movie/episode sample buffering, including the compatibility budget for old TVs. */
@OptIn(markerClass = [UnstableApi::class])
internal object VodLoadControl {
    fun create(buffer: VodBufferConfig, constrainedDevice: Boolean,
        measurements: (() -> BufferMeasurements)? = null, adaptive: Boolean = false): DefaultLoadControl =
        if (measurements != null) MeasuredVodLoadControl(buffer, constrainedDevice, measurements, adaptive)
        else DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                buffer.minBufferMs,
                buffer.maxBufferMs,
                buffer.playbackBufferMs,
                buffer.rebufferMs,
            )
            .apply {
                if (constrainedDevice) {
                    // Media3's track-derived video/audio target can exceed the
                    // entire app heap of an older stick. Bound encoded samples;
                    // decoder surfaces, posters and native allocations are extra.
                    setTargetBufferBytes(24 * 1_048_576)
                    // The same byte target must also permit start/rebuffer. A
                    // high-bitrate source might fill it before the time target.
                    setPrioritizeTimeOverSizeThresholds(false)
                }
            }
            .build()
}

@OptIn(markerClass = [UnstableApi::class])
private class MeasuredVodLoadControl(
    private val buffer: VodBufferConfig,
    private val constrained: Boolean,
    private val measurements: () -> BufferMeasurements,
    private val adaptive: Boolean,
) : DefaultLoadControl(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    buffer.minBufferMs, buffer.maxBufferMs, buffer.playbackBufferMs, buffer.rebufferMs,
    if (constrained) 24 * 1_048_576 else C.LENGTH_UNSET, false, 0, false) {
    private var loading = false
    override fun onPrepared(playerId: PlayerId) { super.onPrepared(playerId); loading = false }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val sample = measurements()
        val target = MeasuredBufferPolicy.target(constrained, false, sample)
        if (constrained || adaptive || sample.memoryPressure) {
            (allocator as DefaultAllocator).setTargetBufferSize(target.bytes)
            if (allocator.totalBytesAllocated >= target.bytes) { loading = false; return false }
        }
        if (!adaptive) return super.shouldContinueLoading(parameters)
        val duration = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        loading = when {
            duration < target.reserveMs * 500L -> true
            duration >= target.reserveMs * 1_000L -> false
            else -> loading
        }
        return loading
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (parameters.bufferedDurationUs <= 0) return false
        val sample = measurements()
        val target = MeasuredBufferPolicy.target(constrained, false, sample)
        if ((constrained || adaptive || sample.memoryPressure) && parameters.bufferedDurationUs > 0 &&
            allocator.totalBytesAllocated >= target.bytes) return true
        if (!adaptive || !parameters.rebuffering || sample.rebuffers == 0) return super.shouldStartPlayback(parameters)
        val duration = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return duration >= target.restartMs * 1_000L
    }
}
