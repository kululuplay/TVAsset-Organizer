/*
 * RequestDialog.kt
 * TV-first request and feedback flow. It keeps submission/history work bound to
 * the dialog lifetime, distinguishes empty history from a load failure, and
 * provides an explicit focus graph for remote, keyboard and RTL navigation.
 */
package com.iptv.player.ui.common

import android.text.InputFilter
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.util.RequestReporter
import com.iptv.player.util.SupportResult
import com.iptv.player.util.SupportFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object RequestDialog {

    /** Type codes posted to the server; index-aligned with the 2x2 chip grid. */
    private val TYPES = listOf("channel", "movie", "series", "complaint")

    /** Most recent requests to show in the history list (server may return more). */
    private const val HISTORY_LIMIT = 8

    /** Weak so a destroyed Activity can never be retained by this singleton. */
    private var activeDialog: WeakReference<AlertDialog>? = null
    private var activeOwner: WeakReference<AppCompatActivity>? = null

    fun show(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return

        var staleDialog: AlertDialog? = null
        val alreadyOpen = synchronized(this) {
            val dialog = activeDialog?.get()
            val owner = activeOwner?.get()
            if (dialog?.isShowing == true && owner === activity) {
                dialog
            } else {
                staleDialog = dialog?.takeIf { it.isShowing }
                activeDialog = null
                activeOwner = null
                null
            }
        }
        staleDialog?.dismiss()
        if (alreadyOpen != null) {
            val current = alreadyOpen.window?.currentFocus
            if (current == null || !current.requestFocus()) {
                alreadyOpen.findViewById<View>(R.id.chipChannel)?.requestFocus()
            }
            return
        }

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_request, null)
        val dialogWidthPx = constrainWidthToScreen(activity, view)

        val chips = listOf(
            view.findViewById<TextView>(R.id.chipChannel),
            view.findViewById<TextView>(R.id.chipMovie),
            view.findViewById<TextView>(R.id.chipSeries),
            view.findViewById<TextView>(R.id.chipComplaint),
        )
        val messageBox = view.findViewById<EditText>(R.id.requestMessage)
        val messageCounter = view.findViewById<TextView>(R.id.requestMessageCounter)
        val inlineStatus = view.findViewById<TextView>(R.id.requestInlineStatus)
        val contentScroll = view.findViewById<MaxHeightScrollView>(R.id.requestScroll)
        val historyStateRow = view.findViewById<View>(R.id.requestHistoryStateRow)
        val historyProgress = view.findViewById<ProgressBar>(R.id.requestHistoryProgress)
        val historyState = view.findViewById<TextView>(R.id.requestHistoryState)
        val historyRetry = view.findViewById<TextView>(R.id.requestHistoryRetry)
        val historyList = view.findViewById<LinearLayout>(R.id.requestHistory)
        val cancelButton = view.findViewById<TextView>(R.id.requestCancel)
        val sendButton = view.findViewById<View>(R.id.requestSend)
        val sendLabel = view.findViewById<TextView>(R.id.requestSendLabel)
        val sendProgress = view.findViewById<ProgressBar>(R.id.requestSendProgress)

        messageBox.filters = messageBox.filters + InputFilter.LengthFilter(
            RequestReporter.MAX_MESSAGE_LENGTH
        )

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .create()

        var selected = 0
        var sending = false
        var retryingSend = false
        var historyRows: List<View> = emptyList()
        var historyJob: Job? = null
        var sendJob: Job? = null

        fun updateCounter() {
            val length = messageBox.text?.length ?: 0
            messageCounter.text = activity.getString(
                R.string.request_message_counter,
                length,
                RequestReporter.MAX_MESSAGE_LENGTH,
            )
            messageCounter.setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (length >= RequestReporter.MAX_MESSAGE_LENGTH * 9 / 10) {
                        R.color.accent_amber
                    } else {
                        R.color.text_muted
                    },
                )
            )
        }

        fun applySelection() {
            chips.forEachIndexed { index, chip ->
                val selectedNow = index == selected
                chip.isSelected = selectedNow
                chip.isActivated = selectedNow
            }
            messageBox.nextFocusUpId = chips[selected].id
        }

        fun showInline(message: CharSequence, success: Boolean) {
            inlineStatus.text = message
            inlineStatus.setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (success) R.color.accent_emerald else R.color.danger,
                )
            )
            inlineStatus.visibility = View.VISIBLE
            inlineStatus.post {
                if (dialog.isShowing) {
                    contentScroll.smoothScrollTo(0, inlineStatus.bottom)
                    inlineStatus.announceForAccessibility(inlineStatus.text)
                }
            }
        }

        fun hideInline() {
            inlineStatus.visibility = View.GONE
        }

        fun setSendingState(isSending: Boolean, retry: Boolean = false) {
            sending = isSending
            retryingSend = !isSending && retry
            sendProgress.visibility = if (isSending) View.VISIBLE else View.GONE
            sendLabel.setText(
                when {
                    isSending -> R.string.request_sending
                    retry -> R.string.request_retry
                    else -> R.string.request_send
                }
            )
            sendButton.contentDescription = sendLabel.text
            // Keep the focused view enabled so Android TV does not throw focus
            // outside the modal. Clickability + the guard stop double submits.
            sendButton.isClickable = !isSending
            sendButton.alpha = if (isSending) 0.78f else 1f
        }

        fun updateFocusGraph() {
            val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL

            fun wirePair(first: View, second: View) {
                if (rtl) {
                    first.nextFocusLeftId = second.id
                    first.nextFocusRightId = first.id
                    second.nextFocusLeftId = second.id
                    second.nextFocusRightId = first.id
                } else {
                    first.nextFocusLeftId = first.id
                    first.nextFocusRightId = second.id
                    second.nextFocusLeftId = first.id
                    second.nextFocusRightId = second.id
                }
            }

            wirePair(chips[0], chips[1])
            wirePair(chips[2], chips[3])
            chips[0].nextFocusUpId = chips[0].id
            chips[1].nextFocusUpId = chips[1].id
            chips[0].nextFocusDownId = chips[2].id
            chips[1].nextFocusDownId = chips[3].id
            chips[2].nextFocusUpId = chips[0].id
            chips[3].nextFocusUpId = chips[1].id
            chips[2].nextFocusDownId = messageBox.id
            chips[3].nextFocusDownId = messageBox.id

            val firstBelowInput = historyRows.firstOrNull()
                ?: historyRetry.takeIf { it.visibility == View.VISIBLE }
                ?: sendButton
            messageBox.nextFocusUpId = chips[selected].id
            messageBox.nextFocusDownId = firstBelowInput.id

            historyRows.forEachIndexed { index, row ->
                row.nextFocusLeftId = row.id
                row.nextFocusRightId = row.id
                row.nextFocusUpId = historyRows.getOrNull(index - 1)?.id ?: messageBox.id
                row.nextFocusDownId = historyRows.getOrNull(index + 1)?.id ?: sendButton.id
            }

            historyRetry.nextFocusLeftId = historyRetry.id
            historyRetry.nextFocusRightId = historyRetry.id
            historyRetry.nextFocusUpId = messageBox.id
            historyRetry.nextFocusDownId = sendButton.id

            val actionUpTarget = historyRows.lastOrNull()
                ?: historyRetry.takeIf { it.visibility == View.VISIBLE }
                ?: messageBox
            cancelButton.nextFocusUpId = actionUpTarget.id
            sendButton.nextFocusUpId = actionUpTarget.id
            cancelButton.nextFocusDownId = cancelButton.id
            sendButton.nextFocusDownId = sendButton.id
            wirePair(cancelButton, sendButton)
        }

        fun clearHistoryStateCardFocus() {
            historyStateRow.isFocusable = false
            historyStateRow.setBackgroundResource(0)
            historyStateRow.setPadding(0, 0, 0, 0)
            historyStateRow.contentDescription = null
            historyState.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }

        fun showHistoryLoading() {
            val transientStateHadFocus =
                historyRetry.hasFocus() || historyStateRow.hasFocus()
            clearHistoryStateCardFocus()
            historyRows = emptyList()
            historyList.removeAllViews()
            historyList.visibility = View.GONE
            historyStateRow.visibility = View.VISIBLE
            historyProgress.visibility = View.VISIBLE
            historyState.setText(R.string.request_history_loading)
            historyState.setTextColor(
                ContextCompat.getColor(activity, R.color.text_secondary)
            )
            historyRetry.visibility = View.GONE
            updateFocusGraph()
            if (transientStateHadFocus) sendButton.requestFocus()
        }

        fun renderHistoryResult(result: RequestReporter.HistoryResult) {
            when (result) {
                is RequestReporter.HistoryResult.Success -> {
                    historyRetry.visibility = View.GONE
                    historyProgress.visibility = View.GONE
                    if (result.items.isEmpty()) {
                        historyList.removeAllViews()
                        historyList.visibility = View.GONE
                        historyStateRow.visibility = View.VISIBLE
                        historyState.setText(R.string.request_history_empty)
                        historyState.setTextColor(
                            ContextCompat.getColor(activity, R.color.text_secondary)
                        )
                        historyStateRow.isFocusable = true
                        historyStateRow.setBackgroundResource(
                            R.drawable.bg_request_history_row
                        )
                        val padding = activity.resources.getDimensionPixelSize(
                            R.dimen.space_s
                        )
                        historyStateRow.setPadding(padding, 0, padding, 0)
                        historyStateRow.contentDescription = historyState.text
                        historyState.importantForAccessibility =
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        historyRows = listOf(historyStateRow)
                    } else {
                        clearHistoryStateCardFocus()
                        historyStateRow.visibility = View.GONE
                        historyList.visibility = View.VISIBLE
                        historyRows = renderHistory(
                            activity,
                            historyList,
                            result.items.take(HISTORY_LIMIT),
                        )
                    }
                }
                RequestReporter.HistoryResult.Error, is RequestReporter.HistoryResult.DetailedError -> {
                    clearHistoryStateCardFocus()
                    historyRows = emptyList()
                    historyList.removeAllViews()
                    historyList.visibility = View.GONE
                    historyStateRow.visibility = View.VISIBLE
                    historyProgress.visibility = View.GONE
                    historyState.text = (result as? RequestReporter.HistoryResult.DetailedError)
                        ?.failure?.userMessage ?: activity.getString(R.string.request_history_failed)
                    historyState.setTextColor(
                        ContextCompat.getColor(activity, R.color.danger)
                    )
                    historyRetry.visibility = View.VISIBLE
                }
            }
            updateFocusGraph()
        }

        fun loadHistory() {
            historyJob?.cancel()
            showHistoryLoading()
            historyJob = activity.lifecycleScope.launch {
                val result = RequestReporter.fetchMineResult(activity)
                if (
                    !dialog.isShowing ||
                    activity.isFinishing ||
                    activity.isDestroyed
                ) return@launch
                renderHistoryResult(result)
            }
        }

        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                selected = index
                applySelection()
                updateFocusGraph()
                if (!sending) {
                    if (inlineStatus.visibility == View.VISIBLE) hideInline()
                    if (retryingSend) setSendingState(isSending = false)
                }
            }
        }
        applySelection()
        setSendingState(isSending = false)

        messageBox.doAfterTextChanged {
            updateCounter()
            if (!sending) {
                if (inlineStatus.visibility == View.VISIBLE) hideInline()
                if (retryingSend) setSendingState(isSending = false)
            }
        }
        updateCounter()

        messageBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                messageBox.hideSoftKeyboard()
                sendButton.requestFocus()
                true
            } else {
                false
            }
        }

        // Up/down leave the editor only at its first/last visual line, preserving
        // cursor navigation for multiline text entered with a hardware keyboard.
        messageBox.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val layout = messageBox.layout ?: return@setOnKeyListener false
            val offset = messageBox.selectionStart.coerceAtLeast(0)
            val line = layout.getLineForOffset(offset)
            val targetId = when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && line == 0 ->
                    messageBox.nextFocusUpId
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN && line >= layout.lineCount - 1 ->
                    messageBox.nextFocusDownId
                else -> View.NO_ID
            }
            if (targetId == View.NO_ID) {
                false
            } else {
                messageBox.hideSoftKeyboard()
                view.findViewById<View>(targetId)?.requestFocus() == true
            }
        }

        historyRetry.setOnClickListener { loadHistory() }
        cancelButton.setOnClickListener { dialog.dismiss() }

        sendButton.setOnClickListener {
            if (sending) return@setOnClickListener
            val raw = messageBox.text?.toString().orEmpty()
            val normalized = RequestReporter.normalizeMessage(raw)
            if (normalized == null) {
                showInline(activity.getString(R.string.request_empty), success = false)
                messageBox.requestFocus()
                return@setOnClickListener
            }

            val type = TYPES[selected]
            hideInline()
            setSendingState(isSending = true)
            sendJob?.cancel()
            sendJob = activity.lifecycleScope.launch {
                val result = try {
                    RequestReporter.sendDetailed(activity, type, normalized)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SupportResult.Failure(SupportFailureKind.UNKNOWN)
                }
                if (
                    !dialog.isShowing ||
                    activity.isFinishing ||
                    activity.isDestroyed
                ) return@launch

                val sent = result is SupportResult.Success
                setSendingState(isSending = false, retry = !sent)
                if (sent) {
                    // Sending deliberately leaves the editor enabled so a TV user
                    // never loses focus while the network request is in flight.
                    // Do not erase a newer draft they started during that request.
                    if (messageBox.text?.toString().orEmpty() == raw) {
                        messageBox.setText("")
                    }
                    showInline(result.userMessage, success = true)
                    loadHistory()
                } else {
                    showInline(result.userMessage, success = false)
                }
            }
        }

        dialog.setOnDismissListener {
            historyJob?.cancel()
            sendJob?.cancel()
            messageBox.hideSoftKeyboard()
            synchronized(this) {
                if (activeDialog?.get() === dialog) {
                    activeDialog = null
                    activeOwner = null
                }
            }
        }

        runCatching {
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dialogWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                )
            }
            synchronized(this) {
                activeDialog = WeakReference(dialog)
                activeOwner = WeakReference(activity)
            }
            (activity as? BaseActivity)?.trackIdleInteractions(dialog)
            updateFocusGraph()
            chips[selected].post { chips[selected].requestFocus() }
            loadHistory()
        }.onFailure {
            if (dialog.isShowing) dialog.dismiss()
            historyJob?.cancel()
            sendJob?.cancel()
            synchronized(this) {
                if (activeDialog?.get() === dialog) {
                    activeDialog = null
                    activeOwner = null
                }
            }
        }
    }

    /** Render history cards and return them for the dialog's focus graph. */
    private fun renderHistory(
        activity: AppCompatActivity,
        container: LinearLayout,
        items: List<RequestReporter.MyRequest>,
    ): List<View> {
        container.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        return items.map { request ->
            val row = inflater.inflate(R.layout.item_request_history, container, false)
            row.id = View.generateViewId()

            val done = request.status == "done"
            val status = row.findViewById<TextView>(R.id.histStatus)
            status.setText(if (done) R.string.request_status_done else R.string.request_status_new)
            status.setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (done) R.color.accent_emerald else R.color.accent_amber,
                )
            )
            status.setBackgroundResource(
                if (done) {
                    R.drawable.bg_request_status_done
                } else {
                    R.drawable.bg_request_status_pending
                }
            )
            val horizontalPadding = activity.resources.getDimensionPixelSize(R.dimen.space_s)
            val verticalPadding = (3f * activity.resources.displayMetrics.density).toInt()
            status.setPadding(
                horizontalPadding,
                verticalPadding,
                horizontalPadding,
                verticalPadding,
            )

            val type = typeLabel(activity, request.type)
            row.findViewById<TextView>(R.id.histType).text =
                if (request.code.isBlank()) type else "$type · ${request.code}"
            row.findViewById<TextView>(R.id.histMessage).text = request.message

            val dateText = formatCreatedAt(request.createdAt)
            val date = row.findViewById<TextView>(R.id.histDate)
            if (dateText == null) {
                date.visibility = View.GONE
            } else {
                date.text = dateText
                date.visibility = View.VISIBLE
            }

            row.contentDescription = listOfNotNull(
                type,
                request.code.takeIf { it.isNotBlank() },
                status.text?.toString(),
                dateText,
                request.message,
            ).joinToString(". ")
            container.addView(row)
            row
        }
    }

    private fun formatCreatedAt(raw: String): String? {
        if (raw.length < 19) return null
        return runCatching {
            // Server timestamps are ISO-8601 UTC. Parse the stable seconds part so
            // API 21 devices do not depend on java.time/desugaring.
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(raw.take(19)) ?: return null
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
        }.getOrNull()
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

    private fun constrainWidthToScreen(activity: AppCompatActivity, view: View): Int {
        val density = activity.resources.displayMetrics.density
        val requested = (720f * density).toInt()
        val safeMargin = (48f * density).toInt()
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val minimum = minOf((320f * density).toInt(), screenWidth)
        val available = (screenWidth - safeMargin * 2).coerceAtLeast(minimum)
        val width = minOf(requested, available)
        view.layoutParams = ViewGroup.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return width
    }
}
