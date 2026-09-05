package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** Transparent sink wrapper: observe playout, never alter PCM, timing or output. */
@OptIn(markerClass = [UnstableApi::class])
internal class MonitoredAudioSink(
    sink: AudioSink,
    private val monitor: AudioUnderrunMonitor,
    private val nowMs: () -> Long,
) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        monitor.onPosition(position.takeIf { it != AudioSink.CURRENT_POSITION_NOT_SET }, nowMs())
        return position
    }

    override fun flush() {
        monitor.reset()
        super.flush()
    }

    override fun handleDiscontinuity() {
        monitor.reset()
        super.handleDiscontinuity()
    }

    override fun pause() {
        monitor.reset()
        super.pause()
    }

    override fun reset() {
        monitor.reset()
        super.reset()
    }

    override fun release() {
        monitor.reset()
        super.release()
    }
}
