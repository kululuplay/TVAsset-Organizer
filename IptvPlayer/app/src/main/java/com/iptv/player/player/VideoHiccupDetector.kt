package com.iptv.player.player

/**
 * Counts short decoder stalls (1.7.0, rule C.3): video frames pause for at
 * least [stallMs] while playback is READY, the input is not buffering, the
 * buffer is healthy and the media clock keeps advancing, then frames resume.
 * One such hiccup is tolerated; the controller switches engines on the second
 * within the first seconds of a channel, before the long frame-stall watchdog
 * would ever fire. Fed from the engine's periodic liveness poll.
 */
internal class VideoHiccupDetector(
    private val stallMs: Long = DEFAULT_STALL_MS,
    private val resumedMs: Long = DEFAULT_RESUMED_MS,
) {
    enum class Event { NONE, HICCUP_RESUMED }

    private var stalled = false

    fun sample(
        ready: Boolean,
        buffering: Boolean,
        bufferHealthy: Boolean,
        clockAdvanceMs: Long,
        frameAgeMs: Long?,
        minimumClockAdvanceMs: Long,
    ): Event {
        val age = frameAgeMs ?: return Event.NONE
        if (!ready || buffering) {
            // A rebuffer or a stop is not a decoder hiccup.
            stalled = false
            return Event.NONE
        }
        if (stalled) {
            if (age < resumedMs) {
                stalled = false
                return Event.HICCUP_RESUMED
            }
            return Event.NONE
        }
        if (bufferHealthy && clockAdvanceMs >= minimumClockAdvanceMs && age >= stallMs) {
            stalled = true
        }
        return Event.NONE
    }

    fun reset() {
        stalled = false
    }

    companion object {
        const val DEFAULT_STALL_MS = 1_500L
        const val DEFAULT_RESUMED_MS = 600L
    }
}
