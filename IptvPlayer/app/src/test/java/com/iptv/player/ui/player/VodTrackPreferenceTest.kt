package com.iptv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodTrackPreferenceTest {

    @Test
    fun `semantic language survives different track labels`() {
        val saved = VodTrackPreference.encode("English (AAC 5.1)")

        assertEquals(
            1,
            VodTrackPreference.bestMatchIndex(
                saved,
                listOf("Deutsch AC3", "ENG · Stereo", "Türkçe"),
            ),
        )
    }

    @Test
    fun `commentary role wins over the main track in the same language`() {
        val saved = VodTrackPreference.encode("English Commentary")

        assertEquals(
            1,
            VodTrackPreference.bestMatchIndex(
                saved,
                listOf("English", "ENG Director Commentary"),
            ),
        )
    }

    @Test
    fun `legacy exact labels remain supported`() {
        assertEquals(
            0,
            VodTrackPreference.bestMatchIndex("Türkçe AC3", listOf("Turkce AC3", "English")),
        )
    }

    @Test
    fun `legacy language label upgrades semantically`() {
        assertEquals(
            1,
            VodTrackPreference.bestMatchIndex("English", listOf("Deutsch AC3", "ENG AAC")),
        )
    }

    @Test
    fun `unrelated language is not selected`() {
        val saved = VodTrackPreference.encode("Deutsch")
        assertNull(VodTrackPreference.bestMatchIndex(saved, listOf("English", "Türkçe")))
    }

    @Test
    fun `empty incremental track snapshot does not close preference generation`() {
        val plan = VodTrackPreference.applicationPlan(
            savedAudio = VodTrackPreference.encode("English"),
            savedSubtitle = VodTrackPreference.encode("Türkçe"),
            audioCandidates = emptyList(),
            subtitleCandidates = emptyList(),
            disabledSubtitleToken = "__off__",
        )

        assertFalse(plan.complete)
        assertNull(plan.audioIndex)
        assertNull(plan.subtitleIndex)
    }

    @Test
    fun `later populated snapshot resolves both preferences`() {
        val plan = VodTrackPreference.applicationPlan(
            savedAudio = VodTrackPreference.encode("English"),
            savedSubtitle = VodTrackPreference.encode("Türkçe"),
            audioCandidates = listOf("Deutsch", "ENG AAC"),
            subtitleCandidates = listOf("Turkce Forced", "English"),
            disabledSubtitleToken = "__off__",
        )

        assertTrue(plan.complete)
        assertEquals(1, plan.audioIndex)
        assertEquals(0, plan.subtitleIndex)
    }

    @Test
    fun `explicit subtitle off is complete even when no text tracks exist`() {
        val plan = VodTrackPreference.applicationPlan(
            savedAudio = null,
            savedSubtitle = "__off__",
            audioCandidates = emptyList(),
            subtitleCandidates = emptyList(),
            disabledSubtitleToken = "__off__",
        )

        assertTrue(plan.complete)
        assertTrue(plan.disableSubtitles)
    }
}
