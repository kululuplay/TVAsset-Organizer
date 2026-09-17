package com.iptv.player.ui.home

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKeyPolicyTest {

    @Test
    fun `consumed key swallows repeats and its UP, then forgets it`() {
        val tracker = ConsumedUntilUpTracker()
        tracker.add(KeyEvent.KEYCODE_BACK)
        assertTrue(tracker.swallow(KeyEvent.KEYCODE_BACK, isDown = true, isUp = false, repeatCount = 1))
        assertTrue(tracker.swallow(KeyEvent.KEYCODE_BACK, isDown = false, isUp = true, repeatCount = 0))
        // Entry removed by the UP: the next press is dispatched normally.
        assertFalse(tracker.swallow(KeyEvent.KEYCODE_BACK, isDown = false, isUp = true, repeatCount = 0))
        assertFalse(tracker.swallow(KeyEvent.KEYCODE_DPAD_LEFT, isDown = true, isUp = false, repeatCount = 0))
    }

    @Test
    fun `a fresh DOWN after a lost UP passes through and clears the stale entry`() {
        val tracker = ConsumedUntilUpTracker()
        tracker.add(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse(tracker.swallow(KeyEvent.KEYCODE_DPAD_CENTER, isDown = true, isUp = false, repeatCount = 0))
        assertFalse(tracker.swallow(KeyEvent.KEYCODE_DPAD_CENTER, isDown = false, isUp = true, repeatCount = 0))
    }

    @Test
    fun `key classification`() {
        assertTrue(HomeKeyCodes.isConfirmKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(HomeKeyCodes.isConfirmKey(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(HomeKeyCodes.isConfirmKey(KeyEvent.KEYCODE_BACK))
        assertTrue(HomeKeyCodes.isNumberKey(KeyEvent.KEYCODE_NUMPAD_5))
        assertFalse(HomeKeyCodes.isNumberKey(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(HomeKeyCodes.cancelsNumberZap(KeyEvent.KEYCODE_CHANNEL_UP))
        assertFalse(HomeKeyCodes.cancelsNumberZap(KeyEvent.KEYCODE_1))
    }

    @Test
    fun `fullscreen player consumes handled keys, ignores repeats and marks BACK`() {
        val back = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_BACK, isDown = true, repeatCount = 0, guideVisible = false)
        assertEquals(HomeFullscreenKeyPolicy.Command.EXIT_FULLSCREEN, back.command)
        assertTrue(back.markConsumedUntilUp)
        assertTrue(back.cancelZap)

        val repeat = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_MENU, isDown = true, repeatCount = 2, guideVisible = false)
        assertTrue(repeat.consume)
        assertEquals(HomeFullscreenKeyPolicy.Command.NONE, repeat.command)

        val unrelated = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_VOLUME_UP, isDown = true, repeatCount = 0, guideVisible = false)
        assertFalse(unrelated.consume)
    }

    @Test
    fun `vertical zap keys keep a pending debounce, other keys cancel it`() {
        val chUp = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_CHANNEL_UP, isDown = true, repeatCount = 0, guideVisible = false)
        assertEquals(HomeFullscreenKeyPolicy.Command.ZAP_UP, chUp.command)
        assertFalse(chUp.cancelZap)

        val info = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_INFO, isDown = true, repeatCount = 0, guideVisible = false)
        assertEquals(HomeFullscreenKeyPolicy.Command.TOGGLE_CAPTION, info.command)
        assertTrue(info.cancelZap)

        val left = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_DPAD_LEFT, isDown = true, repeatCount = 0, guideVisible = false)
        assertTrue(left.consume)
        assertEquals(HomeFullscreenKeyPolicy.Command.NONE, left.command)
    }

    @Test
    fun `guide leaves vertical navigation to the list and closes on BACK or LEFT`() {
        val down = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_DPAD_DOWN, isDown = true, repeatCount = 0, guideVisible = true)
        assertFalse(down.consume)

        val left = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_DPAD_LEFT, isDown = true, repeatCount = 0, guideVisible = true)
        assertEquals(HomeFullscreenKeyPolicy.Command.CLOSE_GUIDE, left.command)
        assertTrue(left.markConsumedUntilUp)
        assertFalse(left.cancelZap)

        val pageDown = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_PAGE_DOWN, isDown = true, repeatCount = 0, guideVisible = true)
        assertEquals(HomeFullscreenKeyPolicy.Command.GUIDE_ZAP_DOWN, pageDown.command)

        val info = HomeFullscreenKeyPolicy.decide(KeyEvent.KEYCODE_INFO, isDown = true, repeatCount = 0, guideVisible = true)
        assertTrue(info.consume)
        assertEquals(HomeFullscreenKeyPolicy.Command.NONE, info.command)
    }
}
