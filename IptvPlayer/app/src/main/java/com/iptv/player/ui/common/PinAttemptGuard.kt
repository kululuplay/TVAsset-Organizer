/*
 * PinAttemptGuard.kt
 * Process-wide PIN throttling backed by SettingsStore so a lockout survives
 * closing and re-opening the prompt (and an app restart). Thin wrapper around
 * the pure PinAttemptPolicy; all reads/writes are synchronous SharedPreferences
 * mirrors, safe to call from the main thread.
 */
package com.iptv.player.ui.common

import android.os.SystemClock
import com.iptv.player.data.ServiceLocator

object PinAttemptGuard {

    private val policy = PinAttemptPolicy()

    private fun state(): PinAttemptPolicy.State {
        val settings = ServiceLocator.settings
        return PinAttemptPolicy.State(
            failures = settings.pinFailureCount(),
            lockedUntilMs = settings.pinLockedUntilMs(),
        )
    }

    private fun persist(state: PinAttemptPolicy.State) {
        ServiceLocator.settings.setPinAttemptState(state.failures, state.lockedUntilMs)
    }

    /** Milliseconds until PIN input is accepted again; 0 when not locked. */
    fun remainingLockMs(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        policy.remainingLockMs(state(), nowMs)

    /** Whole seconds left in the lockout (rounded up); 0 when not locked. */
    fun remainingLockSeconds(nowMs: Long = SystemClock.elapsedRealtime()): Int =
        policy.remainingLockSeconds(state(), nowMs)

    fun isLocked(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        policy.isLocked(state(), nowMs)

    /** Records a wrong PIN and returns true when this attempt started a lockout. */
    fun registerFailure(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val next = policy.onFailure(state(), nowMs)
        persist(next)
        return policy.isLocked(next, nowMs)
    }

    /** A correct PIN clears the counter and any pending lockout. */
    fun reset() {
        persist(policy.onSuccess())
    }
}
