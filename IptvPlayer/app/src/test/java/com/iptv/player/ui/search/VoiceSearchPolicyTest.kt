package com.iptv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSearchPolicyTest {

    @Test
    fun `the first usable result wins and is trimmed`() {
        assertEquals("tatort", VoiceSearchPolicy.bestResult(listOf("  tatort ", "tat ort")))
        assertEquals("tat ort", VoiceSearchPolicy.bestResult(listOf(null, "", "   ", "tat ort")))
    }

    @Test
    fun `a cancelled or empty recognition gives nothing`() {
        assertNull(VoiceSearchPolicy.bestResult(null))
        assertNull(VoiceSearchPolicy.bestResult(emptyList()))
        assertNull(VoiceSearchPolicy.bestResult(listOf("", "  ", null)))
    }
}
