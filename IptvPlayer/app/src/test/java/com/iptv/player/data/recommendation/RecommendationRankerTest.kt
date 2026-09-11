package com.iptv.player.data.recommendation

import org.junit.Assert.*
import org.junit.Test

class RecommendationRankerTest {
    private val now = 1_789_200_000_000L
    private fun item(id: String, genre: String = "Comedy", rating: Double = 9.0, added: Long = now / 1000 - 86400) =
        RecommendationCandidate(id, id, genre, "cat-$genre", rating, added)
    private fun habits(profile: Long = 1, genre: String = "Crime", kind: String = "series") =
        (0..2).map { day -> WatchPreference(profile, kind, "seen-$day", now / 86400000 - day,
            1_800_000, now - day * 86400000L, genre, "cat-$genre") }
    private fun rank(history: List<WatchPreference>, profile: Long = 1, items: List<RecommendationCandidate> =
        listOf(item("comedy"), item("crime", "Crime", 7.0))): List<String> =
        RecommendationRanker(profile, "movie", history, now).also { r -> items.forEach(r::offer) }.results()

    @Test fun sustainedViewingAcrossDaysLearnsGenresAcrossMoviesAndSeries() {
        assertEquals("comedy", rank(emptyList()).first())
        assertEquals("crime", rank(habits()).first())
    }
    @Test fun shortVisitsAreNotPreferences() {
        assertEquals(rank(emptyList()), rank(habits().map { it.copy(watchedMs = 30_000) }))
    }
    @Test fun profileIsolationAlsoHoldsWhenHistoryContainsOtherProfiles() {
        assertEquals("crime", rank(habits(1) + habits(2, "Comedy"), 1).first())
        assertEquals("comedy", rank(habits(1) + habits(2, "Comedy"), 2).first())
        assertEquals(rank(emptyList()), rank(habits(1), 3))
    }
    @Test fun oldAndFutureSignalsAreIgnored() {
        val stale = habits().map { it.copy(lastWatchedAt = now - 91L * 86400000) }
        val future = habits().map { it.copy(lastWatchedAt = now + 86400000) }
        assertEquals(rank(emptyList()), rank(stale + future))
    }
    @Test fun translatedGenresShareAffinity() {
        assertEquals("crime", rank(habits(genre = "Krimi")).first())
        assertEquals("crime", rank(habits(genre = "Suç")).first())
    }
    @Test fun actualViewedMovieAndCompletedMovieAreNotRecommendedAgain() {
        val r = RecommendationRanker(1, "movie", habits(kind = "movie"), now, setOf("done"))
        listOf(item("seen-0"), item("done"), item("unseen")).forEach(r::offer)
        assertEquals(listOf("unseen"), r.results())
    }
    @Test fun newEpisodeBringsAnAlreadyWatchedSeriesBack() {
        val r = RecommendationRanker(1, "series", habits(), now)
        r.offer(item("seen-0", added = now / 1000 - 1000))
        r.offer(item("seen-1", added = now / 1000 - 1000))
        assertEquals(listOf("seen-1"), r.results())
    }
    @Test fun capAndDiscoverySurviveLargeCatalogs() {
        val r = RecommendationRanker(1, "movie", habits(), now)
        (1..10_000).forEach { r.offer(item("crime-$it", "Crime", 7.0)) }
        (1..60).forEach { r.offer(item("new-$it", "Comedy", 10.0)) }
        val result = r.results(500)
        assertEquals(50, result.size)
        assertEquals(50, result.toSet().size)
        assertTrue(result.take(4).all { it.startsWith("crime-") })
        assertTrue(result[4].startsWith("new-"))
    }
    @Test fun emptyAndIncompleteMetadataHaveDeterministicFallback() {
        assertTrue(rank(emptyList(), items = emptyList()).isEmpty())
        val items = listOf(RecommendationCandidate("a", "A", null, null, null, 0),
            RecommendationCandidate("b", "B", "", null, Double.NaN, 0))
        assertEquals(listOf("a", "b"), rank(emptyList(), items = items))
    }
}
