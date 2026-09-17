/*
 * HomePinGate.kt
 *
 * Architecture note (Home screen decomposition):
 *   Single-flight wrapper around PinLockHelper.guard for HomeActivity. Repeated
 *   OK presses (row click, RIGHT shortcut, number zap, CH+/-) must not stack
 *   parental dialogs, and a PIN accepted after the screen stopped must not run
 *   its continuation. Both category and per-channel gates share this instance;
 *   what to unlock on success stays with the caller.
 *
 * Moved verbatim from HomeActivity (the two identical inline copies).
 */
package com.iptv.player.ui.home

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.iptv.player.ui.common.PinLockHelper

internal class HomePinGate(private val activity: AppCompatActivity) {

    /** Prevents repeated OK events from stacking multiple parental dialogs/actions. */
    private var promptInFlight = false
    private var requestGeneration = 0
    private var request: PinLockHelper.Request? = null

    /** Prompts for the adult PIN once; [onAllowed] runs only while still STARTED. */
    fun guard(onAllowed: () -> Unit) {
        if (promptInFlight) return
        promptInFlight = true
        val generation = ++requestGeneration
        request = PinLockHelper.guard(
            activity = activity,
            isAdult = true,
            onDenied = {
                if (generation == requestGeneration) {
                    promptInFlight = false
                    request = null
                }
            },
            onAllowed = allowed@{
                if (
                    generation != requestGeneration ||
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) return@allowed
                promptInFlight = false
                request = null
                onAllowed()
            },
        )
    }

    /** onStop: cancel a pending prompt and invalidate its continuation. */
    fun reset() {
        request?.cancel()
        request = null
        promptInFlight = false
        requestGeneration++
    }
}
