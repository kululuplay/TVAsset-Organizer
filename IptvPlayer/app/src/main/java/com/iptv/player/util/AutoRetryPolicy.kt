/*
 * AutoRetryPolicy.kt
 * Pure backoff schedule for the live player's post-fatal automatic retries.
 * After PlayerController's own reconnect window has already elapsed
 * (onFatalError), the player keeps trying by itself on this schedule so an
 * unattended TV recovers from a provider/network hiccup without a remote
 * press. Kept free of Android types so it is unit-testable in plain JVM.
 */
package com.iptv.player.util

object AutoRetryPolicy {

    /**
     * Delay before each automatic retry, indexed by how many automatic retries
     * have already been made. Grows so a dead stream isn't hammered, caps at
     * two minutes, and stops entirely after the last entry (the user can still
     * retry manually at any time).
     */
    val delaysMs = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 120_000L)

    /** Total automatic retries before giving up (manual retry stays available). */
    val maxAttempts: Int get() = delaysMs.size

    /**
     * Delay (ms) to wait before automatic retry number [attempt] (0-based), or
     * null when the schedule is exhausted and automatic retrying must stop.
     */
    fun delayForAttempt(attempt: Int): Long? = when {
        attempt < 0 -> delaysMs[0]
        attempt >= delaysMs.size -> null
        else -> delaysMs[attempt]
    }
}
