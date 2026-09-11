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

object M3uParser {

    private fun isStreamUrl(line: String): Boolean = runCatching {
        val uri = java.net.URI(line)
        uri.scheme?.lowercase() in setOf("http", "https", "rtsp", "rtsps", "rtmp", "rtmps", "udp", "rtp") &&
            !uri.rawAuthority.isNullOrBlank()
    }.getOrDefault(false)

    fun hasPlaylistSignature(prefix: String): Boolean {
        val first = prefix.trimStart('\uFEFF', ' ', '\t', '\r', '\n').lineSequence().firstOrNull().orEmpty()
        return first.startsWith("#EXTM3U", ignoreCase = true) || isStreamUrl(first)
    }

    // Matches attribute pairs like tvg-logo="http://..." inside an #EXTINF line.
    private val ATTR_REGEX = Regex("([a-zA-Z0-9-]+)=\"([^\"]*)\"")

    fun parse(reader: BufferedReader): List<Channel> {
        val channels = ArrayList<Channel>()
        var pending: PendingInfo? = null
        var autoId = 0
        var headerSeen = false
        var contentSeen = false

        reader.forEachLine { raw ->
            val clean = raw.trim().trimStart('\uFEFF')
            if (clean.isNotBlank() && !clean.startsWith("#")) contentSeen = true
            if (clean.startsWith("<")) throw java.io.IOException("HTML/XML is not a playlist")
            // A single malformed line must never abort parsing the whole playlist.
            runCatching {
                val line = clean
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> headerSeen = true
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        pending = parseExtInf(line)
                    }
                    line.isEmpty() || line.startsWith("#") -> {
                        // Ignore comments / directives we don't use yet.
                    }
                    isStreamUrl(line) -> {
                        // A URL line completes the previously seen #EXTINF. If no
                        // #EXTINF preceded it, treat the bare URL as a minimal
                        // channel so the entry isn't silently dropped.
                        val info = pending ?: PendingInfo(
                            name = line.substringAfterLast('/').ifBlank { "Unknown" },
                            logo = null,
                            group = null,
                            tvgId = null
                        )
                        channels.add(
                            Channel(
                                id = "m3u_${autoId++}_${info.tvgId ?: info.name.hashCode()}",
                                name = info.name,
                                streamUrl = line,
                                logoUrl = info.logo,
                                categoryId = info.group?.let { "grp_${it.hashCode()}" },
                                categoryName = info.group ?: "Uncategorized",
                                epgChannelId = info.tvgId,
                                number = null,
                                type = ContentType.LIVE
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

    private fun parseExtInf(line: String): PendingInfo {
        val attrs = ATTR_REGEX.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
        // Channel name is the text after the last comma.
        val name = line.substringAfterLast(',', "").trim().ifEmpty {
            attrs["tvg-name"] ?: "Unknown"
        }
        return PendingInfo(
            name = name,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            group = attrs["group-title"]?.takeIf { it.isNotBlank() },
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
        )
    }

    private data class PendingInfo(
        val name: String,
        val logo: String?,
        val group: String?,
        val tvgId: String?
    )
}
