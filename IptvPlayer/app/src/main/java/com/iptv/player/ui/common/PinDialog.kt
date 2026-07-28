/*
 * PinDialog.kt
 * Reusable 4-digit PIN dialog used by parental controls. Three flows:
 *   - showSet:   prompt for a new PIN, then confirm it matches (pin_set/pin_confirm).
 *   - showEnter: prompt for the PIN and validate against an expected value (pin_enter).
 * All user-facing text comes from the shared pin_* / action_* strings.
 */
package com.iptv.player.ui.common

import android.content.Context
import com.iptv.player.R

object PinDialog {

    /** Asks for a new PIN twice and calls [onPin] with the confirmed value. */
    fun showSet(context: Context, onPin: (String) -> Unit) {
        PinPromptDialog.showEntry(
            context = context,
            titleRes = R.string.pin_set,
            onValue = { first ->
                PinPromptDialog.showEntry(
                    context = context,
                    titleRes = R.string.pin_confirm,
                    onValue = onPin,
                    isValid = { second -> first == second },
                    invalidMessageRes = R.string.pin_mismatch,
                )
            },
        )
    }

    /** Asks for the PIN and runs [onSuccess] only when it matches [expected]. */
    fun showEnter(context: Context, expected: String, onSuccess: () -> Unit) {
        PinPromptDialog.show(
            context = context,
            expectedPin = expected,
            onSuccess = onSuccess,
        )
    }
}
