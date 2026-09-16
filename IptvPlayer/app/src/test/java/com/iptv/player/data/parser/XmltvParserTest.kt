package com.iptv.player.data.parser

import com.iptv.player.data.model.Program
import org.junit.Assert.*
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

class XmltvParserTest {
    private val programme = """<programme channel="epg.11" start="20260908150000 +0300" stop="20260908160000+0300"><title>Haber &amp; News</title></programme>"""
    private fun parse(bytes: ByteArray): List<Program> = mutableListOf<Program>().also { result ->
        XmltvParser.parseWithParser(KXmlParser(), bytes.inputStream()) { result += it }
    }

    @Test fun `plain and gzip XMLTV preserve channel ids titles and UTC instants`() {
        val xml = "<tv>$programme</tv>".toByteArray()
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml) } }.toByteArray()
        val plain = parse(xml).single()
        assertEquals(plain, parse(zipped).single())
        assertEquals("epg.11", plain.epgChannelId)
        assertEquals("Haber & News", plain.title)
        assertEquals(1788868800000L, plain.startMs)
    }

    @Test fun `bad dates are skipped instead of normalized into another day`() {
        val result = parse("<tv>${programme.replace("20260908", "20260231")}$programme</tv>".toByteArray())
        assertEquals(1, result.size)
    }

    @Test fun `empty guide is valid but empty HTTP body html and truncated XML fail`() {
        assertTrue(parse("<tv/>".toByteArray()).isEmpty())
        listOf("", "<html/>", "<tv><programme>").forEach {
            assertThrows(Exception::class.java) { parse(it.toByteArray()) }
        }
    }

    @Test fun `timezone omitted means UTC regardless of television zone`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val input = programme.replace(" +0300", "").replace("+0300", "")
            assertEquals(1788879600000L, parse("<tv>$input</tv>".toByteArray()).single().startMs)
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun `first title and description win in multi-language programmes`() {
        val xml = """<tv><programme channel="epg.11" start="20260908150000 +0300" stop="20260908160000 +0300">""" +
            """<title lang="tr">Haber</title><title lang="en">News</title>""" +
            """<desc lang="tr">Açıklama</desc><desc lang="en">Description</desc></programme></tv>"""
        val program = parse(xml.toByteArray()).single()
        assertEquals("Haber", program.title)
        assertEquals("Açıklama", program.description)
    }

    @Test fun `large guide emits every programme through bounded callback parsing`() {
        var count = 0
        val xml = "<tv>" + programme.repeat(20_000) + "</tv>"
        XmltvParser.parseWithParser(KXmlParser(), xml.byteInputStream()) { count++ }
        assertEquals(20_000, count)
    }
}
