/*
 * SearchInputKeyPolicy.kt
 * Decides whether the search screen may intercept a D-pad key while the search
 * field has focus. Field report (Fire TV, remote only): the on-screen keyboard
 * opened, but no letter could be entered. The Activity took DOWN (to jump to
 * the filter row) and RIGHT (to jump to the clear button) before the IME ever
 * saw them, so the user could not move from the field into the keyboard rows.
 * While the IME is showing, every D-pad key must reach the IME; the screen
 * only navigates once the keyboard is closed (BACK) or on a physical keyboard.
 */
package com.iptv.player.ui.search

import android.view.KeyEvent

internal object SearchInputKeyPolicy {

    enum class Action { LET_IME_HANDLE, FOCUS_FILTERS, FOCUS_CLEAR_BUTTON, PASS_THROUGH }

    fun decide(
        keyCode: Int,
        imeVisible: Boolean,
        towardEnd: Int,
        cursorAtEnd: Boolean,
        clearButtonVisible: Boolean,
    ): Action {
        val dpad = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
        if (imeVisible && dpad) return Action.LET_IME_HANDLE
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> Action.FOCUS_FILTERS
            towardEnd -> if (cursorAtEnd && clearButtonVisible) Action.FOCUS_CLEAR_BUTTON else Action.PASS_THROUGH
            else -> Action.PASS_THROUGH
        }
    }
}
