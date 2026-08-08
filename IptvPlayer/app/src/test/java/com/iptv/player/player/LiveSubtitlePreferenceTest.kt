package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSubtitlePreferenceTest {

    @Test
    fun `auto off and normalized language round trip independently`() {
        val values = listOf(
            LiveSubtitlePreference.Auto,
            LiveSubtitlePreference.Off,
            LiveSubtitlePreference.Language.from("deu")!!,
        )

        values.forEach { preference ->
            assertEquals(
                preference,
                LiveSubtitlePreference.fromStorage(preference.storageValue()),
            )
        }
    }

    @Test
    fun `legacy raw language remains a language while missing stays auto`() {
        assertEquals(
            LiveSubtitlePreference.Language.from("tr"),
            LiveSubtitlePreference.fromStorage("tur"),
        )
        assertEquals(LiveSubtitlePreference.Auto, LiveSubtitlePreference.fromStorage(null))
        assertEquals(LiveSubtitlePreference.Auto, LiveSubtitlePreference.fromStorage("garbage"))
    }
}
