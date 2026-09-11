package com.iptv.player.player

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MediaTransportPolicyTest {
    @Test fun `HLS paths encoded extensions and transport query flags are blocked`() {
        for (url in listOf(
            "https://example.test/movie/1.m3u8?token=abc#x",
            "https://example.test/series/1.M3U8", "https://example.test/1%2em3u8",
            "https://example.test/live/1.ts?output=hls", "https://example.test/live/1?format=m3u8",
            "https://example.test/live/1?output=%6d3u8", "https://example.test/_hls/v2/1/playlist",
        )) {
            assertTrue(url, MediaTransportPolicy.isHlsUrl(url))
            try { MediaTransportPolicy.requireDirectMedia(url); fail(url) } catch (_: IOException) { }
        }
    }

    @Test fun `direct movie and episode formats and signed query tokens are unchanged`() {
        for (ext in listOf("mp4", "mkv", "avi", "webm", "mov", "ts")) {
            val url = "https://example.test/movie/1.$ext?token=hls&next=file.m3u8"
            assertFalse(MediaTransportPolicy.isHlsUrl(url))
            MediaTransportPolicy.requireDirectMedia(url)
        }
    }

    @Test fun `manifest MIME and disguised payloads are recognized`() {
        assertTrue(MediaTransportPolicy.isHlsContentType("Application/Vnd.Apple.Mpegurl; charset=UTF-8"))
        assertFalse(MediaTransportPolicy.isHlsContentType("video/mp2t"))
        val playlist = "\uFEFF\n#EXTM3U\n#EXT-X-VERSION:3".toByteArray()
        assertTrue(MediaTransportPolicy.isPlaylistHeader(playlist, playlist.size))
        val ts = ByteArray(188 * 3).apply { this[0] = 0x47; this[188] = 0x47 }
        assertFalse(MediaTransportPolicy.isPlaylistHeader(ts, 32))
    }

    @Test fun `VLC uses closed demux lists without adaptive or playlist fallback`() {
        assertEquals(":demux=ts,none", MediaTransportPolicy.VLC_LIVE_DEMUX)
        val demuxers = MediaTransportPolicy.VLC_FILE_DEMUX.removePrefix(":demux=").split(',')
        assertEquals("none", demuxers.last())
        assertTrue(demuxers.containsAll(listOf("mp4", "mkv", "avi", "ts")))
        assertFalse(demuxers.any { it in listOf("adaptive", "hls", "any", "avformat", "playlist") })
    }
}
