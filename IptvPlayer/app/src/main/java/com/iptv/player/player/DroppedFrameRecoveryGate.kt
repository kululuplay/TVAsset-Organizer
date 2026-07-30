package com.iptv.player.player

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
    private val confirmationMs: Long = DEFAULT_CONFIRMATION_MS,
) {
    data class Breach(
        val droppedFrames: Int,
        val windowMs: Long,
    )

    private data class Sample(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val droppedFrames: Int,
    )

    private val samples = ArrayDeque<Sample>()
    private var firstFrameAtMs = UNSET
    private var graceUntilMs = UNSET
    private var firstSevereWindowAtMs = UNSET
    private var tripped = false

    fun onFirstFrame(nowMs: Long) {
        firstFrameAtMs = nowMs
        graceUntilMs = nowMs + startupGraceMs
        clearEvidence()
        tripped = false
    }

    /**
     * Re-arms startup grace when the renderer moves to a materially different
     * output surface (for example preview -> fullscreen). Media3 can include the
     * frames discarded during that hand-off in its next cumulative callback.
     */
    fun onOutputTransition(nowMs: Long) {
        if (firstFrameAtMs == UNSET || tripped) return
        graceUntilMs = nowMs + startupGraceMs
        clearEvidence()
    }

    /**
     * Returns a single [Breach] after frame loss remains severe across two
     * observations separated by [confirmationMs]. Media3 reports cumulative
     * batches, so [elapsedMs] is required to attribute only the part that falls
     * inside the rolling window.
     */
    fun onDroppedFrames(
        nowMs: Long,
        droppedFrames: Int,
        elapsedMs: Long,
    ): Breach? {
        if (
            tripped ||
            firstFrameAtMs == UNSET ||
            droppedFrames <= 0 ||
            elapsedMs <= 0
        ) {
            return null
        }

        val startedAtMs = nowMs - elapsedMs
        // Never charge startup or a Surface hand-off to steady-state playback.
        // The whole callback is ignored when its accounting interval crosses the
        // grace boundary because Media3 does not expose per-frame timestamps.
        if (nowMs < graceUntilMs || startedAtMs < graceUntilMs) {
            clearEvidence()
            return null
        }

        val newestSample = Sample(
            startedAtMs = startedAtMs,
            endedAtMs = nowMs,
            droppedFrames = droppedFrames,
        )
        samples.addLast(newestSample)
        val oldestAllowedMs = nowMs - windowMs
        while (samples.isNotEmpty() && samples.peekFirst().endedAtMs <= oldestAllowedMs) {
            samples.removeFirst()
        }

        val rollingDroppedFrames = estimateDropsInWindow(
            windowStartedAtMs = oldestAllowedMs,
            windowEndedAtMs = nowMs,
        )
        if (rollingDroppedFrames < thresholdFrames) {
            firstSevereWindowAtMs = UNSET
            return null
        }
        if (firstSevereWindowAtMs == UNSET) {
            firstSevereWindowAtMs = nowMs
            return null
        }
        if (nowMs - firstSevereWindowAtMs < confirmationMs) return null
        // The confirmation must contain severe evidence of its own. Otherwise a
        // tiny later callback could re-count the first burst while it is still in
        // the rolling window and incorrectly look like sustained loss.
        val newestSampleDropsInWindow = estimateSampleDropsInWindow(
            sample = newestSample,
            windowStartedAtMs = oldestAllowedMs,
            windowEndedAtMs = nowMs,
        )
        if (newestSampleDropsInWindow < thresholdFrames) return null

        tripped = true
        return Breach(rollingDroppedFrames, windowMs)
    }

    fun reset() {
        clearEvidence()
        firstFrameAtMs = UNSET
        graceUntilMs = UNSET
        tripped = false
    }

    private fun estimateDropsInWindow(
        windowStartedAtMs: Long,
        windowEndedAtMs: Long,
    ): Int {
        var estimatedDrops = 0.0
        samples.forEach { sample ->
            estimatedDrops += estimateSampleDrops(
                sample = sample,
                windowStartedAtMs = windowStartedAtMs,
                windowEndedAtMs = windowEndedAtMs,
            )
        }
        return ceil(estimatedDrops).toInt()
    }

    private fun estimateSampleDropsInWindow(
        sample: Sample,
        windowStartedAtMs: Long,
        windowEndedAtMs: Long,
    ): Int = ceil(
        estimateSampleDrops(
            sample = sample,
            windowStartedAtMs = windowStartedAtMs,
            windowEndedAtMs = windowEndedAtMs,
        ),
    ).toInt()

    private fun estimateSampleDrops(
        sample: Sample,
        windowStartedAtMs: Long,
        windowEndedAtMs: Long,
    ): Double {
        val sampleDurationMs = (sample.endedAtMs - sample.startedAtMs).coerceAtLeast(1L)
        val overlapStartedAtMs = max(sample.startedAtMs, windowStartedAtMs)
        val overlapEndedAtMs = min(sample.endedAtMs, windowEndedAtMs)
        val overlapMs = (overlapEndedAtMs - overlapStartedAtMs).coerceAtLeast(0L)
        return sample.droppedFrames.toDouble() * overlapMs.toDouble() /
            sampleDurationMs.toDouble()
    }

    private fun clearEvidence() {
        samples.clear()
        firstSevereWindowAtMs = UNSET
    }

    private companion object {
        private const val UNSET = Long.MIN_VALUE
        private const val DEFAULT_STARTUP_GRACE_MS = 3_000L
        private const val DEFAULT_WINDOW_MS = 2_000L
        private const val DEFAULT_THRESHOLD_FRAMES = 12
        private const val DEFAULT_CONFIRMATION_MS = 1_000L
    }
}
