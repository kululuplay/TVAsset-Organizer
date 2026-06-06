/*
 * PlayerTestDoubles.kt
 * Shared fakes for the connection-lifecycle tests. None of these touch real
 * Android or native player APIs, so they run as plain JVM unit tests.
 */
package com.iptv.player.player

import android.view.ViewGroup

/**
 * Counts how many stream connections are open across *all* engines at once and
 * remembers the peak. The single-connection contract is proven by asserting the
 * peak never exceeds 1.
 */
class ConnectionTracker {
    var currentlyOpen = 0
        private set
    var peakOpen = 0
        private set

    fun openOne() {
        currentlyOpen++
        if (currentlyOpen > peakOpen) peakOpen = currentlyOpen
    }

    fun closeOne() {
        if (currentlyOpen > 0) currentlyOpen--
    }
}

/**
 * A PlayerEngine that records every lifecycle call into a shared [log] and
 * tracks its own open/closed connection state in [tracker]. It mirrors the real
 * engines' single-connection contract: play() fully stops any prior stream
 * before opening the next, so a correctly-behaving controller can never make the
 * tracker peak exceed 1.
 */
class RecordingPlayerEngine(
    override val engineName: String,
    private val tracker: ConnectionTracker,
    private val log: MutableList<String>
) : PlayerEngine {

    var listener: PlayerListener? = null
        private set

    /** True while this engine holds an open stream connection. */
    var open = false
        private set

    override fun bind(container: ViewGroup) {
        log.add("$engineName:bind")
    }

    override fun play(url: String) {
        // Single-connection contract: close the prior stream before opening the
        // next (this is what VlcPlayerEngine.startMedia does via mp.stop()).
        if (open) {
            open = false
            tracker.closeOne()
            log.add("$engineName:stop(implicit)")
        }
        open = true
        tracker.openOne()
        log.add("$engineName:play:$url")
    }

    override fun pause() {
        log.add("$engineName:pause")
    }

    override fun resume() {
        log.add("$engineName:resume")
    }

    override fun stop() {
        if (open) {
            open = false
            tracker.closeOne()
        }
        log.add("$engineName:stop")
    }

    override fun release() {
        if (open) {
            open = false
            tracker.closeOne()
        }
        log.add("$engineName:release")
    }

    override fun setListener(listener: PlayerListener?) {
        this.listener = listener
    }

    // --- Test drivers: simulate engine callbacks the controller reacts to. ---
    fun emitError() = listener?.onError("test-error")
    fun emitPlaying() = listener?.onPlaying()
    fun emitBuffering() = listener?.onBuffering()
}

/**
 * Deterministic scheduler: runs main-thread work inline and queues delayed
 * (retry) tasks so a test can fire them on demand instead of waiting on a real
 * Looper.
 */
class FakeScheduler : PlayerScheduler {
    private data class Task(val delayMs: Long, val action: () -> Unit)

    private val pending = ArrayDeque<Task>()

    var cancelCount = 0
        private set

    val pendingCount: Int get() = pending.size

    override fun runOnMain(action: () -> Unit) = action()

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        pending.addLast(Task(delayMs, action))
    }

    override fun cancelAll() {
        cancelCount++
        pending.clear()
    }

    /** Fire the oldest queued delayed task (e.g. a scheduled retry). */
    fun runNext() {
        val task = pending.removeFirst()
        task.action()
    }
}

/** Records the controller's UI-facing callbacks for assertions. */
class RecordingCallback : PlayerController.Callback {
    var bufferingCount = 0
    val playingEngines = mutableListOf<String>()
    var fatalCount = 0
    val retryAttempts = mutableListOf<Int>()

    override fun onBuffering() {
        bufferingCount++
    }

    override fun onPlaying(engineName: String) {
        playingEngines.add(engineName)
    }

    override fun onFatalError() {
        fatalCount++
    }

    override fun onRetrying(attempt: Int) {
        retryAttempts.add(attempt)
    }
}
