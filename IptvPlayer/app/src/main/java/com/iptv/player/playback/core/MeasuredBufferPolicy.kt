package com.iptv.player.playback.core

/** Measurements are session-local: startup, pauses and seeks are not network stalls. */
data class BufferMeasurements(
    val networkGapMs: Long = 0,
    val rebufferMs: Long = 0,
    val rebuffers: Int = 0,
    val memoryPressure: Boolean = false,
)

data class MeasuredBufferTarget(val reserveMs: Int, val restartMs: Int, val bytes: Int)

object MeasuredBufferPolicy {
    private const val MIB = 1_048_576

    /** libVLC cache options are immutable per media. Apply on natural open/recovery only. */
    fun nativeCacheMs(configuredMs: Int, adaptive: Boolean, constrained: Boolean, live: Boolean,
        sample: BufferMeasurements): Int {
        if (!adaptive) return configuredMs
        if (sample.memoryPressure) return 1_500
        val baseline = if (live && constrained) 2_500 else 1_500
        val measured = maxOf(sample.networkGapMs.coerceIn(0, 8_000) * 3 / 2 + 500,
            sample.rebufferMs.coerceIn(0, 8_000) + 500)
        return maxOf(baseline.toLong(), measured).coerceIn(750, 3_000).toInt()
    }

    fun target(constrained: Boolean, live: Boolean, sample: BufferMeasurements): MeasuredBufferTarget {
        val baseRestart = if (constrained) 2_500 else 1_000
        val gap = sample.networkGapMs.coerceIn(0, 8_000)
        val stall = sample.rebufferMs.coerceIn(0, 8_000)
        val measuredRestart = maxOf(gap * 3 / 2 + 500, stall + 500)
        val restart = maxOf(baseRestart.toLong(), measuredRestart)
            .coerceAtMost(if (constrained) 4_000L else 6_000L).toInt()
        val reserve = maxOf(if (constrained) 6_000 else 8_000, restart * 3)
            .coerceAtMost(if (live) { if (constrained) 12_000 else 20_000 } else 30_000)
        // Sample memory only. Decoder surfaces/native allocations are separate.
        val mib = when {
            sample.memoryPressure -> if (constrained) 8 else 16
            constrained -> 24
            else -> 48
        }
        return MeasuredBufferTarget(reserve, restart, mib * MIB)
    }
}

/** A bounded window, fed only by actual media-state transitions and active transfers. */
class BufferMeasurementWindow {
    private var lastGapAt = Long.MIN_VALUE
    private var gap = 0L
    private var stall = 0L
    private var count = 0

    @Synchronized fun reset() { lastGapAt = Long.MIN_VALUE; gap = 0; stall = 0; count = 0 }

    @Synchronized fun networkGap(nowMs: Long, gapMs: Long, starving: Boolean) {
        if (!starving || gapMs !in 100..15_000) return
        if (lastGapAt == Long.MIN_VALUE || nowMs - lastGapAt > 60_000) gap = 0
        gap = maxOf(gap, gapMs.coerceAtMost(8_000))
        lastGapAt = nowMs
    }

    @Synchronized fun rebuffer(durationMs: Long) {
        if (durationMs !in 100..120_000) return
        val bounded = durationMs.coerceAtMost(8_000)
        stall = if (count == 0) bounded else (stall * 3 + bounded) / 4
        count = (count + 1).coerceAtMost(100)
    }

    @Synchronized fun snapshot(nowMs: Long, pressure: Boolean): BufferMeasurements =
        BufferMeasurements(
            networkGapMs = if (lastGapAt != Long.MIN_VALUE && nowMs - lastGapAt in 0..60_000) gap else 0,
            rebufferMs = stall,
            rebuffers = count,
            memoryPressure = pressure,
        )
}
