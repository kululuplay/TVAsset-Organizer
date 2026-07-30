package com.iptv.player.player

import java.util.ArrayDeque

/**
 * Detects sustained, user-visible frame loss without overreacting to a handful
 * of frames dropped while a live decoder is warming up.
 *
 * Media3 reports dropped frames in batches. A decoder may still advance its
 * clock and render occasional frames while dropping enough output to look frozen
 * or badly pixelated, so the ordinary "first frame" and stall watchdogs cannot
 * classify this failure. This rolling gate supplies that missing quality signal.
 */
internal class DroppedFrameRecoveryGate(
    private val startupGraceMs: Long = DEFAULT_STARTUP_GRACE_MS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val thresholdFrames: Int = DEFAULT_THRESHOLD_FRAMES,
) {
    data class Breach(
        val droppedFrames: Int,
        val windowMs: Long,
    )

    private data class Sample(
        val atMs: Long,
        val droppedFrames: Int,
    )

    private val samples = ArrayDeque<Sample>()
    private var firstFrameAtMs = UNSET
    private var tripped = false
    private var rollingDroppedFrames = 0

    fun onFirstFrame(nowMs: Long) {
        firstFrameAtMs = nowMs
        samples.clear()
        rollingDroppedFrames = 0
        tripped = false
    }

    /**
     * Returns a single [Breach] when the rolling frame-loss threshold is crossed.
     * Further samples stay latched until [reset] or a new [onFirstFrame].
     */
    fun onDroppedFrames(nowMs: Long, droppedFrames: Int): Breach? {
        if (
            tripped ||
            firstFrameAtMs == UNSET ||
            droppedFrames <= 0 ||
            nowMs - firstFrameAtMs < startupGraceMs
        ) {
            return null
        }

        samples.addLast(Sample(nowMs, droppedFrames))
        rollingDroppedFrames += droppedFrames
        val oldestAllowedMs = nowMs - windowMs
        while (samples.isNotEmpty() && samples.peekFirst().atMs < oldestAllowedMs) {
            rollingDroppedFrames -= samples.removeFirst().droppedFrames
        }

        if (rollingDroppedFrames < thresholdFrames) return null
        tripped = true
        return Breach(rollingDroppedFrames, windowMs)
    }

    fun reset() {
        samples.clear()
        firstFrameAtMs = UNSET
        rollingDroppedFrames = 0
        tripped = false
    }

    private companion object {
        private const val UNSET = Long.MIN_VALUE
        private const val DEFAULT_STARTUP_GRACE_MS = 3_000L
        private const val DEFAULT_WINDOW_MS = 2_000L
        private const val DEFAULT_THRESHOLD_FRAMES = 12
    }
}
