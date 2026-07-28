/*
 * PinPromptDialog.kt
 * Modern, self-contained 4-digit PIN gate for locked categories/content.
 * Shows display boxes + an on-screen keypad (D-pad friendly for TV). The PIN
 * auto-submits as soon as 4 digits are entered — no "OK" press needed.
 *
 * A match against the configured [expectedPin] opens the content immediately.
 * There is NO master/back-door code: once the user sets a personal PIN, only
 * that PIN works. (The built-in "0000" only unlocks while no custom PIN has
 * been set, because callers pass it as [expectedPin] in that unset state.)
 * A wrong code shows an inline "incorrect" hint and clears the boxes to retry.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import com.iptv.player.R

object PinPromptDialog {

    fun show(
        context: Context,
        expectedPin: String?,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ): AlertDialog = showEntry(
        context = context,
        titleRes = R.string.pin_enter,
        onValue = { onSuccess() },
        isValid = { entered -> expectedPin != null && entered == expectedPin },
        invalidMessageRes = R.string.pin_wrong,
        onCancel = onCancel,
    )

    /**
     * Reusable TV-first 4-digit entry surface. [isValid] keeps the dialog open and
     * renders [invalidMessageRes] inline when confirmation/validation fails.
     */
    fun showEntry(
        context: Context,
        titleRes: Int,
        onValue: (String) -> Unit,
        isValid: (String) -> Boolean = { true },
        invalidMessageRes: Int = R.string.pin_wrong,
        onCancel: () -> Unit = {},
    ): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin, null, false)
        val title = view.findViewById<TextView>(R.id.pinTitle)
        val boxesRow = view.findViewById<LinearLayout>(R.id.pinBoxes)
        val keysCol = view.findViewById<LinearLayout>(R.id.pinKeys)
        val error = view.findViewById<TextView>(R.id.pinError)
        title.setText(titleRes)
        ViewCompat.setAccessibilityHeading(title, true)
        keysCol.layoutDirection = View.LAYOUT_DIRECTION_LTR

        val boxViews = mutableListOf<TextView>()
        repeat(4) {
            val box = LayoutInflater.from(context)
                .inflate(R.layout.item_pin_box, boxesRow, false) as TextView
            boxesRow.addView(box)
            boxViews.add(box)
        }

        val entry = StringBuilder()
        var consumeBackKeyUp = false

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setOnCancelListener { onCancel() }
            .create()

        fun refreshBoxes() {
            boxViews.forEachIndexed { i, box ->
                box.text = if (i < entry.length) "\u25CF" else ""
                box.isActivated = i < entry.length
            }
            boxesRow.contentDescription = context.getString(
                R.string.pin_progress,
                entry.length,
            )
        }

        fun submit() {
            val entered = entry.toString()
            if (isValid(entered)) {
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                onValue(entered)
            } else {
                error.setText(invalidMessageRes)
                error.visibility = View.VISIBLE
                error.announceForAccessibility(context.getString(invalidMessageRes))
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

        fun clearEntry() {
            if (entry.isEmpty()) return
            entry.setLength(0)
            error.visibility = View.INVISIBLE
            refreshBoxes()
        }

        // Hardware TV remotes/keyboards with a numeric pad should work directly,
        // without forcing users to traverse the on-screen keypad with D-pad.
        dialog.setOnKeyListener { _, keyCode, event ->
            val digit = when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                    ('0'.code + keyCode - KeyEvent.KEYCODE_0).toChar()
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                    ('0'.code + keyCode - KeyEvent.KEYCODE_NUMPAD_0).toChar()
                else -> null
            }
            when {
                digit != null -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        onDigit(digit.toString())
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_DEL -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        onBackspace()
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_CLEAR -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        clearEntry()
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_BACK -> when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount > 0) {
                            // A held TV-remote Back key must not erase the whole
                            // PIN and then dismiss the dialog in the same press.
                            consumeBackKeyUp
                        } else if (entry.isNotEmpty()) {
                            onBackspace()
                            consumeBackKeyUp = true
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        if (consumeBackKeyUp) {
                            consumeBackKeyUp = false
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
                else -> false
            }
        }

        fun keypadRow(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = context.resources.getDimensionPixelSize(R.dimen.space_s) }
        }

        val keyViews = mutableListOf<TextView>()
        fun addKey(
            row: LinearLayout,
            label: String,
            backspace: Boolean = false,
            clear: Boolean = false,
        ): TextView {
            val key = LayoutInflater.from(context)
                .inflate(R.layout.item_pin_key, row, false) as TextView
            key.id = View.generateViewId()
            key.text = label
            key.contentDescription = when {
                backspace -> context.getString(R.string.pin_backspace)
                clear -> context.getString(R.string.pin_clear)
                else -> label
            }
            key.setOnClickListener {
                when {
                    backspace -> onBackspace()
                    clear -> clearEntry()
                    else -> onDigit(label)
                }
            }
            row.addView(key)
            keyViews += key
            return key
        }

        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        ).forEach { labels ->
            keypadRow().also { row ->
                labels.forEach { addKey(row, it) }
                keysCol.addView(row)
            }
        }
        keypadRow().also { row ->
            addKey(row, "C", clear = true)
            addKey(row, "0")
            addKey(row, "\u232B", backspace = true)
            keysCol.addView(row)
        }

        // Explicit focus graph prevents geometry/font scaling from sending D-pad
        // focus outside the modal. Boundary arrows intentionally stay put.
        val rows = keyViews.chunked(3)
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, key ->
                key.nextFocusLeftId = row.getOrNull(columnIndex - 1)?.id ?: key.id
                key.nextFocusRightId = row.getOrNull(columnIndex + 1)?.id ?: key.id
                key.nextFocusUpId =
                    rows.getOrNull(rowIndex - 1)?.getOrNull(columnIndex)?.id ?: key.id
                key.nextFocusDownId =
                    rows.getOrNull(rowIndex + 1)?.getOrNull(columnIndex)?.id ?: key.id
            }
        }

        dialog.show()
        // Let only our rounded card show (drop the default dialog chrome).
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        (context as? BaseActivity)?.trackIdleInteractions(dialog)
        refreshBoxes()
        keyViews.firstOrNull()?.post { keyViews.firstOrNull()?.requestFocus() }
        return dialog
    }
}
