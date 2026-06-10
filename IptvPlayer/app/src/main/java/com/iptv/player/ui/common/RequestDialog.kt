/*
 * RequestDialog.kt
 * Modern Home "İstek & Şikayet" dialog. Lets the user pick a request type
 * (live channel / movie / series / complaint), type a message, and send it to
 * the crash-receiver via RequestReporter. D-pad driven: the four type chips form
 * a single-select row, the message box and the send/cancel CTAs follow. The chips
 * + input + the user's sent-request history live in a height-capped ScrollView and
 * the window uses SOFT_INPUT_ADJUST_RESIZE, so the on-screen keyboard can never
 * hide what's being typed. Shows a toast on success/failure and only dismisses
 * once the post succeeds. The send button is never disabled while busy (that would
 * steal D-pad focus) — a guard flag prevents double submits instead. The history
 * list loads on open and reloads after a successful send.
 */
package com.iptv.player.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.util.RequestReporter
import kotlinx.coroutines.launch

object RequestDialog {

    /** Type codes posted to the server; index-aligned with the chip row. */
    private val TYPES = listOf("channel", "movie", "series", "complaint")

    /** Most recent requests to show in the history list (server may return more). */
    private const val HISTORY_LIMIT = 8

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
        val historyHeader = view.findViewById<TextView>(R.id.requestHistoryHeader)
        val historyEmpty = view.findViewById<TextView>(R.id.requestHistoryEmpty)
        val historyList = view.findViewById<LinearLayout>(R.id.requestHistory)

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .create()
        // Resize the dialog when the on-screen keyboard opens so the message box
        // (and what the user is typing) is never hidden behind it.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        fun loadHistory() {
            activity.lifecycleScope.launch {
                val items = RequestReporter.fetchMine(activity)
                if (activity.isFinishing || activity.isDestroyed) return@launch
                renderHistory(activity, historyList, historyHeader, historyEmpty, items)
            }
        }

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
                if (ok) {
                    messageBox.setText("")
                    loadHistory()
                }
                sending = false
            }
        }

        dialog.show()
        chips[selected].requestFocus()
        loadHistory()
    }

    /** Render (or clear) the sent-request history rows below the compose form. */
    private fun renderHistory(
        activity: AppCompatActivity,
        container: LinearLayout,
        header: TextView,
        empty: TextView,
        items: List<RequestReporter.MyRequest>,
    ) {
        container.removeAllViews()
        if (items.isEmpty()) {
            header.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        header.visibility = View.VISIBLE
        empty.visibility = View.GONE
        val inflater = LayoutInflater.from(activity)
        items.take(HISTORY_LIMIT).forEach { r ->
            val row = inflater.inflate(R.layout.item_request_history, container, false)
            val done = r.status == "done"
            val status = row.findViewById<TextView>(R.id.histStatus)
            status.setText(if (done) R.string.request_status_done else R.string.request_status_new)
            status.setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (done) R.color.accent_emerald else R.color.accent_amber,
                )
            )
            row.findViewById<TextView>(R.id.histType).text = typeLabel(activity, r.type)
            row.findViewById<TextView>(R.id.histMessage).text = r.message
            container.addView(row)
        }
    }

    private fun typeLabel(activity: AppCompatActivity, type: String): String =
        activity.getString(
            when (type) {
                "channel" -> R.string.request_type_channel
                "movie" -> R.string.request_type_movie
                "series" -> R.string.request_type_series
                "complaint" -> R.string.request_type_complaint
                else -> R.string.request_type_other
            }
        )
}
