package com.iptv.player.ui.player

/**
 * Tells a real end of a film or episode from a stream the provider closed
 * early. Both arrive as the same "ended" event from the engines.
 */
internal object VodCompletionPolicy {

    private const val MIN_REMAINING_MS = 5 * 60_000L

    /**
     * True when playback ended with a known position far before a known end:
     * more than five minutes and more than a tenth of the running time left.
     * The margin is wide on purpose, because container durations are often a
     * little longer than the content; an unknown position or duration counts
     * as a real end, as before.
     */
    fun endedEarly(positionMs: Long, durationMs: Long): Boolean =
        positionMs > 0L && durationMs > 0L &&
            durationMs - positionMs > maxOf(MIN_REMAINING_MS, durationMs / 10)
}
