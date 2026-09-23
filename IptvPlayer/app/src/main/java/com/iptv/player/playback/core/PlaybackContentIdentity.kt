package com.iptv.player.playback.core

import java.net.IDN
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Cross-device content identity. Account path segments, query strings and titles are never hashed. */
object PlaybackContentIdentity {
    private val numericId = Regex("[0-9]{1,18}")
    fun key(providerUrl: String?, kind: PlaybackContentKind, stableId: String?): String? {
        val id = stableId?.takeIf(numericId::matches)?.trimStart('0')?.ifEmpty { "0" } ?: return null
        val uri = runCatching { URI(providerUrl.orEmpty()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        val host = uri.host?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return null
        val canonicalHost = runCatching { IDN.toASCII(host.lowercase(Locale.ROOT)) }.getOrNull() ?: return null
        val port = uri.port.takeIf { it > 0 && it != 80 && it != 443 }?.let { ":$it" }.orEmpty()
        return MessageDigest.getInstance("SHA-256")
            .digest("kululu-content-v1|$canonicalHost$port|${kind.name}|$id".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun forStream(url: String?, kind: PlaybackContentKind, stableId: String? = null): String? {
        val uri = runCatching { URI(url.orEmpty()) }.getOrNull() ?: return null
        val segments = uri.path.orEmpty().trim('/').split('/')
        val expected = when (kind) {
            PlaybackContentKind.LIVE_TV, PlaybackContentKind.RADIO -> "live"
            PlaybackContentKind.VOD_MOVIE -> "movie"
            PlaybackContentKind.VOD_EPISODE -> "series"
            PlaybackContentKind.CATCH_UP -> null
        }
        val pathId = when {
            kind == PlaybackContentKind.CATCH_UP -> catchUpStreamId(uri, segments)
            segments.size == 4 && segments.first() == expected -> segments.last().substringBefore('.').takeIf(numericId::matches)
            else -> null
        }
        return key(url, kind, stableId?.takeIf(numericId::matches) ?: pathId)
    }

    /**
     * Xtream catch-up identifies the archived channel by its live stream id, so every device replaying that
     * archive compares under one key. Only `stream=<id>` of `/streaming/timeshift.php?...` or the last segment of
     * `/timeshift/<user>/<pass>/<minutes>/<start>/<id>.ts` is read; account, start and duration are never hashed.
     */
    private fun catchUpStreamId(uri: URI, segments: List<String>): String? = when {
        segments.lastOrNull().equals("timeshift.php", ignoreCase = true) -> uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("stream=") }?.substringAfter('=')?.takeIf(numericId::matches)
        segments.size == 6 && segments.first().equals("timeshift", ignoreCase = true) ->
            segments.last().substringBefore('.').takeIf(numericId::matches)
        else -> null
    }
}
