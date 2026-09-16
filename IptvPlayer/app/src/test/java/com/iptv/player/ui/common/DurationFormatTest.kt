package com.iptv.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {

    @Test
    fun `under an hour renders minutes and zero-padded seconds`() {
        assertEquals("0:05", DurationFormat.seconds(5))
        assertEquals("42:07", DurationFormat.seconds(42 * 60 + 7))
        assertEquals("59:59", DurationFormat.seconds(3599))
    }

    @Test
    fun `an hour or more renders hours minutes and seconds`() {
        assertEquals("1:00:00", DurationFormat.seconds(3600))
        assertEquals("2:03:04", DurationFormat.seconds(2 * 3600 + 3 * 60 + 4))
        assertEquals("12:00:09", DurationFormat.seconds(12 * 3600 + 9))
    }

    @Test
    fun `negative input is clamped instead of producing garbage`() {
        assertEquals("0:00", DurationFormat.seconds(-30))
    }
}
