package com.iptv.player.data.remote

import com.iptv.player.data.model.Episode
import org.junit.Assert.*
import org.junit.Test

class MetadataPolicyTest {
    @Test fun newBadgeExpiresAt24HoursAndRejectsMissingOrFutureDates() {
        val now = 1_789_200_000L
        assertTrue(MetadataPolicy.isNew(now, now * 1000))
        assertTrue(MetadataPolicy.isNew(now - 86_399, now * 1000))
        assertFalse(MetadataPolicy.isNew(now - 86_400, now * 1000))
        assertFalse(MetadataPolicy.isNew(now + 1, now * 1000))
        assertFalse(MetadataPolicy.isNew(0, now * 1000))
    }
    @Test fun stripsProviderPrefixesButKeepsRealTitle() {
        assertEquals("Criminal Minds", MetadataPolicy.searchTitle("vodGerman Criminal Minds"))
        assertEquals("Bahar", MetadataPolicy.searchTitle("TR: Bahar"))
        assertEquals("Dark", MetadataPolicy.searchTitle("DE | Dark HD"))
        assertEquals("Deutschland 83", MetadataPolicy.searchTitle("Deutschland 83"))
        assertEquals("Dark", MetadataPolicy.searchTitle("Dark (2017)"))
        assertNull(MetadataPolicy.tmdbId("0"))
        assertEquals("4057", MetadataPolicy.tmdbId(" 4057 "))
    }
    @Test fun newEpisodeTimestampOutranksOlderSeriesTimestampAndNeverRollsBack() {
        assertEquals(1_789_200_000L, MetadataPolicy.newest(1_789_100_000, "1789200000000", "1789000000"))
        assertEquals(1_789_200_000L, MetadataPolicy.newest(1_789_200_000, "1789000000", null, ""))
        assertEquals(0L, MetadataPolicy.newest(0, "invalid", "-1"))
    }
    @Test fun enrichmentKeepsStreamIdentityAndRealProviderMetadata() {
        val original = Episode("e12", "s1", 2, 12, "12. Bölüm", "https://example.test/12.mkv")
        val metadata = TmdbEpisode(12, "Arrival", "Story", "/still.jpg", 45)
        val enriched = MetadataPolicy.enrichEpisode(original, metadata)
        assertEquals("Arrival", enriched.title)
        assertEquals(2700, enriched.durationSecs)
        assertEquals(original.streamUrl, enriched.streamUrl)
        assertEquals(original.id, enriched.id)
        val complete = original.copy(title = "Provider title", plot = "Provider plot", posterUrl = "https://example.test/image.jpg")
        assertEquals("Provider plot", MetadataPolicy.enrichEpisode(complete, metadata).plot)
        assertEquals(complete.posterUrl, MetadataPolicy.enrichEpisode(complete, metadata).posterUrl)
    }
}
