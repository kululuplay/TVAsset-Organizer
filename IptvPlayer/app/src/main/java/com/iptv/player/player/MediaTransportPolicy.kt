package com.iptv.player.player

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Playback policy shared by live, VOD and Cast; playlist imports are separate. */
object MediaTransportPolicy {
    class UnsupportedTransportException : IOException("HLS playback is disabled")
    fun isHlsUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull()
        val path = (uri?.path ?: url.substringBefore('?').substringBefore('#')).lowercase(Locale.US)
        if (path.endsWith(".m3u8") || path.endsWith(".m3u") || path.contains("/_hls/")) return true
        return uri?.rawQuery.orEmpty().split('&').any { part ->
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            key in setOf("output", "format", "type", "extension", "ext") &&
                value in setOf("hls", "m3u8", "m3u")
        }
    }

    fun isHlsContentType(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US) in setOf(
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
        )

    fun isPlaylistHeader(bytes: ByteArray, length: Int): Boolean =
        String(bytes, 0, length, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .startsWith("#EXTM3U")

    fun requireDirectMedia(url: String) {
        if (isHlsUrl(url)) throw UnsupportedTransportException()
    }

    // VLC's final 'none' stops module selection. Never let adaptive or playlist
    // demuxers sniff a disguised/redirected manifest, including a VOD fallback.
    // https://github.com/videolan/vlc/blob/3.0.x/src/modules/modules.c
    const val VLC_LIVE_DEMUX = ":demux=ts,none"
    const val VLC_FILE_DEMUX = ":demux=mp4,mkv,avi,asf,ogg,flac,mpgv,mpga,ts,es,wav,aiff,au,rawdv,none"

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value).lowercase(Locale.US)
}
