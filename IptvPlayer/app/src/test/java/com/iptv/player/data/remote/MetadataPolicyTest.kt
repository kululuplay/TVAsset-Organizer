package com.iptv.player.data.remote

import com.iptv.player.data.model.Episode
import org.junit.Assert.*
import org.junit.Test

class MetadataPolicyTest {
    @Test fun newestMeansEpisodeAddedRatherThanAnUnrelatedSeriesMetadataEdit() {
        // Reproduces the provider: Bahar edited later, but Sevdiğim Sensin
        // actually received a new episode while its series timestamp stayed old.
        val bahar = MetadataPolicy.seriesFreshness(1789081451, 1766408164)
        val sevdigim = MetadataPolicy.seriesFreshness(1788614065, 1789099873)
        assertTrue(sevdigim > bahar)
        assertEquals(1789014198L, MetadataPolicy.seriesFreshness(1789022111, 1789014198))
        assertEquals(1789022111L, MetadataPolicy.seriesFreshness(1789022111, 0))
    }
    @Test fun episodeDatesAcceptSecondsAndMillisButRejectCorruptFutureValues() {
        val now = 1789200000000L
        assertEquals(1789099873L, MetadataPolicy.episodeTimestamp(
            listOf(null, "", "0", "1789099873000", "1766408164", "9999999999999"), now))
        assertEquals(0L, MetadataPolicy.episodeTimestamp(listOf("invalid", "-100"), now))
    }
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
