/*
 * PinPromptDialog.kt
 * A minimal, self-contained 4-digit PIN entry dialog (no coupling to Settings).
 * Verifies the entered PIN against [expectedPin]; on a match it invokes
 * [onSuccess], otherwise it shows the pin_wrong message. Reuses pin_* strings.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.iptv.player.R

object PinPromptDialog {

    fun show(
        context: Context,
        expectedPin: String?,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = context.getString(R.string.pin_enter)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.pin_enter)
            .setView(input)
            .setPositiveButton(R.string.action_ok) { dialog, _ ->
                if (input.text.toString() == expectedPin && !expectedPin.isNullOrEmpty()) {
                    onSuccess()
                } else {
                    Toast.makeText(context, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                    onCancel()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel) { dialog, _ ->
                onCancel()
                dialog.dismiss()
            }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
