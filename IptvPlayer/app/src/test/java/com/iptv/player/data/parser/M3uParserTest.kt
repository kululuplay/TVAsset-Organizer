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
