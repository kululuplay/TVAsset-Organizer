package com.iptv.player.ui.common

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberZapInputPolicyTest {

    @Test
    fun initialDigitAppendsExactlyOnce() {
        val decision = NumberZapInputPolicy.decide(
            keyCode = KeyEvent.KEYCODE_7,
            repeatCount = 0,
            hasPendingInput = false,
        )

        assertEquals(NumberZapInputPolicy.Action.APPEND_DIGIT, decision.action)
        assertEquals('7', decision.digit)
    }

    @Test
    fun heldDigitConsumesRepeatsWithoutAppending() {
        val decision = NumberZapInputPolicy.decide(
            keyCode = KeyEvent.KEYCODE_7,
            repeatCount = 3,
            hasPendingInput = true,
        )

        assertEquals(NumberZapInputPolicy.Action.CONSUME_REPEAT, decision.action)
        assertNull(decision.digit)
    }

    @Test
    fun confirmCommitsOnlyWhenInputIsPending() {
        val pending = NumberZapInputPolicy.decide(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            repeatCount = 0,
            hasPendingInput = true,
        )
        val empty = NumberZapInputPolicy.decide(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            repeatCount = 0,
            hasPendingInput = false,
        )

        assertEquals(NumberZapInputPolicy.Action.COMMIT, pending.action)
        assertEquals(NumberZapInputPolicy.Action.IGNORE, empty.action)
    }

    @Test
    fun heldConfirmCannotCommitTwice() {
        val decision = NumberZapInputPolicy.decide(
            keyCode = KeyEvent.KEYCODE_ENTER,
            repeatCount = 1,
            hasPendingInput = true,
        )

        assertEquals(NumberZapInputPolicy.Action.CONSUME_REPEAT, decision.action)
    }

    @Test
    fun numpadDigitsAndAlternateConfirmKeysAreSupported() {
        val digit = NumberZapInputPolicy.decide(
            keyCode = KeyEvent.KEYCODE_NUMPAD_4,
            repeatCount = 0,
            hasPendingInput = false,
        )
        val confirm = NumberZapInputPolicy.decide(
            keyCode = KeyEvent.KEYCODE_BUTTON_A,
            repeatCount = 0,
            hasPendingInput = true,
        )

        assertEquals(NumberZapInputPolicy.Action.APPEND_DIGIT, digit.action)
        assertEquals('4', digit.digit)
        assertEquals(NumberZapInputPolicy.Action.COMMIT, confirm.action)
    }
}
