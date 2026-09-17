package com.iptv.player.ui.player

import com.iptv.player.data.model.Episode
import com.iptv.player.data.model.Season
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodNextEpisodeFinderTest {

    private fun episode(season: Int, number: Int, url: String = "http://x/$season/$number") =
        Episode(
            id = "s${season}e$number",
            seriesId = "series",
            seasonNumber = season,
            episodeNumber = number,
            title = "S$season E$number",
            streamUrl = url,
        )

    private val seasons = listOf(
        Season("series", 2, listOf(episode(2, 1), episode(2, 2))),
        Season("series", 1, listOf(episode(1, 3), episode(1, 1), episode(1, 2, url = ""))),
    )

    @Test
    fun `next episode in the same season skips placeholder rows without a stream`() {
        // 1x2 has no stream URL, so 1x1 continues with 1x3.
        assertEquals("s1e3", VodNextEpisodeFinder.findNext(seasons, season = 1, episode = 1)?.id)
    }

    @Test
    fun `end of season rolls into the first episode of the next season`() {
        assertEquals("s2e1", VodNextEpisodeFinder.findNext(seasons, season = 1, episode = 3)?.id)
    }

    @Test
    fun `last episode of the last season has no successor`() {
        assertNull(VodNextEpisodeFinder.findNext(seasons, season = 2, episode = 2))
        assertNull(VodNextEpisodeFinder.findNext(emptyList(), season = 1, episode = 1))
    }
}
