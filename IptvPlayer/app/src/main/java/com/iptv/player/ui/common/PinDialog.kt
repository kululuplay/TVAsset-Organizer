/*
 * PinDialog.kt
 * Reusable 4-digit PIN dialog used by parental controls. Three flows:
 *   - showSet:   prompt for a new PIN, then confirm it matches (pin_set/pin_confirm).
 *   - showEnter: prompt for the PIN and validate against an expected value (pin_enter).
 * All user-facing text comes from the shared pin_* / action_* strings.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.iptv.player.R

object PinDialog {

    /** Asks for a new PIN twice and calls [onPin] with the confirmed value. */
    fun showSet(context: Context, onPin: (String) -> Unit) {
        promptPin(context, R.string.pin_set) { first ->
            promptPin(context, R.string.pin_confirm) { second ->
                if (first == second) {
                    onPin(first)
                } else {
                    Toast.makeText(context, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Asks for the PIN and runs [onSuccess] only when it matches [expected]. */
    fun showEnter(context: Context, expected: String, onSuccess: () -> Unit) {
        promptPin(context, R.string.pin_enter) { entered ->
            if (entered == expected) {
                onSuccess()
            } else {
                Toast.makeText(context, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptPin(context: Context, titleRes: Int, onValue: (String) -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine()
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                onValue(input.text.toString().trim())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
