/*
 * M3uParser.kt
 * Streaming parser for #EXTM3U playlists. Extracts channel name, tvg-logo,
 * group-title and tvg-id from each #EXTINF entry. Reads line-by-line so even
 * very large playlists don't blow up memory on cheap sticks.
 */
package com.iptv.player.data.parser

import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import java.io.BufferedReader
import java.security.MessageDigest

object M3uParser {

    private val STREAM_SCHEMES = setOf("http", "https", "rtsp", "rtsps", "rtmp", "rtmps", "udp", "rtp")

    private fun isStreamUrl(line: String): Boolean = runCatching {
        val uri = java.net.URI(line)
        uri.scheme?.lowercase() in STREAM_SCHEMES && !uri.rawAuthority.isNullOrBlank()
    }.getOrDefault(false)

    fun hasPlaylistSignature(prefix: String): Boolean {
        val first = prefix.trimStart('﻿', ' ', '\t', '\r', '\n').lineSequence().firstOrNull().orEmpty()
        return first.startsWith("#EXTM3U", ignoreCase = true) || isStreamUrl(splitUrlOptions(first).first)
    }

    // Matches attribute pairs like tvg-logo="http://..." inside an #EXTINF line.
    private val ATTR_REGEX = Regex("([a-zA-Z0-9-]+)=\"([^\"]*)\"")

    fun parse(reader: BufferedReader): List<Channel> {
        val channels = ArrayList<Channel>()
        var pending: PendingInfo? = null
        var headerSeen = false
        var contentSeen = false
        // Ids are derived from stable playlist facts (tvg-id, else the URL), so
        // favorites and overrides survive a provider inserting a line above.
        // Only entries sharing the same key need a deterministic suffix.
        val seenKeys = HashMap<String, Int>()

        reader.forEachLine { raw ->
            val clean = raw.trim().trimStart('﻿').trim()
            if (clean.isNotBlank() && !clean.startsWith("#")) contentSeen = true
            if (clean.startsWith("<")) throw java.io.IOException("HTML/XML is not a playlist")
            // A single malformed line must never abort parsing the whole playlist.
            runCatching {
                val line = clean
                val (url, urlOptions) = if (line.isEmpty() || line.startsWith("#")) {
                    Pair(line, emptyMap<String, String>())
                } else {
                    splitUrlOptions(line)
                }
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> headerSeen = true
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        pending = parseExtInf(line)
                    }
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        // VLC/Kodi style per-entry option; only HTTP headers are used.
                        val header = vlcOptionHeader(line.substring("#EXTVLCOPT:".length))
                        val info = pending
                        if (header != null && info != null) pending = info.copy(headers = info.headers + header)
                    }
                    line.isEmpty() || line.startsWith("#") -> {
                        // Ignore comments / directives we don't use yet.
                    }
                    isStreamUrl(url) -> {
                        // A URL line completes the previously seen #EXTINF. If no
                        // #EXTINF preceded it, treat the bare URL as a minimal
                        // channel so the entry isn't silently dropped.
                        val info = pending ?: PendingInfo(
                            name = url.substringAfterLast('/').ifBlank { "Unknown" },
                            logo = null,
                            group = null,
                            tvgId = null
                        )
                        val key = info.tvgId ?: sha1(url)
                        val nth = (seenKeys[key] ?: 0) + 1
                        seenKeys[key] = nth
                        val id = when (nth) {
                            1 -> "m3u_$key"
                            // Same tvg-id twice (HD/SD variants): tie-break on the entry
                            // itself first so reordering those two keeps their ids.
                            2 -> "m3u_${key}_${sha1(info.name + "\n" + url).take(8)}"
                            else -> "m3u_${key}_${sha1(info.name + "\n" + url).take(8)}_$nth"
                        }
                        channels.add(
                            Channel(
                                id = id,
                                name = info.name,
                                streamUrl = url,
                                logoUrl = info.logo,
                                categoryId = info.group?.let { "grp_${it.hashCode()}" },
                                categoryName = info.group ?: "Uncategorized",
                                epgChannelId = info.tvgId,
                                number = null,
                                type = ContentType.LIVE,
                                headers = info.headers + urlOptions,
                            )
                        )
                        pending = null
                    }
                }
            }
        }
        if (!headerSeen && contentSeen && channels.isEmpty()) throw java.io.IOException("Malformed playlist response")
        return channels
    }

    /**
     * Splits a Kodi-style `url|User-Agent=x&Referer=y` line into the bare URL and
     * its header options. A `|` inside the URL proper is not valid there anyway.
     */
    internal fun splitUrlOptions(line: String): Pair<String, Map<String, String>> {
        val bar = line.indexOf('|')
        if (bar < 0) return line to emptyMap()
        val url = line.substring(0, bar).trim()
        val headers = LinkedHashMap<String, String>()
        line.substring(bar + 1).split('&').forEach { option ->
            val eq = option.indexOf('=')
            if (eq <= 0) return@forEach
            val name = canonicalHeader(option.substring(0, eq).trim()) ?: return@forEach
            val value = runCatching { java.net.URLDecoder.decode(option.substring(eq + 1).trim(), "UTF-8") }
                .getOrDefault(option.substring(eq + 1).trim())
            if (value.isNotEmpty()) headers[name] = value
        }
        return url to headers
    }

    private fun vlcOptionHeader(option: String): Pair<String, String>? {
        val eq = option.indexOf('=')
        if (eq <= 0) return null
        val name = when (option.substring(0, eq).trim().lowercase()) {
            "http-user-agent" -> "User-Agent"
            "http-referrer" -> "Referer"
            else -> return null
        }
        val value = option.substring(eq + 1).trim()
        return if (value.isEmpty()) null else name to value
    }

    /** Only headers a player can safely forward; anything else is dropped. */
    private fun canonicalHeader(name: String): String? = when (name.lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> null
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun parseExtInf(line: String): PendingInfo {
        val attrs = ATTR_REGEX.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
        // Channel name is everything after the first comma outside quotes (the one
        // that closes the attribute block), so "News, Weather & Sport" survives.
        val name = nameAfterAttributes(line).trim().ifEmpty {
            attrs["tvg-name"] ?: "Unknown"
        }
        return PendingInfo(
            name = name,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            group = attrs["group-title"]?.takeIf { it.isNotBlank() },
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
        )
    }

    private fun nameAfterAttributes(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return line.substring(i + 1)
            }
        }
        return ""
    }

    private data class PendingInfo(
        val name: String,
        val logo: String?,
        val group: String?,
        val tvgId: String?,
        val headers: Map<String, String> = emptyMap(),
    )
}
