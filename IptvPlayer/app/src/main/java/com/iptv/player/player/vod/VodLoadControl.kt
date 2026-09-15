package com.iptv.player.player.vod

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/** Movie/episode sample buffering, including the compatibility budget for old TVs. */
@OptIn(markerClass = [UnstableApi::class])
internal object VodLoadControl {
    fun create(buffer: VodBufferConfig, constrainedDevice: Boolean): DefaultLoadControl =
        DefaultLoadControl.Builder()
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
