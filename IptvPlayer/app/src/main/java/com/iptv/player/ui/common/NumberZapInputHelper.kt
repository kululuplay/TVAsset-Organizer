/*
 * NumberZapInputHelper.kt
 * Captures digit key presses (KEYCODE_0..9) to build a channel number, like a
 * classic TV remote. After a short idle period (or when a max length is reached)
 * it resolves the typed number to a channel via the caller-supplied [lookup]
 * lambda and reports the result. Forward both keyCode and repeatCount to
 * handleKeyDown() from an Activity's onKeyDown().
 *
 * Usage:
 *   private val zap = NumberZapInputHelper(
 *       lookup = { num -> channels.firstOrNull { it.number == num } },
 *       onResolved = { ch -> ch?.let { openPlayer(it) } },
 *       onInputChanged = { typed -> binding.zapOverlay.text = typed }
 *   )
 *   override fun onKeyDown(k:Int,e:KeyEvent) =
 *       zap.handleKeyDown(k, e.repeatCount) || super.onKeyDown(k,e)
 */
package com.iptv.player.ui.common

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.iptv.player.data.model.Channel

class NumberZapInputHelper(
    private val lookup: (Int) -> Channel?,
    private val onResolved: (Channel?) -> Unit,
    private val onInputChanged: (String) -> Unit = {},
    private val maxDigits: Int = 4,
    private val commitDelayMs: Long = 1500L
) {

    private val handler = Handler(Looper.getMainLooper())
    private val buffer = StringBuilder()

    private val commitRunnable = Runnable { commit() }

    /** True while one or more digits are waiting to be resolved. */
    val hasPendingInput: Boolean
        get() = buffer.isNotEmpty()

    /**
     * Handles digit and confirm key-down events.
     *
     * A held digit is consumed without appending its auto-repeat events. A confirm
     * key commits pending digits immediately, but is ignored when the buffer is
     * empty so OK can still click the focused channel/category normally.
     */
    fun handleKeyDown(keyCode: Int, repeatCount: Int = 0): Boolean {
        val decision = NumberZapInputPolicy.decide(
            keyCode = keyCode,
            repeatCount = repeatCount,
            hasPendingInput = hasPendingInput,
        )
        return when (decision.action) {
            NumberZapInputPolicy.Action.IGNORE -> false
            NumberZapInputPolicy.Action.COMMIT -> {
                commit()
                true
            }
            NumberZapInputPolicy.Action.CONSUME_REPEAT -> true
            NumberZapInputPolicy.Action.APPEND_DIGIT -> {
                val digit = decision.digit ?: return false
                buffer.append(digit)
                onInputChanged(buffer.toString())
                handler.removeCallbacks(commitRunnable)
                if (buffer.length >= maxDigits) {
                    commit()
                } else {
                    handler.postDelayed(commitRunnable, commitDelayMs)
                }
                true
            }
        }
    }

    /** Convenience overload for callers that already hold the full event. */
    fun handleKeyDown(event: KeyEvent): Boolean =
        handleKeyDown(event.keyCode, event.repeatCount)

    /** Commits pending input and reports whether anything was consumed. */
    fun commitIfPending(): Boolean {
        if (!hasPendingInput) return false
        commit()
        return true
    }

    /** Force resolution of whatever is currently typed (e.g. on OK press). */
    fun commit() {
        handler.removeCallbacks(commitRunnable)
        if (buffer.isEmpty()) return
        val number = buffer.toString().toIntOrNull()
        buffer.setLength(0)
        onInputChanged("")
        if (number != null) onResolved(lookup(number)) else onResolved(null)
    }

    fun cancel() {
        handler.removeCallbacks(commitRunnable)
        buffer.setLength(0)
        onInputChanged("")
    }
}
