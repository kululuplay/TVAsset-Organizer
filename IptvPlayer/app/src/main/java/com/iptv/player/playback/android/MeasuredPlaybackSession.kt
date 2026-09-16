package com.iptv.player.playback.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.iptv.player.playback.core.CodecQueueMode
import com.iptv.player.playback.core.MeasuredBufferPolicy
import com.iptv.player.playback.core.MeasuredBufferTarget
import com.iptv.player.util.PlaybackLog

/** Main-looper observations. It never seeks, restarts a player or overrides user pause. */
@OptIn(markerClass = [UnstableApi::class])
internal class MeasuredPlaybackSession(
    private val context: Context,
    private val player: ExoPlayer,
    private val buffer: MeasuredPlaybackBuffer,
    private val codecs: VerifiedCodecAdapterFactory,
    private val live: Boolean,
    private val verifiedOutput: () -> Boolean,
) : AnalyticsListener {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaId: String? = null
    private var played = false
    private var stallAt = 0L
    private var cooldownUntil = 0L
    private var windowAt = 0L
    private var renderedAt = 0
    private var droppedAt = 0
    private var lastPosition = -1L
    private var windowKey: String? = null
    private var trialCounted = false
    private var lastTarget: MeasuredBufferTarget? = null
    private val tick = object : Runnable {
        override fun run() {
            if (mediaId == null) return
            sample()
            handler.postDelayed(this, 2_000)
        }
    }

    init { player.addAnalyticsListener(this) }

    fun start(id: String) {
        stop()
        mediaId = id
        played = false
        trialCounted = false
        cooldownUntil = SystemClock.elapsedRealtime() + 10_000
        lastTarget = null
        buffer.reset()
        codecs.reset()
        handler.post(tick)
    }

    fun stop() {
        mediaId = null
        stallAt = 0
        windowAt = 0
        windowKey = null
        lastPosition = -1
        buffer.state(0, false)
        codecs.reset()
        handler.removeCallbacks(tick)
    }

    fun release() { stop(); player.removeAnalyticsListener(this) }

    fun videoFailure() {
        codecs.attempt?.let { VerifiedCodecStore.failed(it.key) }
        windowAt = 0
    }

    private fun current(event: AnalyticsListener.EventTime): Boolean {
        val id = mediaId ?: return false
        if (player.currentMediaItem?.mediaId != id) return false
        if (event.windowIndex !in 0 until event.timeline.windowCount) return false
        return event.timeline.getWindow(event.windowIndex, Timeline.Window()).mediaItem.mediaId == id
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (!current(eventTime)) return
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> {
                windowAt = 0
                if (played && player.playWhenReady && now >= cooldownUntil && stallAt == 0L) stallAt = now
            }
            Player.STATE_READY -> {
                if (stallAt > 0 && player.playWhenReady) buffer.rebuffer(now - stallAt)
                stallAt = 0
            }
            else -> { stallAt = 0; windowAt = 0 }
        }
        buffer.state(player.totalBufferedDuration, player.playWhenReady && now >= cooldownUntil && (played || state == Player.STATE_READY))
    }

    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        if (!current(eventTime) || playWhenReady) return
        stallAt = 0
        windowAt = 0
        lastPosition = -1
        buffer.discardSeekEvidence()
        buffer.state(0, false)
    }

    override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (!current(eventTime)) return
        cooldownUntil = SystemClock.elapsedRealtime() + 10_000
        windowAt = 0
        stallAt = 0
        lastPosition = -1
        buffer.discardSeekEvidence()
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        if (!current(eventTime)) return
        // Transport/HTTP, source and audio-output errors cannot condemn a video queue.
        if (error.errorCode in 4_000..4_999) codecs.attempt?.let { VerifiedCodecStore.failed(it.key) }
        windowAt = 0
        stallAt = 0
    }

    private fun sample() {
        if (player.currentMediaItem?.mediaId != mediaId) return
        PlaybackMemoryPressure.sample(context)
        val now = SystemClock.elapsedRealtime()
        val active = player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        buffer.state(player.totalBufferedDuration, active && now >= cooldownUntil)
        val snapshot = buffer.snapshot()
        val constrained = PlaybackQoeRuntime.devicePlaybackProfile().compatibilityMode
        val target = MeasuredBufferPolicy.target(constrained, live, snapshot)
        if (target != lastTarget) {
            val targetText = "reserve=${target.reserveMs} restart=${target.restartMs} bytes=${target.bytes} pressure=${snapshot.memoryPressure}"
            PlaybackLog.log(context, "MeasuredBuffer", targetText + " gap=${snapshot.networkGapMs} stall=${snapshot.rebufferMs}")
            lastTarget = target
        }
        if (player.isPlaying) played = true
        val attempt = codecs.attempt
        val format = player.videoFormat
        val counters = player.videoDecoderCounters
        val position = player.currentPosition
        val advances = lastPosition >= 0 && position > lastPosition && position - lastPosition < 5_000
        lastPosition = position
        if (!player.isPlaying || !advances || !verifiedOutput() || player.totalBufferedDuration < 1_000 ||
            snapshot.memoryPressure || now < cooldownUntil || attempt == null || counters == null || format == null ||
            attempt.formatKey != VerifiedCodecAdapterFactory.formatKey(format)) {
            windowAt = 0
            return
        }
        counters.ensureUpdated()
        if (windowAt == 0L || windowKey != attempt.key || counters.renderedOutputBufferCount < renderedAt ||
            counters.droppedBufferCount < droppedAt) {
            windowAt = now
            windowKey = attempt.key
            renderedAt = counters.renderedOutputBufferCount
            droppedAt = counters.droppedBufferCount
            return
        }
        if (now - windowAt < 60_000) return
        val rendered = (counters.renderedOutputBufferCount - renderedAt).toLong()
        val dropped = (counters.droppedBufferCount - droppedAt).toLong()
        VerifiedCodecStore.observe(attempt.key, attempt.mode, rendered, dropped, now - windowAt, !trialCounted)
        // An async candidate needs evidence from two distinct playback sessions.
        if (attempt.mode == CodecQueueMode.ASYNC && rendered >= 600) trialCounted = true
        PlaybackLog.log(context, "VerifiedCodec", "queue=${attempt.mode} rendered=$rendered dropped=$dropped windowMs=${now - windowAt}")
        windowAt = 0
    }
}
