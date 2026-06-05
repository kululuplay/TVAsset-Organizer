/*
 * IdleWatcher.kt
 * Handler-based idle detector. After [timeoutMs] of no user interaction it fires
 * [onIdle] (typically to launch the screensaver). Activities forward interaction
 * by overriding onUserInteraction()/dispatchKeyEvent and calling notifyInteraction().
 *
 * Usage in an Activity:
 *   private val idle by lazy { IdleWatcher(this) { startScreensaver() } }
 *   override fun onResume()  { super.onResume();  idle.start() }
 *   override fun onPause()   { super.onPause();   idle.stop() }
 *   override fun onUserInteraction() { super.onUserInteraction(); idle.notifyInteraction() }
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
        if (running) onIdle()
    }

    /** Convenience: configure from minutes (0 disables the watcher). */
    fun setTimeoutMinutes(minutes: Int) {
        timeoutMs = minutes.toLong() * 60_000L
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
