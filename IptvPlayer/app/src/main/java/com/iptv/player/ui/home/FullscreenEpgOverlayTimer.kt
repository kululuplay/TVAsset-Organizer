/*
 * FullscreenEpgOverlayTimer.kt
 *
 * Architecture note (Home screen decomposition):
 *   The dedicated handler behind HomeActivity's fullscreen EPG card: the
 *   one-second clock/progress tick and the four-second auto-hide. What the tick
 *   renders (playing-channel vs guide-row programmes, expired-EPG refresh) and
 *   whether hiding is allowed stay in the Activity, reached through [Host].
 *
 * Moved verbatim from HomeActivity.
 */
package com.iptv.player.ui.home

import android.os.Handler
import android.os.Looper
import android.view.View

internal class FullscreenEpgOverlayTimer(
    private val overlay: View,
    private val autoHideMs: Long,
    private val host: Host,
) {
    interface Host {
        /** `inlineFullscreen && !fullscreenGuideVisible` at the moment the hide fires. */
        fun canAutoHide(): Boolean
        /** False once fullscreen ended or the Activity is finishing/stopped. */
        fun shouldTick(): Boolean
        fun onTick(now: Long)
        val tickIntervalMs: Long
    }

    /** One-second fullscreen EPG clock/progress updates + four-second auto-hide. */
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable {
        if (host.canAutoHide()) {
            overlay.visibility = View.GONE
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!host.shouldTick()) return
            host.onTick(System.currentTimeMillis())
            handler.postDelayed(this, host.tickIntervalMs)
        }
    }

    /** Keeps progress/remaining time exact while the fullscreen card is active. */
    fun startTicker() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stopAll() {
        handler.removeCallbacksAndMessages(null)
    }

    fun cancelAutoHide() {
        handler.removeCallbacks(hide)
    }

    /** Caller has already checked that usable EPG is visible. */
    fun postAutoHide() {
        handler.postDelayed(hide, autoHideMs)
    }
}
