package com.iptv.player.playback.core

/**
 * Framework-free bookkeeping for the stale-process recycle
 * ([com.iptv.player.playback.android.StaleProcessGuard]).
 *
 * All times are `SystemClock.elapsedRealtime()` milliseconds, which keep counting
 * through deep sleep, so a stick that slept overnight reports the real absence.
 * Two ways back to a long-lived process are covered:
 *  - the app returns to the foreground after at least the threshold in the
 *    background (Home pressed, screen off, device asleep);
 *  - the app stayed in front the whole time (every screen keeps the screen on,
 *    so the in-app screensaver or an idle screen can run all night) and the
 *    first key press arrives after at least the threshold without any input.
 *    A healthy running playback is then left alone: a viewer may watch a long
 *    match without touching the remote.
 * Not thread-safe; every call comes from the main thread.
 */
class StaleProcessTracker(private val thresholdMs: () -> Long) {

    enum class InputDecision {
        /** Deliver the event normally. */
        DELIVER,

        /** Recycle the process instead of delivering this press. */
        RECYCLE,

        /** A recycle is already under way; keep every later event away from the old UI. */
        SWALLOW,
    }

    data class InputResult(val decision: InputDecision, val idleMs: Long)

    private var backgroundSinceMs: Long? = null
    private var pendingAwayMs: Long? = null
    private var lastInputMs: Long? = null

    var recycleRequested: Boolean = false
        private set

    /** The first Activity started after none: process start or return from background. */
    fun onForeground(nowMs: Long) {
        backgroundSinceMs?.let { since -> pendingAwayMs = (nowMs - since).coerceAtLeast(0L) }
        backgroundSinceMs = null
        if (lastInputMs == null) lastInputMs = nowMs
    }

    /** The last started Activity stopped: backgrounded, screen off or device asleep. */
    fun onBackground(nowMs: Long) {
        backgroundSinceMs = nowMs
        pendingAwayMs = null
    }

    /**
     * A screen reached RESUMED. Returns the absence to act on when the app has
     * just come back after at least the threshold in the background, else null.
     * The return itself counts as the user's first input.
     */
    fun onResumed(nowMs: Long): Long? {
        val away = pendingAwayMs ?: return null
        pendingAwayMs = null
        lastInputMs = nowMs
        return away.takeIf { !recycleRequested && it >= thresholdMs() }
    }

    /** A key or touch press reached one of the app's windows. */
    fun onInput(nowMs: Long, healthyPlayback: Boolean): InputResult {
        if (recycleRequested) return InputResult(InputDecision.SWALLOW, 0L)
        val last = lastInputMs
        lastInputMs = nowMs
        if (last == null) return InputResult(InputDecision.DELIVER, 0L)
        val idleMs = (nowMs - last).coerceAtLeast(0L)
        val decision = if (idleMs >= thresholdMs() && !healthyPlayback) {
            InputDecision.RECYCLE
        } else {
            InputDecision.DELIVER
        }
        return InputResult(decision, idleMs)
    }

    fun markRecycleRequested() {
        recycleRequested = true
    }

    /**
     * The recycle could not be launched (screen not eligible, Cast owns playback,
     * rate limit, vendor refusal). Stay usable and re-arm after another full
     * threshold instead of retrying on every key press.
     */
    fun recycleNotLaunched(nowMs: Long) {
        recycleRequested = false
        lastInputMs = nowMs
    }
}
