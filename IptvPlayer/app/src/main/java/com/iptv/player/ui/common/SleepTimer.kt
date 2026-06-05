/*
 * SleepTimer.kt
 * Tiny countdown that fires [onExpired] after N minutes. Used by both players to
 * stop playback automatically. Universal (works regardless of the engine).
 */
package com.iptv.player.ui.common

import android.os.Handler
import android.os.Looper

class SleepTimer(private val onExpired: () -> Unit) {

    private val handler = Handler(Looper.getMainLooper())

    var minutes: Int = 0
        private set

    /** Schedules expiry in [min] minutes; [min] <= 0 cancels any running timer. */
    fun set(min: Int) {
        cancel()
        minutes = min.coerceAtLeast(0)
        if (minutes > 0) {
            handler.postDelayed({
                minutes = 0
                onExpired()
            }, minutes.toLong() * 60_000L)
        }
    }

    fun cancel() {
        minutes = 0
        handler.removeCallbacksAndMessages(null)
    }

    fun release() = cancel()
}
