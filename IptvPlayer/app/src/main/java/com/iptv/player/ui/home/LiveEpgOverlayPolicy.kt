package com.iptv.player.ui.home

import com.iptv.player.data.model.Program
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pure now/next projection used by the fullscreen Live TV overlay.
 *
 * Keeping time selection outside the Activity makes programme rollovers and
 * remaining-time calculations deterministic and JVM-testable.
 */
internal object LiveEpgOverlayPolicy {

    data class Snapshot(
        val current: Program?,
        val next: Program?,
        val progressPercent: Int,
        val remainingMinutes: Int?,
    )

    fun resolve(programs: List<Program>, nowMs: Long): Snapshot {
        // Single O(n) scan: this runs once per second in fullscreen, so avoid
        // sorting/allocating the full guide on low-powered TV sticks. Selecting
        // the earliest candidate also makes the result independent of DAO order.
        var current: Program? = null
        var next: Program? = null
        for (program in programs) {
            if (program.stopMs <= program.startMs) continue
            val active = current
            if (
                program.isLiveAt(nowMs) &&
                (active == null || program.startMs < active.startMs)
            ) {
                current = program
            }
            val upcoming = next
            if (
                program.startMs > nowMs &&
                (upcoming == null || program.startMs < upcoming.startMs)
            ) {
                next = program
            }
        }
        val remainingMinutes = current?.let {
            ceil((it.stopMs - nowMs).coerceAtLeast(0L) / 60_000.0)
                .toInt()
        }
        return Snapshot(
            current = current,
            next = next,
            progressPercent = current
                ?.progressAt(nowMs)
                ?.times(100f)
                ?.roundToInt()
                ?.coerceIn(0, 100)
                ?: 0,
            remainingMinutes = remainingMinutes,
        )
    }
}
