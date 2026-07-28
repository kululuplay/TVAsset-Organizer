package com.iptv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfirmPressTest {

    @Test
    fun `orphaned up is ignored`() {
        val press = RemoteConfirmPress()

        assertNull(press.onUp(keyCode = 23, eventTimeMs = 500L, canceled = false))
    }

    @Test
    fun `matching down and up reports held duration`() {
        val press = RemoteConfirmPress()
        press.onDown(keyCode = 23, eventTimeMs = 100L, repeatCount = 0)

        val release = press.onUp(keyCode = 23, eventTimeMs = 725L, canceled = false)

        assertEquals(625L, release?.heldMs)
        assertFalse(release!!.canceled)
        assertNull(press.onUp(keyCode = 23, eventTimeMs = 800L, canceled = false))
    }

    @Test
    fun `repeat down without first down never arms a press`() {
        val press = RemoteConfirmPress()
        press.onDown(keyCode = 23, eventTimeMs = 300L, repeatCount = 1)

        assertNull(press.onUp(keyCode = 23, eventTimeMs = 900L, canceled = false))
    }

    @Test
    fun `different key cannot complete or replace active press`() {
        val press = RemoteConfirmPress()
        press.onDown(keyCode = 23, eventTimeMs = 100L, repeatCount = 0)
        press.onDown(keyCode = 66, eventTimeMs = 200L, repeatCount = 0)

        assertNull(press.onUp(keyCode = 66, eventTimeMs = 250L, canceled = false))
        assertEquals(
            300L,
            press.onUp(keyCode = 23, eventTimeMs = 400L, canceled = false)?.heldMs,
        )
    }

    @Test
    fun `canceled matching release is surfaced and clears state`() {
        val press = RemoteConfirmPress()
        press.onDown(keyCode = 96, eventTimeMs = 10L, repeatCount = 0)

        val release = press.onUp(keyCode = 96, eventTimeMs = 20L, canceled = true)

        assertTrue(release!!.canceled)
        assertNull(press.onUp(keyCode = 96, eventTimeMs = 30L, canceled = false))
    }

    @Test
    fun `clear drops an in-flight press`() {
        val press = RemoteConfirmPress()
        press.onDown(keyCode = 23, eventTimeMs = 100L, repeatCount = 0)

        press.clear()

        assertNull(press.onUp(keyCode = 23, eventTimeMs = 200L, canceled = false))
    }
}
