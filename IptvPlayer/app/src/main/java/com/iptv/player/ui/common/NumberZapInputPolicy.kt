/*
 * NumberZapInputPolicy.kt
 * Pure key-decision policy for TV remote number zapping. Keeping the decision
 * outside the Handler-backed helper makes repeat/confirm behaviour testable on
 * the JVM without an Android Looper.
 */
package com.iptv.player.ui.common

import android.view.KeyEvent

internal object NumberZapInputPolicy {

    enum class Action {
        IGNORE,
        APPEND_DIGIT,
        COMMIT,
        CONSUME_REPEAT,
    }

    data class Decision(
        val action: Action,
        val digit: Char? = null,
    )

    /**
     * Decides how a key-down should affect number input.
     *
     * Auto-repeat events are consumed for handled keys but never mutate/commit the
     * buffer. A confirm key commits only when digits are pending; otherwise it is
     * ignored so the focused TV row can perform its normal click.
     */
    fun decide(keyCode: Int, repeatCount: Int, hasPendingInput: Boolean): Decision {
        val digit = digitFor(keyCode)
        if (digit != null) {
            return if (repeatCount > 0) {
                Decision(Action.CONSUME_REPEAT)
            } else {
                Decision(Action.APPEND_DIGIT, digit)
            }
        }

        if (hasPendingInput && isConfirmKey(keyCode)) {
            return if (repeatCount > 0) {
                Decision(Action.CONSUME_REPEAT)
            } else {
                Decision(Action.COMMIT)
            }
        }

        return Decision(Action.IGNORE)
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
            ('0'.code + keyCode - KeyEvent.KEYCODE_NUMPAD_0).toChar()
        else -> null
    }

    private fun isConfirmKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A -> true
        else -> false
    }
}
