package com.iptv.player.data.remote

import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class XtreamJsonTest {
    private val gson = XtreamJson.create()
    private val liveType = object : TypeToken<List<XtreamLiveStream>>() {}.type

    @Test fun `panel login accepts numeric and boolean flags and capacity one`() {
        listOf("1", "\"1\"", "true").forEach { auth ->
            val result = gson.fromJson("""{"user_info":{"auth":$auth,"status":"Active","active_cons":1,"max_connections":"1","exp_date":null},"server_info":{"port":8080,"https_port":"443","timezone":"Europe/Istanbul","timestamp_now":1788883200}}""", XtreamAuth::class.java)
            assertEquals(1, result.userInfo?.auth)
            assertEquals("1", result.userInfo?.activeConnections)
            assertEquals("1", result.userInfo?.maxConnections)
            assertEquals("8080", result.serverInfo?.port)
            assertNull(result.userInfo?.expDate)
        }
    }

    @Test fun `HTTP 200 malformed login does not authenticate`() {
        listOf("{}", "[]", "null", "{\"user_info\":{}}", "{\"user_info\":{\"auth\":2}}",
            "{\"user_info\":{\"auth\":\"garbage\"}}").forEach {
            assertThrows(JsonParseException::class.java) { gson.fromJson(it, XtreamAuth::class.java) }
        }
    }

    @Test fun `broken optional field does not drop valid live channels`() {
        val result: List<XtreamLiveStream> = gson.fromJson("""[
            {"stream_id":"11","name":"One","num":"bad","tv_archive":true,"category_id":7,"stream_icon":[]},
            {"stream_id":12,"name":"Two","num":2,"tv_archive_duration":"2"}
        ]""", liveType)
        assertEquals(listOf(11L, 12L), result.map { it.streamId })
        assertNull(result[0].num)
        assertNull(result[0].streamIcon)
        assertEquals(1, result[0].tvArchive)
    }

    @Test fun `bad identifiers are reported while valid rows survive`() {
        var rejected = 0
        val parser = XtreamJson.create { rejected += it }
        val result: List<XtreamLiveStream> = parser.fromJson("""[{"stream_id":"../12"},null,{"stream_id":-1},{"stream_id":12}]""", liveType)
        assertEquals(listOf(12L), result.map { it.streamId })
        assertEquals(3, rejected)
    }

    @Test fun `empty category succeeds but wrong envelope and all invalid fail`() {
        assertTrue(gson.fromJson<List<XtreamLiveStream>>("[]", liveType).isEmpty())
        listOf("{}", "null", "{\"error\":\"denied\"}", "[{\"stream_id\":\"bad\"}]").forEach {
            assertThrows(JsonParseException::class.java) { gson.fromJson<List<XtreamLiveStream>>(it, liveType) }
        }
    }

    @Test fun `series episode season map preserves numeric string identifiers`() {
        val value = gson.fromJson("""{"info":{"name":"Series"},"episodes":{"1":[{"id":41,"season":"1","episode_num":"2","container_extension":"mkv","info":{"duration_secs":"1800"}}]}}""", XtreamSeriesInfo::class.java)
        val ep = value.episodes!!["1"]!!.single()
        assertEquals("41", ep.id)
        assertEquals(2, ep.episodeNum)
        assertEquals(1800, ep.info?.durationSecs)
        assertTrue(gson.fromJson("""{"episodes":[]} """, XtreamSeriesInfo::class.java).episodes!!.isEmpty())
        assertThrows(JsonParseException::class.java) { gson.fromJson("{}", XtreamSeriesInfo::class.java) }
    }

    @Test fun `missing VOD details rejected and release date variants accepted`() {
        assertThrows(JsonParseException::class.java) { gson.fromJson("{}", XtreamVodInfo::class.java) }
        val info = gson.fromJson("""{"info":{"release_date":"2026-09-01"},"movie_data":{"stream_id":21,"container_extension":"mkv"}}""", XtreamVodInfo::class.java)
        assertEquals("21", info.movieData?.streamId)
        assertEquals("2026-09-01", info.info?.releaseDate)
    }

    @Test fun `catchup uses panel clock independently from TV timezone`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            assertEquals("2026-09-08:15-00", XtreamTime.formatCatchup(1788868800000L, "Europe/Istanbul"))
            assertNull(XtreamTime.formatCatchup(1788868800000L, "not-a-zone"))
            assertNull(XtreamTime.formatCatchup(1788868800000L, null))
        } finally { TimeZone.setDefault(previous) }
    }
}
