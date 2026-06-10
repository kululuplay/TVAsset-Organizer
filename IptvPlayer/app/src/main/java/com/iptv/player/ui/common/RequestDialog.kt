/*
 * RequestDialog.kt
 * Modern Home "İstek & Şikayet" dialog. Lets the user pick a request type
 * (live channel / movie / series / complaint), type a message, and send it to
 * the crash-receiver via RequestReporter. D-pad driven: the four type chips form
 * a single-select row, the message box and the send/cancel CTAs follow. Shows a
 * toast on success/failure and only dismisses once the post succeeds. The send
 * button is never disabled while busy (that would steal D-pad focus) — a guard
 * flag prevents double submits instead.
 */
package com.iptv.player.ui.common

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.util.RequestReporter
import kotlinx.coroutines.launch

object RequestDialog {

    /** Type codes posted to the server; index-aligned with the chip row. */
    private val TYPES = listOf("channel", "movie", "series", "complaint")

    fun show(activity: AppCompatActivity) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_request, null)

        val chips = listOf(
            view.findViewById<TextView>(R.id.chipChannel),
            view.findViewById<TextView>(R.id.chipMovie),
            view.findViewById<TextView>(R.id.chipSeries),
            view.findViewById<TextView>(R.id.chipComplaint),
        )
        var selected = 0
        fun applySelection() {
            chips.forEachIndexed { i, chip -> chip.isSelected = i == selected }
        }
        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener {
                selected = i
                applySelection()
            }
        }
        applySelection()

        val messageBox = view.findViewById<EditText>(R.id.requestMessage)

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .create()

        view.findViewById<View>(R.id.requestCancel).setOnClickListener { dialog.dismiss() }

        val sendButton = view.findViewById<View>(R.id.requestSend)
        var sending = false
        sendButton.setOnClickListener {
            if (sending) return@setOnClickListener
            val text = messageBox.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(activity, R.string.request_empty, Toast.LENGTH_SHORT).show()
                messageBox.requestFocus()
                return@setOnClickListener
            }
            sending = true
            val type = TYPES[selected]
            activity.lifecycleScope.launch {
                val ok = RequestReporter.send(activity, type, text)
                if (activity.isFinishing || activity.isDestroyed) return@launch
                Toast.makeText(
                    activity,
                    if (ok) R.string.request_sent else R.string.request_failed,
                    Toast.LENGTH_LONG,
                ).show()
                if (ok) dialog.dismiss() else sending = false
            }
        }

        dialog.show()
        chips[selected].requestFocus()
    }
}
