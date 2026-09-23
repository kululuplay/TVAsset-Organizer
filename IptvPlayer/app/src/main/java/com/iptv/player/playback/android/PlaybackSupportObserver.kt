package com.iptv.player.playback.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.iptv.player.playback.core.PlaybackObservation
import com.iptv.player.playback.core.PlaybackStartupClock
import com.iptv.player.playback.core.PlaybackVideoCodec
import com.iptv.player.playback.core.PlaybackVideoDecoder
import com.iptv.player.playback.core.PlaybackVideoFormat

/** Observes existing requests and codec selections; never changes their policies or starts probes. */
@OptIn(markerClass = [UnstableApi::class])
internal class PlaybackSupportEvidence(clock: () -> Long = SystemClock::elapsedRealtime) {
    val startup = PlaybackStartupClock(clock)
    private val codecKinds = LinkedHashMap<String, PlaybackVideoDecoder>()
    @Synchronized fun remember(infos: List<MediaCodecInfo>) {
        infos.forEach { info -> codecKinds[info.name] = when {
            info.softwareOnly -> PlaybackVideoDecoder.SOFTWARE
            info.hardwareAccelerated -> PlaybackVideoDecoder.HARDWARE
            else -> PlaybackVideoDecoder.UNKNOWN
        } }
        while (codecKinds.size > 128) codecKinds.remove(codecKinds.keys.first())
    }
    @Synchronized fun decoder(name: String) = codecKinds[name] ?: PlaybackVideoDecoder.UNKNOWN
    fun wrap(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val owner = startup.generation()
        factory.createDataSource().apply {
            addTransferListener(object : TransferListener {
                private var token: Long? = null
                override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) {
                    token = startup.initializing(owner, network)
                }
                override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) {
                    if (network) startup.opened(owner, token)
                }
                override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, bytes: Int) {
                    if (network) startup.bytes(owner, token, bytes)
                }
                override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            })
        }
    }
}

/** Two-second lightweight Media3 counters. Native VLC keeps its existing health sampler. */
@OptIn(markerClass = [UnstableApi::class])
internal class PlaybackSupportObserver(
    private val player: ExoPlayer,
    private val evidence: PlaybackSupportEvidence,
) : AnalyticsListener {
    var onObservation: ((PlaybackObservation) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var mediaId: String? = null
    private var decoder = PlaybackVideoDecoder.UNKNOWN
    private val tick = object : Runnable {
        override fun run() {
            if (mediaId == null) return
            sample()
            handler.postDelayed(this, 2_000)
        }
    }
    init { player.addAnalyticsListener(this) }
    fun start(id: String) {
        stop(); mediaId = id; evidence.startup.reset(); handler.post(tick)
    }
    fun stop() {
        mediaId = null; decoder = PlaybackVideoDecoder.UNKNOWN
        evidence.startup.stop(); handler.removeCallbacks(tick)
    }
    fun release() { stop(); player.removeAnalyticsListener(this) }
    private fun current(event: AnalyticsListener.EventTime): Boolean {
        val id = mediaId ?: return false
        return player.currentMediaItem?.mediaId == id &&
            event.windowIndex in 0 until event.timeline.windowCount &&
            event.timeline.getWindow(event.windowIndex, Timeline.Window()).mediaItem.mediaId == id
    }
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) {
        if (current(eventTime)) decoder = evidence.decoder(decoderName)
    }
    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?) {
        if (current(eventTime)) decoder = decoderReuseEvaluation?.decoderName?.let(evidence::decoder)
            ?: PlaybackVideoDecoder.UNKNOWN
    }
    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (current(eventTime)) evidence.startup.firstFrame(renderTimeMs)
    }
    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        if (current(eventTime) && !playWhenReady) evidence.startup.interruptBeforeFirstFrame()
    }
    override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo, reason: Int) {
        if (current(eventTime) && (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)) {
            evidence.startup.interruptBeforeFirstFrame()
        }
    }
    private fun sample() {
        if (player.currentMediaItem?.mediaId != mediaId) return
        val format = player.videoFormat
        val counters = player.videoDecoderCounters?.takeIf { format != null }
        counters?.ensureUpdated()
        onObservation?.invoke(PlaybackObservation(
            source = mediaId.orEmpty(), rendered = counters?.renderedOutputBufferCount?.toLong(),
            dropped = counters?.droppedBufferCount?.toLong(), bufferMs = player.totalBufferedDuration,
            paused = !player.playWhenReady, startup = evidence.startup.snapshot(),
            video = format?.let { PlaybackVideoFormat(PlaybackVideoCodec.from(it.sampleMimeType), decoder,
                it.width, it.height, it.frameRate) },
        ))
    }
}
