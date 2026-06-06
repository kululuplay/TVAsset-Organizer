/*
 * AnrWatchdog.kt
 * Tiny "application not responding" detector. A background thread periodically
 * posts a no-op to the main looper and checks whether it ran within a deadline.
 * If the main thread is blocked for too long (e.g. accidental disk/network work
 * on the UI thread), it logs the main thread's stack so the freeze can be traced
 * from field logs. It never kills the app — it only observes and reports.
 */
package com.iptv.player.util

import android.os.Handler
import android.os.Looper

class AnrWatchdog(
    private val timeoutMs: Long = 5_000L,
    private val checkIntervalMs: Long = 2_000L
) : Thread("AnrWatchdog") {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var ticked = true
    @Volatile private var running = true
    // Avoid spamming the log while a single long freeze is ongoing.
    private var reportedForCurrentStall = false

    init {
        isDaemon = true
    }

    fun stopWatching() {
        running = false
        interrupt()
    }

    override fun run() {
        while (running && !isInterrupted) {
            ticked = false
            mainHandler.post { ticked = true }

            try {
                sleep(timeoutMs)
            } catch (_: InterruptedException) {
                return
            }

            if (!ticked) {
                if (!reportedForCurrentStall) {
                    reportedForCurrentStall = true
                    val stack = Looper.getMainLooper().thread.stackTrace
                    val rendered = stack.joinToString("\n") { "\tat $it" }
                    Logger.w(
                        "AnrWatchdog",
                        "Main thread blocked for >${timeoutMs}ms. Main stack:\n$rendered"
                    )
                }
            } else {
                reportedForCurrentStall = false
            }

            try {
                sleep(checkIntervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
