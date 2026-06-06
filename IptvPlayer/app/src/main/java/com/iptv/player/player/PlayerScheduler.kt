/*
 * PlayerScheduler.kt
 * Tiny scheduling seam over android.os.Handler so PlayerController's
 * thread-hopping and retry/backoff timing can be driven deterministically in
 * pure-JVM unit tests (no Looper / Robolectric needed). Production uses the
 * Handler-backed implementation bound to the main looper; tests inject a fake
 * that records and fires tasks on demand.
 */
package com.iptv.player.player

import android.os.Handler
import android.os.Looper

interface PlayerScheduler {
    /** Run [action] on the UI thread, inline if already on it. */
    fun runOnMain(action: () -> Unit)

    /** Schedule [action] to run after [delayMs] on the UI thread. */
    fun postDelayed(delayMs: Long, action: () -> Unit)

    /** Cancel every pending task scheduled through this scheduler. */
    fun cancelAll()
}

/** Default production scheduler: posts to the main looper via a Handler. */
class HandlerScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : PlayerScheduler {

    override fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else handler.post(action)
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        handler.postDelayed(action, delayMs)
    }

    override fun cancelAll() {
        handler.removeCallbacksAndMessages(null)
    }
}
