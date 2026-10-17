package com.iptv.player.player

import java.util.ArrayDeque

/**
 * Real-time stutter detector (1.7.0, rule C.1): over a sliding window of
 * [windowMs] the share of dropped frames `dropped / (rendered + dropped)` may
 * not exceed [maxRatio] while the buffer is healthy. Fed with cumulative
 * decoder counters (Media3 `DecoderCounters`), so it needs no per-frame
 * callbacks. Startup and every rebuffer clear the window: frames dropped while
 * the renderer catches up after a network stall are not decoder evidence.
 *
 * Trips once per stream. The engine then reports an invalid-video verdict and
 * the controller moves to the other hardware engine.
 */
internal class FrameDropRatioGate(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val startupGraceMs: Long = DEFAULT_STARTUP_GRACE_MS,
    private val minFrames: Int = DEFAULT_MIN_FRAMES,
    private val maxRatio: Double = DEFAULT_MAX_RATIO,
) {
    data class Breach(val dropped: Long, val rendered: Long, val windowMs: Long) {
        val ratio: Double get() = if (dropped + rendered > 0) dropped.toDouble() / (dropped + rendered) else 0.0
    }

    private class Sample(val atMs: Long, val rendered: Long, val dropped: Long)

    private val samples = ArrayDeque<Sample>()
    private var graceUntilMs = Long.MAX_VALUE
    private var tripped = false

    /** Arms the gate: samples before the first frame plus [startupGraceMs] are ignored. */
    fun onFirstFrame(nowMs: Long) {
        graceUntilMs = nowMs + startupGraceMs
        samples.clear()
        tripped = false
    }

    /** A rebuffer: the catch-up drops that follow are network, not decoder, evidence. */
    fun onBuffering() {
        samples.clear()
    }

    fun reset() {
        samples.clear()
        graceUntilMs = Long.MAX_VALUE
        tripped = false
    }

    /**
     * [rendered] and [dropped] are cumulative counters for the current decoder.
     * Returns the breach once when the window is full and the ratio exceeded.
     */
    fun sample(nowMs: Long, rendered: Long, dropped: Long, buffering: Boolean): Breach? {
        if (tripped) return null
        if (buffering) {
            samples.clear()
            return null
        }
        if (nowMs < graceUntilMs) return null
        samples.addLast(Sample(nowMs, rendered, dropped))
        // Keep the oldest sample that still bounds a full window.
        while (samples.size > 1) {
            val second = samples.elementAt(1)
            if (second.atMs <= nowMs - windowMs) samples.removeFirst() else break
        }
        val first = samples.peekFirst() ?: return null
        val last = samples.peekLast() ?: return null
        if (last.atMs - first.atMs < windowMs) return null
        val renderedDelta = last.rendered - first.rendered
        val droppedDelta = last.dropped - first.dropped
        if (renderedDelta < 0L || droppedDelta < 0L) {
            // Counters restarted (new decoder instance): rebuild the window.
            samples.clear()
            samples.addLast(last)
            return null
        }
        val total = renderedDelta + droppedDelta
        if (total < minFrames) return null
        if (droppedDelta.toDouble() / total.toDouble() > maxRatio) {
            tripped = true
            return Breach(droppedDelta, renderedDelta, last.atMs - first.atMs)
        }
        return null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5_000L
        const val DEFAULT_STARTUP_GRACE_MS = 3_000L
        /** Too few frames in the window (a 25 fps stream yields ~125) say nothing. */
        const val DEFAULT_MIN_FRAMES = 40
        const val DEFAULT_MAX_RATIO = 0.25
    }
}
