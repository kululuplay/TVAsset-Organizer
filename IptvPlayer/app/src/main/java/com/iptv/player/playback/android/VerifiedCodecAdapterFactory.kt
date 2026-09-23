package com.iptv.player.playback.android

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import com.iptv.player.playback.core.CodecQueueMode
import com.iptv.player.playback.core.VerifiedCodecPolicy
import com.iptv.player.playback.core.PlaybackVideoDecoder
import java.util.concurrent.atomic.AtomicLong

/** Selects a queue for an exact firmware/codec/format, never for an entire brand. */
@OptIn(markerClass = [UnstableApi::class])
internal class VerifiedCodecAdapterFactory(private val context: Context) : MediaCodecAdapter.Factory {
    data class Attempt(val epoch: Long, val key: String, val formatKey: String, val mode: CodecQueueMode)
    private val epoch = AtomicLong()
    @Volatile var attempt: Attempt? = null
        private set
    @Volatile var videoDecoder: PlaybackVideoDecoder = PlaybackVideoDecoder.UNKNOWN
        private set

    init { VerifiedCodecStore.init(context) }
    fun reset() { epoch.incrementAndGet(); attempt = null; videoDecoder = PlaybackVideoDecoder.UNKNOWN }

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val token = epoch.get()
        val codec = configuration.codecInfo
        val format = configuration.format
        val video = format.sampleMimeType?.startsWith("video/") == true
        val decoder = when {
            codec.hardwareAccelerated -> PlaybackVideoDecoder.HARDWARE
            codec.softwareOnly -> PlaybackVideoDecoder.SOFTWARE
            else -> PlaybackVideoDecoder.UNKNOWN
        }
        if (video && token == epoch.get()) {
            attempt = null
            videoDecoder = PlaybackVideoDecoder.UNKNOWN
        }
        val eligible = format.sampleMimeType?.startsWith("video/") == true && codec.hardwareAccelerated &&
            !codec.secure && !codec.tunneling && format.drmInitData == null && Build.VERSION.SDK_INT in 23..30
        if (!eligible) return DefaultMediaCodecAdapterFactory(context).createAdapter(configuration).also {
            if (video && token == epoch.get()) videoDecoder = decoder
        }
        val formatKey = formatKey(format)
        val key = VerifiedCodecStore.hash("${VerifiedCodecStore.deviceScope}|${codec.name}|$formatKey")
        val mode = VerifiedCodecPolicy.select(Build.VERSION.SDK_INT, true, false,
            VerifiedCodecStore.evidence(key), System.currentTimeMillis())
        var actualMode = mode
        val adapter = try {
            DefaultMediaCodecAdapterFactory(context).apply {
                if (mode == CodecQueueMode.ASYNC) forceEnableAsynchronous()
            }.createAdapter(configuration)
        } catch (failure: Exception) {
            if (mode != CodecQueueMode.ASYNC) throw failure
            VerifiedCodecStore.failed(key)
            actualMode = CodecQueueMode.DEFAULT
            // Retry once with the platform-default queue. Do not reconnect media
            // or silently change the video codec because a queue could not start.
            DefaultMediaCodecAdapterFactory(context).createAdapter(configuration)
        }
        if (token == epoch.get()) {
            attempt = Attempt(token, key, formatKey, actualMode)
            videoDecoder = decoder
        }
        return adapter
    }

    companion object {
        fun formatKey(format: Format): String = listOf(format.sampleMimeType, format.codecs,
            format.width, format.height, format.frameRate.toInt(), format.colorInfo?.colorTransfer,
            format.colorInfo?.colorSpace, format.colorInfo?.lumaBitdepth).joinToString("|")
    }
}
