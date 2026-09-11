package com.iptv.player.data.parser

import org.junit.Assert.*
import org.junit.Test

class M3uContractTest {
    @Test fun `HTTP 200 html and empty bodies are not channels or successful login`() {
        assertFalse(M3uParser.hasPlaylistSignature(""))
        assertTrue(M3uParser.parse("".reader().buffered()).isEmpty())
        for (body in listOf("<html>Login</html>", "{\"auth\":0}", "Bad gateway")) {
            assertFalse(M3uParser.hasPlaylistSignature(body))
            assertThrows(java.io.IOException::class.java) { M3uParser.parse(body.reader().buffered()) }
        }
    }
    @Test fun `BOM empty playlist and tokenized stream preserve contract`() {
        assertTrue(M3uParser.parse("\uFEFF#EXTM3U\n".reader().buffered()).isEmpty())
        val url = "https://example.test:9443/path/7.m3u8?sig=a%2Fb%3D&x=1"
        val body = "\uFEFF#EXTM3U\n#EXTINF:-1 tvg-id=\"epg.7\",News\n$url\n"
        assertTrue(M3uParser.hasPlaylistSignature(body))
        val channel = M3uParser.parse(body.reader().buffered()).single()
        assertEquals(url, channel.streamUrl)
        assertEquals("epg.7", channel.epgChannelId)
    }
}
