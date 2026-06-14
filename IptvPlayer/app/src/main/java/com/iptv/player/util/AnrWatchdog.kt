/*
 * AnrWatchdog.kt
 * Tiny "application not responding" detector. A background thread periodically
 * posts a no-op to the main looper and checks whether it ran within a deadline.
 * If the main thread is blocked for too long (e.g. accidental disk/network work
 * on the UI thread), it logs the main thread's stack so the freeze can be traced
 * from field logs, and (when wired) reports one telemetry event. It never kills
 * the app — it only observes and reports.
 */
package com.iptv.player.util

import android.os.Handler
import android.os.Looper

class AnrWatchdog(
    private val timeoutMs: Long = 5_000L,
    private val checkIntervalMs: Long = 2_000L,
    /**
     * Invoked once per freeze (rate-limited) with the clipped main-thread stack so
     * a caller can ship a telemetry event. Runs on the watchdog thread; must not
     * throw. Null = log-only (the original behaviour).
     */
    private val onAnr: ((String) -> Unit)? = null,
) : Thread("AnrWatchdog") {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var ticked = true
    @Volatile private var running = true
    // Avoid spamming the log while a single long freeze is ongoing.
    private var reportedForCurrentStall = false
    // Throttle telemetry reports so a chronically slow box can't flood the spool.
    private var lastReportMs = 0L

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
                    val now = System.currentTimeMillis()
                    if (onAnr != null && now - lastReportMs >= REPORT_COOLDOWN_MS) {
                        lastReportMs = now
                        val clipped =
                            if (rendered.length > MAX_STACK_CHARS) rendered.substring(0, MAX_STACK_CHARS)
                            else rendered
                        runCatching { onAnr.invoke(clipped) }
                    }
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

    companion object {
        /** At most one ANR telemetry event per this window (log still fires each time). */
        private const val REPORT_COOLDOWN_MS = 10 * 60 * 1000L
        /** Clip the reported stack so a deep trace can't bloat the spool/row. */
        private const val MAX_STACK_CHARS = 8 * 1024
    }
}
