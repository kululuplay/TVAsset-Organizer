/*
 * M3uParserTest.kt
 * Pure-JVM coverage for the #EXTM3U parser. Locks the defensive behaviour the
 * app relies on: malformed, partial and oddly-ordered playlists must parse what
 * they can without ever throwing, and well-formed entries must keep their name,
 * logo, group-title and tvg-id.
 */
package com.iptv.player.data.parser

import com.iptv.player.data.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.reader().buffered())

    @Test
    fun parsesAttributesNameLogoGroupAndTvgId() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc.uk" tvg-logo="http://logo/bbc.png" group-title="News",BBC One
            http://stream/bbc
            """.trimIndent()
        )

        assertEquals(1, channels.size)
        val ch = channels[0]
        assertEquals("BBC One", ch.name)
        assertEquals("http://stream/bbc", ch.streamUrl)
        assertEquals("http://logo/bbc.png", ch.logoUrl)
        assertEquals("News", ch.categoryName)
        assertEquals("bbc.uk", ch.epgChannelId)
        assertEquals(ContentType.LIVE, ch.type)
    }

    @Test
    fun skipsBlankLinesAndComments() {
        val channels = parse(
            """
            #EXTM3U

            #EXT-X-SOMETHING:ignored

            #EXTINF:-1,Channel A
            http://stream/a

            """.trimIndent()
        )

        assertEquals(1, channels.size)
        assertEquals("Channel A", channels[0].name)
    }

    @Test
    fun missingUrlMeansNoChannel() {
        // An #EXTINF immediately followed by another #EXTINF (no URL between) must
        // not emit a dangling channel; only the completed entry counts.
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,Orphan
            #EXTINF:-1,Completed
            http://stream/completed
            """.trimIndent()
        )

        assertEquals(1, channels.size)
        assertEquals("Completed", channels[0].name)
    }

    @Test
    fun fallsBackToUnknownWhenNameMissing() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,
            http://stream/noname
            """.trimIndent()
        )

        assertEquals(1, channels.size)
        assertEquals("Unknown", channels[0].name)
    }

    @Test
    fun usesTvgNameWhenDisplayNameMissing() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="Fallback Name",
            http://stream/x
            """.trimIndent()
        )

        assertEquals("Fallback Name", channels[0].name)
    }

    @Test
    fun bareUrlWithoutExtinfStillBecomesChannel() {
        val channels = parse(
            """
            #EXTM3U
            http://stream/server/movie.mkv
            """.trimIndent()
        )

        assertEquals(1, channels.size)
        assertEquals("http://stream/server/movie.mkv", channels[0].streamUrl)
        assertEquals("movie.mkv", channels[0].name)
    }

    @Test
    fun blankAndWhitespaceLogoGroupTvgIdBecomeNull() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="" tvg-logo="" group-title="",Plain
            http://stream/plain
            """.trimIndent()
        )

        val ch = channels[0]
        assertNull(ch.logoUrl)
        assertNull(ch.epgChannelId)
        assertEquals("Uncategorized", ch.categoryName)
    }

    @Test
    fun emptyInputReturnsEmptyListNotThrow() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("#EXTM3U").isEmpty())
    }

    @Test
    fun multipleChannelsGetUniqueIds() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,A
            http://stream/a
            #EXTINF:-1,B
            http://stream/b
            """.trimIndent()
        )

        assertEquals(2, channels.size)
        assertEquals(2, channels.map { it.id }.toSet().size)
    }

    @Test
    fun idsSurviveInsertingALineAbove() {
        val before = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc.uk",BBC One
            http://stream/bbc
            #EXTINF:-1,No Tvg
            http://stream/notvg
            """.trimIndent()
        )
        val after = parse(
            """
            #EXTM3U
            #EXTINF:-1,Inserted
            http://stream/inserted
            #EXTINF:-1 tvg-id="bbc.uk",BBC One
            http://stream/bbc
            #EXTINF:-1,No Tvg
            http://stream/notvg
            """.trimIndent()
        )

        assertEquals(before.map { it.id }, after.drop(1).map { it.id })
        assertTrue(after[0].id !in before.map { it.id })
    }

    @Test
    fun duplicateTvgIdsGetDistinctDeterministicIds() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc.uk",BBC One HD
            http://stream/bbc-hd
            #EXTINF:-1 tvg-id="bbc.uk",BBC One SD
            http://stream/bbc-sd
            #EXTINF:-1 tvg-id="bbc.uk",BBC One SD
            http://stream/bbc-sd
            """.trimIndent()
        val first = parse(text)
        val second = parse(text)

        assertEquals(3, first.map { it.id }.toSet().size)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals("m3u_bbc.uk", first[0].id)
    }

    @Test
    fun commaInsideNameAndQuotedAttributeIsKept() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="A, B" group-title="News, Sport",News, Weather & Sport
            http://stream/news
            """.trimIndent()
        )

        assertEquals("News, Weather & Sport", channels[0].name)
        assertEquals("News, Sport", channels[0].categoryName)
    }

    @Test
    fun kodiUserAgentSuffixIsParsedIntoHeaders() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,Kodi
            http://stream/kodi.m3u8|User-Agent=Mozilla%2F5.0&Referer=http://ref/
            """.trimIndent()
        )

        assertEquals(1, channels.size)
        assertEquals("http://stream/kodi.m3u8", channels[0].streamUrl)
        assertEquals("Mozilla/5.0", channels[0].headers["User-Agent"])
        assertEquals("http://ref/", channels[0].headers["Referer"])
        assertTrue(M3uParser.hasPlaylistSignature("http://stream/kodi.m3u8|User-Agent=x"))
    }

    @Test
    fun extVlcOptLinesBecomeHeaders() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,VLC
            #EXTVLCOPT:http-user-agent=CustomUA/1.0
            #EXTVLCOPT:http-referrer=http://ref/
            #EXTVLCOPT:network-caching=1000
            http://stream/vlc
            """.trimIndent()
        )

        assertEquals(1, channels.size)
        assertEquals("VLC", channels[0].name)
        assertEquals(mapOf("User-Agent" to "CustomUA/1.0", "Referer" to "http://ref/"), channels[0].headers)
    }

    @Test
    fun crlfAndBomAreTolerated() {
        val channels = parse("﻿#EXTM3U\r\n#EXTINF:-1 tvg-id=\"a\",A\r\nhttp://stream/a\r\n#EXTINF:-1,B\r\nhttp://stream/b\r\n")

        assertEquals(listOf("A", "B"), channels.map { it.name })
        assertEquals("http://stream/a", channels[0].streamUrl)
        assertEquals("a", channels[0].epgChannelId)
    }

    @Test
    fun garbledExtinfDoesNotAbortRemainingEntries() {
        // A junk line that isn't a comment or recognisable #EXTINF should be
        // tolerated; the well-formed entry after it must still parse.
        val channels = parse(
            """
            #EXTM3U
            #EXTINF
            #EXTINF:-1,Good
            http://stream/good
            """.trimIndent()
        )

        assertTrue(channels.any { it.name == "Good" })
    }
}
