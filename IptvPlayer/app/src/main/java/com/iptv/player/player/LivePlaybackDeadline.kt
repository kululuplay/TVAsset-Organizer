package com.iptv.player.player

/**
 * Main-thread liveness budget, including queue/ownership/surface wait BEFORE play.
 * Only a user request, suspension or sustained output may reset an episode.
 * Buffering, READY, submission and single frames never extend these deadlines.
 */
internal class LivePlaybackDeadline(
    private val nowMs: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val onTimeout: (terminal: Boolean) -> Unit,
    private val attemptTimeoutMs: Long = 45_000L,
    private val episodeTimeoutMs: Long = 90_000L,
) {
    private var generation = 0L
    private var episodeStartMs: Long? = null
    private var attemptDeadlineMs: Long? = null

    fun beginAttempt() {
        val now = nowMs()
        val start = episodeStartMs ?: now.also { episodeStartMs = it }
        val deadline = minOf(now + attemptTimeoutMs, start + episodeTimeoutMs)
        attemptDeadlineMs = deadline
        arm(++generation, deadline)
    }

    /** Retain the episode limit while a retry is in backoff or an engine retires. */
    fun waitingForRetry() {
        val start = episodeStartMs ?: nowMs().also { episodeStartMs = it }
        val deadline = start + episodeTimeoutMs
        attemptDeadlineMs = deadline
        arm(++generation, deadline)
    }

    fun reset() {
        ++generation
        episodeStartMs = null
        attemptDeadlineMs = null
    }

    private fun arm(token: Long, deadline: Long) {
        schedule((deadline - nowMs()).coerceAtLeast(0L)) {
            if (token == generation && attemptDeadlineMs == deadline) {
                if (nowMs() < deadline) {
                    arm(token, deadline)
                } else {
                    ++generation
                    attemptDeadlineMs = null
                    onTimeout(nowMs() >= checkNotNull(episodeStartMs) + episodeTimeoutMs)
                }
            }
        }
    }
}
