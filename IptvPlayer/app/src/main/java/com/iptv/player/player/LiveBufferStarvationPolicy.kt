package com.iptv.player.player

/**
 * Earlier stall signal than the 15 s playback-clock watchdog, restricted to the
 * one situation that cannot be a healthy top-up: the engine says BUFFERING and
 * its buffer-fill marker (Media3 buffered position / libVLC bytes read) has not
 * grown at all for [starvationMs]. A slow but flowing link keeps growing the
 * marker and is left to the ordinary watchdog; an unknown marker (< 0) never
 * counts as flat. Inactive until [start], like the progress policy it sits beside.
 */
internal class LiveBufferStarvationPolicy(
    private val starvationMs: Long = EMPTY_BUFFER_STALL_MS,
) {
    private var active = false
    private var lastMarker = -1L
    private var flatSinceMs = -1L

    fun start() {
        reset()
        active = true
    }

    fun reset() {
        active = false
        lastMarker = -1L
        flatSinceMs = -1L
    }

    /** True exactly once when buffering has been fed no data for [starvationMs]. */
    fun sample(nowMs: Long, buffering: Boolean, bufferMarker: Long): Boolean {
        if (!active) return false
        if (!buffering || bufferMarker < 0L) {
            // Playing, or no evidence either way: every new buffering episode
            // starts a fresh baseline so a previous flat spell cannot carry over.
            lastMarker = -1L
            flatSinceMs = -1L
            return false
        }
        if (lastMarker < 0L || bufferMarker > lastMarker) {
            lastMarker = bufferMarker
            flatSinceMs = nowMs
            return false
        }
        if (nowMs - flatSinceMs >= starvationMs) {
            active = false
            return true
        }
        return false
    }

    companion object {
        /** Buffering with a completely flat buffer for this long is a dead source. */
        const val EMPTY_BUFFER_STALL_MS = 6_000L
    }
}
