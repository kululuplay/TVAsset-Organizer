package com.iptv.player.ui.search

import android.view.KeyEvent
import com.iptv.player.ui.search.SearchInputKeyPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchInputKeyPolicyTest {

    private val right = KeyEvent.KEYCODE_DPAD_RIGHT

    @Test
    fun `while the keyboard is open every dpad key goes to the ime`() {
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        )) {
            assertEquals(Action.LET_IME_HANDLE, SearchInputKeyPolicy.decide(key, true, right, true, true))
        }
    }

    @Test
    fun `with the keyboard closed the screen navigates as before`() {
        assertEquals(Action.FOCUS_FILTERS, SearchInputKeyPolicy.decide(KeyEvent.KEYCODE_DPAD_DOWN, false, right, false, false))
        assertEquals(Action.FOCUS_CLEAR_BUTTON, SearchInputKeyPolicy.decide(right, false, right, true, true))
        assertEquals(Action.PASS_THROUGH, SearchInputKeyPolicy.decide(right, false, right, false, true))
        assertEquals(Action.PASS_THROUGH, SearchInputKeyPolicy.decide(right, false, right, true, false))
        assertEquals(Action.PASS_THROUGH, SearchInputKeyPolicy.decide(KeyEvent.KEYCODE_DPAD_UP, false, right, true, true))
    }

    @Test
    fun `letters are never intercepted`() {
        assertEquals(Action.PASS_THROUGH, SearchInputKeyPolicy.decide(KeyEvent.KEYCODE_A, true, right, true, true))
        assertEquals(Action.PASS_THROUGH, SearchInputKeyPolicy.decide(KeyEvent.KEYCODE_A, false, right, true, true))
    }
}
