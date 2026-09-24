package com.iptv.player.player

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.nio.ByteBuffer

/**
 * Diagnostics-only video renderer (LIVE_PLAYBACK_DIAGNOSTICS builds): once a
 * second it reports what the platform video path is doing between the codec
 * and the surface, so a frozen picture can be attributed to missing input,
 * missing decoder output, or output buffers that are held/skipped/dropped.
 * Never part of a normal build and never feeds playback policy.
 */
@UnstableApi
internal class DiagnosticVideoRenderer(
    context: Context,
    selector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedFramesToNotify: Int,
    private val log: (String) -> Unit,
) : MediaCodecVideoRenderer(
    context,
    selector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    private var lastLogMs = 0L
    private var inputCount = 0
    private var lastInputPtsUs = Long.MIN_VALUE
    private var outputCalls = 0
    private var outputProcessed = 0
    private var lastOutputPtsUs = Long.MIN_VALUE
    private var lastOutputPositionUs = Long.MIN_VALUE
    private var lastOutputFlags = 0
    private var lastDecodeOnly = false
    private var dropDecisions = 0
    private var forceDecisions = 0

    private val inputLog = StringBuilder()

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        inputCount++
        lastInputPtsUs = buffer.timeUs
        // First 150 buffers in full, then one in fifty: index:ptsMs/bytes[K][S]
        if (inputCount <= 150 || inputCount % 50 == 0) {
            val data = buffer.data
            inputLog.append(inputCount).append(':').append(buffer.timeUs / 1000)
                .append('/').append(data?.limit() ?: -1).append('@').append(data?.position() ?: -1)
            if (buffer.isKeyFrame) inputLog.append('K')
            if (buffer.hasSupplementalData()) inputLog.append('S')
            data?.let { inputLog.append('[').append(nalTypes(it)).append(']') }
            inputLog.append(' ')
            if (inputCount % 10 == 0 || inputCount > 150) {
                log("videoIn $inputLog")
                inputLog.setLength(0)
            }
        }
    }

    /** NAL unit types found behind start codes, in order (H.264: 9=AUD 7=SPS 8=PPS 6=SEI 5=IDR 1=slice). */
    private fun nalTypes(data: ByteBuffer): String {
        val sb = StringBuilder()
        val limit = data.limit()
        var i = data.position()
        var count = 0
        while (i + 3 < limit && count < 12) {
            if (data.get(i).toInt() == 0 && data.get(i + 1).toInt() == 0 && data.get(i + 2).toInt() == 1) {
                val type = data.get(i + 3).toInt() and 0x1F
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(type)
                count++
                i += 4
            } else {
                i++
            }
        }
        return sb.toString()
    }

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format,
    ): Boolean {
        outputCalls++
        lastOutputPtsUs = bufferPresentationTimeUs
        lastOutputPositionUs = positionUs
        lastOutputFlags = bufferFlags
        lastDecodeOnly = isDecodeOnlyBuffer
        val processed = super.processOutputBuffer(
            positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags,
            sampleCount, bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format,
        )
        if (processed) outputProcessed++
        return processed
    }

    override fun shouldDropOutputBuffer(
        earlyUs: Long,
        elapsedRealtimeUs: Long,
        isLastBuffer: Boolean,
    ): Boolean = super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastBuffer)
        .also { if (it) dropDecisions++ }

    override fun shouldForceRenderOutputBuffer(
        earlyUs: Long,
        elapsedSinceLastRenderUs: Long,
    ): Boolean = super.shouldForceRenderOutputBuffer(earlyUs, elapsedSinceLastRenderUs)
        .also { if (it) forceDecisions++ }

    // Experiment: Media3 1.11 derives a runtime operating rate from its frame
    // rate estimator when the stream format carries no frame rate (live TS) and
    // pushes it with codec.setParameters(); 1.8.1 never did. Suppress it.
    override fun getCodecOperatingRateV23(
        targetPlaybackSpeed: Float,
        format: Format,
        streamFormats: Array<Format>,
    ): Float {
        val rate = super.getCodecOperatingRateV23(targetPlaybackSpeed, format, streamFormats)
        val known = streamFormats.any { it.frameRate != Format.NO_VALUE.toFloat() }
        if (!known && rate != CODEC_OPERATING_RATE_UNSET) {
            log("videoDiag operatingRate suppressed estimated=$rate")
            return CODEC_OPERATING_RATE_UNSET
        }
        return rate
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs, elapsedRealtimeUs)
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogMs < 1000L) return
        lastLogMs = now
        val c = decoderCounters
        log(
            "videoDiag pos=${positionUs / 1000} ready=$isReady ended=$isEnded " +
                "in=$inputCount lastInPts=${lastInputPtsUs / 1000} " +
                "outCalls=$outputCalls outProcessed=$outputProcessed " +
                "lastOutPts=${lastOutputPtsUs / 1000} lastOutPos=${lastOutputPositionUs / 1000} " +
                "earlyMs=${(lastOutputPtsUs - lastOutputPositionUs) / 1000} " +
                "decodeOnly=$lastDecodeOnly flags=$lastOutputFlags " +
                "drops=$dropDecisions forces=$forceDecisions " +
                "counters[queuedIn=${c.queuedInputBufferCount} skippedIn=${c.skippedInputBufferCount} " +
                "rendered=${c.renderedOutputBufferCount} skipped=${c.skippedOutputBufferCount} " +
                "dropped=${c.droppedBufferCount} droppedIn=${c.droppedInputBufferCount} " +
                "toKeyframe=${c.droppedToKeyframeCount} " +
                "maxConsecDropped=${c.maxConsecutiveDroppedBufferCount}]",
        )
        outputCalls = 0
        outputProcessed = 0
    }
}
