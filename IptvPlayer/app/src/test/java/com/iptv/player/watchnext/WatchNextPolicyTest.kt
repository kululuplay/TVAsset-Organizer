package com.iptv.player.watchnext

import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.ResumeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextPolicyTest {

    private fun item(
        id: String,
        updatedAt: Long,
        title: String = "Film $id",
        kind: ResumeKind = ResumeKind.MOVIE,
        positionMs: Long = 600_000L,
        durationMs: Long = 5_400_000L,
    ) = ContinueItem(
        contentId = id,
        kind = kind,
        title = title,
        posterUrl = null,
        streamUrl = "http://example.invalid/$id",
        positionMs = positionMs,
        durationMs = durationMs,
        vodId = null,
        seriesId = null,
        seasonNumber = 0,
        episodeNumber = 0,
        updatedAt = updatedAt,
    )

    private fun select(
        items: List<ContinueItem>,
        profileProtected: Boolean = false,
        adultCategoryIds: Set<String> = emptySet(),
    ) = WatchNextPolicy.select(items, profileProtected) { it.contentId in adultCategoryIds }
        .map { it.contentId }

    @Test
    fun `shows the five newest titles, newest first`() {
        val items = (1..8).map { item("vod_$it", updatedAt = it.toLong()) }.shuffled()

        assertEquals(listOf("vod_8", "vod_7", "vod_6", "vod_5", "vod_4"), select(items))
    }

    @Test
    fun `films and episodes are shown, catch-up is not`() {
        val items = listOf(
            item("vod_1", updatedAt = 3),
            item("ep_2", updatedAt = 2, kind = ResumeKind.EPISODE),
            item("catchup_7_1700000000000", updatedAt = 1, kind = ResumeKind.CATCHUP),
        )

        assertEquals(listOf("vod_1", "ep_2"), select(items))
    }

    @Test
    fun `an adult title or an adult category is never shown and a spare title takes its place`() {
        val items = listOf(
            item("vod_1", updatedAt = 9, title = "XXX Night"),
            item("vod_2", updatedAt = 8, title = "Harmless name"),
            item("vod_3", updatedAt = 7, title = "Erotik Film"),
        ) + (4..9).map { item("vod_$it", updatedAt = 10L - it) }

        assertEquals(
            listOf("vod_4", "vod_5", "vod_6", "vod_7", "vod_8"),
            select(items, adultCategoryIds = setOf("vod_2")),
        )
    }

    @Test
    fun `only started and unfinished titles of known length are shown`() {
        val items = listOf(
            item("vod_1", updatedAt = 5, positionMs = 0L),
            item("vod_2", updatedAt = 4, positionMs = 5_400_000L),
            item("vod_3", updatedAt = 3, durationMs = 0L),
            item("vod_4", updatedAt = 2, positionMs = 5_399_999L),
        )

        assertEquals(listOf("vod_4"), select(items))
    }

    @Test
    fun `a protected profile shows nothing`() {
        val items = (1..3).map { item("vod_$it", updatedAt = it.toLong()) }

        assertTrue(select(items, profileProtected = true).isEmpty())
    }

    @Test
    fun `a title whose id a card could not carry is not shown`() {
        assertTrue(select(listOf(item("vod_a b", updatedAt = 1))).isEmpty())
    }

    @Test
    fun `only a well-formed content id is accepted from a card`() {
        listOf("vod_123", "ep_45678", "vod_m3u_ab.cd-9").forEach {
            assertEquals(it, WatchNextPolicy.validContentId(it))
        }
        listOf(
            null, "", "123", "vod_", "ep_", "catchup_7_1700000000000", "VOD_1",
            "vod_1 OR 1=1", "vod_1'--", "vod_../../x", "ep_1\n", "vod_" + "9".repeat(65),
        ).forEach { assertNull(it.toString(), WatchNextPolicy.validContentId(it)) }
    }

    @Test
    fun `a card request is valid for one minute`() {
        assertTrue(WatchNextPolicy.isRequestFresh(requestedAtMs = 1_000_000L, nowMs = 1_000_000L))
        assertTrue(WatchNextPolicy.isRequestFresh(requestedAtMs = 1_000_000L, nowMs = 1_060_000L))
        assertFalse(WatchNextPolicy.isRequestFresh(requestedAtMs = 1_000_000L, nowMs = 1_060_001L))
        // A clock that moved backwards must not keep an old request alive.
        assertFalse(WatchNextPolicy.isRequestFresh(requestedAtMs = 1_000_000L, nowMs = 999_999L))
    }
}
