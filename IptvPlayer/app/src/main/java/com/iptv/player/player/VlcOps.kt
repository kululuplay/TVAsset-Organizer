/*
 * VlcOps.kt
 * Shared single background thread for BLOCKING libVLC calls (MediaPlayer.stop()
 * / release(), and the stop->setMedia->play sequence).
 *
 * Why it exists: libVLC's MediaPlayer.stop() is SYNCHRONOUS — it waits for the
 * input/demuxer thread to wind down, which on a stalled/slow network can take
 * many SECONDS. Every play() also stops first (single-connection contract), and
 * the failure ladder re-enters play()/stop() on retries, so running these on the
 * main thread froze the whole UI ("app stops responding") exactly when the
 * user's internet was bad. ExoPlayer/Media3 calls are async and MUST stay on the
 * main thread — this thread is for libVLC only.
 *
 * Why ONE shared thread (an app singleton, never quit) instead of one per
 * engine instance:
 *  - FIFO ordering across engine instances preserves the single-connection
 *    contract during engine swaps: the OLD engine's queued stop/release is
 *    guaranteed to finish before the NEXT engine's play runs (PlayerController
 *    trampolines its delayed engine-create through this thread for the same
 *    reason).
 *  - The fallback ladder (EXO -> VLC_HW -> VLC_SW) plus the reconnect loop can
 *    create/destroy engines every few seconds on a bad network; per-instance
 *    threads would churn.
 *
 * View operations (attachViews/detachViews, adding/removing the VLCVideoLayout)
 * stay on the main thread — only the potentially-blocking native calls come
 * here. Each runnable is exception-guarded: a native IllegalStateException from
 * a torn-down player must not kill this long-lived thread (or the app).
 */
package com.iptv.player.player

import android.os.Handler
import android.os.HandlerThread
import com.iptv.player.util.Logger

object VlcOps {

    private const val TAG = "VlcOps"

    private val handler: Handler by lazy {
        val thread = HandlerThread("vlc-ops")
        thread.start()
        Handler(thread.looper)
    }

    /** Run [action] on the shared VLC ops thread (FIFO, exception-guarded). */
    fun post(action: () -> Unit) {
        handler.post {
            try {
                action()
            } catch (t: Throwable) {
                Logger.w(TAG, "vlc op failed", t)
            }
        }
    }
}
