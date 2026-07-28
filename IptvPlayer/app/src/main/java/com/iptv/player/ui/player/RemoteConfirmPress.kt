package com.iptv.player.ui.player

/**
 * Tracks one remote/gamepad confirm press without depending on Android runtime
 * classes. A release is actionable only when the same key produced a first
 * (non-repeat) DOWN. This prevents orphaned/cancelled UP events from being
 * mistaken for a very long press.
 */
internal class RemoteConfirmPress {

    data class Release(
        val heldMs: Long,
        val canceled: Boolean,
    )

    private var activeKeyCode: Int? = null
    private var pressedAtMs: Long = 0L

    /**
     * Records the first DOWN for [keyCode]. Repeats never arm a new press, and a
     * second confirm key cannot replace one that is already held.
     */
    fun onDown(keyCode: Int, eventTimeMs: Long, repeatCount: Int) {
        if (repeatCount != 0 || activeKeyCode != null) return
        activeKeyCode = keyCode
        pressedAtMs = eventTimeMs
    }

    /**
     * Consumes a matching UP and clears the state. Returns null for an orphaned
     * or different-key UP so callers can delegate that event normally.
     */
    fun onUp(keyCode: Int, eventTimeMs: Long, canceled: Boolean): Release? {
        if (activeKeyCode != keyCode) return null
        val release = Release(
            heldMs = (eventTimeMs - pressedAtMs).coerceAtLeast(0L),
            canceled = canceled,
        )
        clear()
        return release
    }

    fun clear() {
        activeKeyCode = null
        pressedAtMs = 0L
    }
}
