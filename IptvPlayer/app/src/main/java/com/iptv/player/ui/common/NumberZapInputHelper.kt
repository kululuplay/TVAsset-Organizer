/*
 * NumberZapInputHelper.kt
 * Captures digit key presses (KEYCODE_0..9) to build a channel number, like a
 * classic TV remote. After a short idle period (or when a max length is reached)
 * it resolves the typed number to a channel via the caller-supplied [lookup]
 * lambda and reports the result. Forward keys to handleKeyDown() from an
 * Activity's onKeyDown().
 *
 * Usage:
 *   private val zap = NumberZapInputHelper(
 *       lookup = { num -> channels.firstOrNull { it.number == num } },
 *       onResolved = { ch -> ch?.let { openPlayer(it) } },
 *       onInputChanged = { typed -> binding.zapOverlay.text = typed }
 *   )
 *   override fun onKeyDown(k:Int,e:KeyEvent) = zap.handleKeyDown(k) || super.onKeyDown(k,e)
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

    /** Returns true if the key was a digit and consumed. */
    fun handleKeyDown(keyCode: Int): Boolean {
        val digit = digitFor(keyCode) ?: return false
        buffer.append(digit)
        onInputChanged(buffer.toString())
        handler.removeCallbacks(commitRunnable)
        if (buffer.length >= maxDigits) {
            commit()
        } else {
            handler.postDelayed(commitRunnable, commitDelayMs)
        }
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

    private fun digitFor(keyCode: Int): Char? = when (keyCode) {
        KeyEvent.KEYCODE_0 -> '0'
        KeyEvent.KEYCODE_1 -> '1'
        KeyEvent.KEYCODE_2 -> '2'
        KeyEvent.KEYCODE_3 -> '3'
        KeyEvent.KEYCODE_4 -> '4'
        KeyEvent.KEYCODE_5 -> '5'
        KeyEvent.KEYCODE_6 -> '6'
        KeyEvent.KEYCODE_7 -> '7'
        KeyEvent.KEYCODE_8 -> '8'
        KeyEvent.KEYCODE_9 -> '9'
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
            ('0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0))
        else -> null
    }
}
