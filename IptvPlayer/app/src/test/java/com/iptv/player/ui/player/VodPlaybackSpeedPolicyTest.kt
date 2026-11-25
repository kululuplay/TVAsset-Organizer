package com.iptv.player.ui.player

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class VodPlaybackSpeedPolicyTest {

    @Test
    fun `speeds run from half to double speed and offer normal speed once`() {
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), VodPlaybackSpeedPolicy.SPEEDS)
        assertEquals(VodPlaybackSpeedPolicy.SPEEDS.sorted(), VodPlaybackSpeedPolicy.SPEEDS)
        assertEquals(1, VodPlaybackSpeedPolicy.SPEEDS.count { it == VodPlaybackSpeedPolicy.DEFAULT })
    }

    @Test
    fun `labels are short and carry no trailing zeros`() {
        assertEquals(
            listOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"),
            VodPlaybackSpeedPolicy.SPEEDS.map { VodPlaybackSpeedPolicy.label(it, Locale.US) },
        )
    }

    @Test
    fun `labels use the decimal comma of the viewer's language`() {
        assertEquals("0,75x", VodPlaybackSpeedPolicy.label(0.75f, Locale.GERMANY))
        assertEquals("1,5x", VodPlaybackSpeedPolicy.label(1.5f, Locale.FRANCE))
        assertEquals("2x", VodPlaybackSpeedPolicy.label(2f, Locale.GERMANY))
    }
}
