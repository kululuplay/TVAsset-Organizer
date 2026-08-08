package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackLanguageTest {
    @Test
    fun `normalizes common iso codes and localized labels`() {
        assertEquals("tr", TrackLanguage.normalize("tur"))
        assertEquals("tr", TrackLanguage.normalize("Audio - Türkçe"))
        assertEquals("de", TrackLanguage.normalize("Deutsch"))
        assertEquals("en", TrackLanguage.normalize("eng"))
        assertEquals("fr", TrackLanguage.normalize("fr-FR"))
    }

    @Test
    fun `unknown labels do not create unstable preferences`() {
        assertNull(TrackLanguage.normalize("Track principal"))
        assertNull(TrackLanguage.normalize(null))
    }
}
