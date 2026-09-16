/*
 * PinAttemptPolicy.kt
 * Pure throttling rules for PIN entry: after [threshold] wrong attempts the
 * input is locked for [baseLockMs], doubling on every further wrong attempt up
 * to [maxLockMs]. Holds no clock or storage of its own so it is unit-testable;
 * PinAttemptGuard persists the [State] between dialogs and app restarts.
 */
package com.iptv.player.ui.common

class PinAttemptPolicy(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val baseLockMs: Long = DEFAULT_BASE_LOCK_MS,
    private val maxLockMs: Long = DEFAULT_MAX_LOCK_MS,
) {

    /** Persistent attempt bookkeeping; [lockedUntilMs] is a wall-clock deadline. */
    data class State(val failures: Int = 0, val lockedUntilMs: Long = 0L) {
        companion object {
            val CLEAR = State()
        }
    }

    /** Milliseconds until input may be accepted again; 0 when not locked. */
    fun remainingLockMs(state: State, nowMs: Long): Long =
        (state.lockedUntilMs - nowMs).coerceAtLeast(0L)

    fun isLocked(state: State, nowMs: Long): Boolean = remainingLockMs(state, nowMs) > 0L

    /** Whole seconds to show to the user (rounded up so "1 s" never reads as 0). */
    fun remainingLockSeconds(state: State, nowMs: Long): Int {
        val remaining = remainingLockMs(state, nowMs)
        return ((remaining + 999L) / 1000L).toInt()
    }

    /** Registers a wrong PIN; the first [threshold]-th failure starts the lockout. */
    fun onFailure(state: State, nowMs: Long): State {
        val failures = state.failures + 1
        if (failures < threshold) return State(failures, state.lockedUntilMs)
        val lockouts = (failures - threshold).coerceAtMost(MAX_SHIFT)
        val duration = (baseLockMs shl lockouts).coerceIn(baseLockMs, maxLockMs)
        return State(failures, nowMs + duration)
    }

    /** A correct PIN clears the counter and any lockout. */
    fun onSuccess(): State = State.CLEAR

    companion object {
        const val DEFAULT_THRESHOLD = 5
        const val DEFAULT_BASE_LOCK_MS = 30_000L
        const val DEFAULT_MAX_LOCK_MS = 5 * 60_000L

        // Prevents shl overflow for absurd failure counts; the result is capped anyway.
        private const val MAX_SHIFT = 20
    }
}
