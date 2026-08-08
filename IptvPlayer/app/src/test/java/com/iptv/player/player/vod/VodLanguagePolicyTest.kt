package com.iptv.player.player.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodLanguagePolicyTest {

    @Test
    fun `normalizes provider underscore tags to BCP-47`() {
        assertEquals("tr-TR", VodLanguagePolicy.normalize(" tr_TR "))
        assertEquals("en-US", VodLanguagePolicy.normalize("EN_us"))
    }

    @Test
    fun `keeps valid three-letter language tags`() {
        assertEquals("deu", VodLanguagePolicy.normalize("deu"))
    }

    @Test
    fun `rejects blank undefined and display labels`() {
        assertNull(VodLanguagePolicy.normalize(null))
        assertNull(VodLanguagePolicy.normalize("  "))
        assertNull(VodLanguagePolicy.normalize("und"))
        assertNull(VodLanguagePolicy.normalize("Türkçe"))
    }

    @Test
    fun `matches exact and regional variants by primary language`() {
        assertTrue(VodLanguagePolicy.matches("de-DE", "de-DE"))
        assertTrue(VodLanguagePolicy.matches("de-DE", "de-AT"))
        assertFalse(VodLanguagePolicy.matches("de-DE", "nl-NL"))
    }
}
