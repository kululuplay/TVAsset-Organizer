/*
 * PinPromptDialog.kt
 * Modern, self-contained 4-digit PIN gate for locked categories/content.
 * Shows display boxes + an on-screen keypad (D-pad friendly for TV). The PIN
 * auto-submits as soon as 4 digits are entered — no "OK" press needed.
 *
 * A match against the configured [expectedPin] OR the built-in master code
 * (SettingsStore.DEFAULT_PIN = "0000") opens the content immediately. A wrong
 * code shows an inline "incorrect" hint and clears the boxes for another try.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.iptv.player.R
import com.iptv.player.data.prefs.SettingsStore

object PinPromptDialog {

    fun show(
        context: Context,
        expectedPin: String?,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin, null, false)
        val boxesRow = view.findViewById<LinearLayout>(R.id.pinBoxes)
        val keysCol = view.findViewById<LinearLayout>(R.id.pinKeys)
        val error = view.findViewById<TextView>(R.id.pinError)

        val boxViews = mutableListOf<TextView>()
        repeat(4) {
            val box = LayoutInflater.from(context)
                .inflate(R.layout.item_pin_box, boxesRow, false) as TextView
            boxesRow.addView(box)
            boxViews.add(box)
        }

        val entry = StringBuilder()

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setOnCancelListener { onCancel() }
            .create()
        // Let only our rounded card show (drop the default dialog chrome).
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun refreshBoxes() {
            boxViews.forEachIndexed { i, box ->
                box.text = if (i < entry.length) "\u25CF" else ""
            }
        }

        fun submit() {
            val entered = entry.toString()
            val ok = entered == expectedPin || entered == SettingsStore.DEFAULT_PIN
            if (ok) {
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                onSuccess()
            } else {
                error.visibility = View.VISIBLE
                entry.setLength(0)
                refreshBoxes()
            }
        }

        fun onDigit(d: String) {
            if (entry.length >= 4) return
            error.visibility = View.INVISIBLE
            entry.append(d)
            refreshBoxes()
            if (entry.length == 4) submit()
        }

        fun onBackspace() {
            if (entry.isNotEmpty()) {
                entry.deleteCharAt(entry.length - 1)
                refreshBoxes()
            }
        }

        fun keypadRow(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = context.resources.getDimensionPixelSize(R.dimen.space_s) }
        }

        fun addKey(row: LinearLayout, label: String, backspace: Boolean = false): TextView {
            val key = LayoutInflater.from(context)
                .inflate(R.layout.item_pin_key, row, false) as TextView
            key.text = label
            key.setOnClickListener { if (backspace) onBackspace() else onDigit(label) }
            row.addView(key)
            return key
        }

        val row1 = keypadRow()
        var firstKey: TextView? = null
        listOf("1", "2", "3", "4", "5").forEach { d ->
            val k = addKey(row1, d)
            if (firstKey == null) firstKey = k
        }
        val row2 = keypadRow()
        listOf("6", "7", "8", "9", "0").forEach { addKey(row2, it) }
        addKey(row2, "\u232B", backspace = true)
        keysCol.addView(row1)
        keysCol.addView(row2)

        dialog.show()
        firstKey?.requestFocus()
    }
}
