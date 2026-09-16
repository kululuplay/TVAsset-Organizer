package com.iptv.player.playback.android

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.iptv.player.playback.core.BufferMeasurementWindow
import com.iptv.player.playback.core.BufferMeasurements
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** No player, URL or credential is read from the network callback thread. */
@OptIn(markerClass = [UnstableApi::class])
internal class MeasuredPlaybackBuffer(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val pressure: () -> Boolean = PlaybackMemoryPressure::pressured,
) {
    private val window = BufferMeasurementWindow()
    @Volatile private var starvingUntilMs = 0L
    @Volatile private var epoch = 0L
    @Volatile private var observationEpoch = 0L

    fun reset() { epoch++; starvingUntilMs = 0; window.reset() }
    fun discardSeekEvidence() { observationEpoch++; starvingUntilMs = 0; window.reset() }
    fun state(bufferedMs: Long, active: Boolean) {
        starvingUntilMs = if (active && bufferedMs < 1_500) clock() + 6_000 else 0
    }
    fun rebuffer(durationMs: Long) = window.rebuffer(durationMs)
    fun snapshot(): BufferMeasurements = window.snapshot(clock(), pressure())

    fun wrap(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        factory.createDataSource().apply {
            addTransferListener(object : TransferListener {
                private var lastByteAt = 0L
                private var transferEpoch = -1L
                private var lastByteStarving = false
                private var lastObservationEpoch = 0L
                override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) = Unit
                override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) {
                    transferEpoch = epoch
                    lastByteAt = 0
                    lastByteStarving = false
                    lastObservationEpoch = observationEpoch
                }
                override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, bytes: Int) {
                    if (!network || bytes <= 0 || transferEpoch != epoch) return
                    // Pauses/seeks can reuse this connection. Do not measure the
                    // time across that boundary as an inter-byte network gap.
                    if (lastObservationEpoch != observationEpoch) {
                        lastByteAt = 0
                        lastByteStarving = false
                        lastObservationEpoch = observationEpoch
                    }
                    val now = clock()
                    val starving = now <= starvingUntilMs
                    if (lastByteAt > 0 && now - lastByteAt >= 100) window.networkGap(now, now - lastByteAt, starving && lastByteStarving)
                    lastByteAt = now
                    lastByteStarving = starving
                }
                override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) { lastByteAt = 0 }
            })
        }
    }
}

/** One throttled background sample shared by all players; never scans codecs or calls GC. */
internal object PlaybackMemoryPressure {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "playback-memory").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    @Volatile private var low = false
    @Volatile private var sampledAt = 0L

    fun sample(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - sampledAt < 5_000 || !pending.compareAndSet(false, true)) return
        val app = context.applicationContext
        runCatching {
            worker.execute {
                try {
                    runCatching {
                        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                        val info = ActivityManager.MemoryInfo()
                        manager?.getMemoryInfo(info)
                        val runtime = Runtime.getRuntime()
                        val headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                        low = info.lowMemory || (info.availMem > 0 && info.availMem <= info.threshold * 3 / 2) ||
                            headroom < 24L * 1_048_576
                        sampledAt = SystemClock.elapsedRealtime()
                    }
                } finally { pending.set(false) }
            }
        }.onFailure { pending.set(false) }
    }

    fun pressured(): Boolean = low && SystemClock.elapsedRealtime() - sampledAt < 15_000
}
