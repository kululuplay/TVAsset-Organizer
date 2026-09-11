package com.iptv.player.data.remote

import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

/** Shapes sampled from trial line 4564; text, URLs and account values replaced. */
class PanelContractFixtureTest {
    private val gson = XtreamJson.create { error("Valid panel fixture rejected $it rows") }
    private fun json(name: String): String = javaClass.getResourceAsStream("/panel-contract/$name.json")!!
        .bufferedReader().use { it.readText() }
    private fun <T> list(name: String, item: Class<T>): List<T> =
        gson.fromJson(json(name), TypeToken.getParameterized(List::class.java, item).type)

    @Test fun `real panel authentication and category shapes`() {
        val auth = gson.fromJson(json("auth"), XtreamAuth::class.java)
        assertEquals(1, auth.userInfo?.auth)
        assertEquals("1", auth.userInfo?.maxConnections)
        assertEquals("UTC", auth.serverInfo?.timezone)
        for (name in listOf("live-categories", "vod-categories", "series-categories")) {
            assertTrue(list(name, XtreamCategory::class.java).all { it.categoryId!!.toLong() > 0 })
        }
    }
    @Test fun `real live movie and series catalogue shapes`() {
        assertEquals(3, list("live-all", XtreamLiveStream::class.java).size)
        assertEquals(3, list("vod-streams", XtreamVodStream::class.java).size)
        assertEquals(3, list("series", XtreamSeriesItem::class.java).size)
    }
    @Test fun `real MKV details and episode map`() {
        val movie = gson.fromJson(json("vod-info"), XtreamVodInfo::class.java)
        assertEquals("1", movie.movieData?.streamId)
        assertEquals("mkv", movie.movieData?.containerExtension)
        val series = gson.fromJson(json("series-info"), XtreamSeriesInfo::class.java)
        assertTrue(series.episodes!!.values.flatten().isNotEmpty())
        assertTrue(series.episodes!!.values.flatten().all { it.id!!.toLong() > 0 })
    }
    @Test fun `real nonempty TV short guide and archive table`() {
        for (name in listOf("tv-short-epg", "tv-simple-data-table")) {
            val guide = gson.fromJson(json(name), XtreamEpgListing::class.java)
            assertTrue(guide.listings!!.isNotEmpty())
            assertEquals("Rml4dHVyZQ==", guide.listings!!.first().titleB64)
        }
    }
}
