package com.iptv.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodCompletionPolicyTest {

    private val min = 60_000L

    @Test
    fun `a film that stops after ten minutes of two hours ended early`() {
        assertTrue(VodCompletionPolicy.endedEarly(10 * min, 120 * min))
    }

    @Test
    fun `an end inside the last tenth or the last five minutes is a real end`() {
        assertFalse(VodCompletionPolicy.endedEarly(110 * min, 120 * min))
        assertFalse(VodCompletionPolicy.endedEarly(38 * min, 42 * min))
        // A short clip can never have five minutes left.
        assertFalse(VodCompletionPolicy.endedEarly(1 * min, 4 * min))
    }

    @Test
    fun `an episode cut twenty minutes before its end ended early`() {
        assertTrue(VodCompletionPolicy.endedEarly(22 * min, 42 * min))
    }

    @Test
    fun `unknown position or duration counts as a real end`() {
        assertFalse(VodCompletionPolicy.endedEarly(0L, 120 * min))
        assertFalse(VodCompletionPolicy.endedEarly(10 * min, 0L))
        assertFalse(VodCompletionPolicy.endedEarly(-1L, -1L))
    }
}
