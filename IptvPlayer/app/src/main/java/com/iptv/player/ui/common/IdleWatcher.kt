/*
 * IdleWatcher.kt
 * Main-thread, one-shot idle detector. After [timeoutMs] of no user interaction
 * it fires [onIdle] (typically to launch the screensaver). The watcher deliberately
 * stops before invoking the callback, preventing duplicate launches while Android
 * is moving the owner Activity through onPause/onStop.
 */
package com.iptv.player.ui.common

import android.os.Handler
import android.os.Looper

class IdleWatcher(
    private var timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onIdle: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val idleRunnable = Runnable {
        if (!running) return@Runnable
        running = false
        onIdle()
    }

    /** Convenience: configure from minutes (0 disables the watcher). */
    fun setTimeoutMinutes(minutes: Int) {
        timeoutMs = minutes.coerceAtLeast(0).toLong() * 60_000L
        if (running) restart()
    }

    fun start() {
        running = true
        restart()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(idleRunnable)
    }

    /** Call on every user interaction to reset the idle timer. */
    fun notifyInteraction() {
        if (running) restart()
    }

    private fun restart() {
        handler.removeCallbacks(idleRunnable)
        if (running && timeoutMs > 0L) {
            handler.postDelayed(idleRunnable, timeoutMs)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10 * 60_000L
    }
}
