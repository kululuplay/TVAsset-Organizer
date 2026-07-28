/*
 * LiveStreamUrl.kt
 * Pure helpers shared by every live playback surface so preview and fullscreen
 * resolve the same transport URL and route-memory key.
 */
package com.iptv.player.player

import com.iptv.player.data.model.StreamFormat
import java.util.Locale

object LiveStreamUrl {

    /**
     * Applies the selected live container to a recognised Xtream live URL.
     *
     * Only a terminal `.ts` or `.m3u8` path extension is rewritten. Query strings
     * and fragments are preserved verbatim, while extensionless and non-live media
     * URLs are returned untouched.
     */
    fun applyFormat(url: String, format: StreamFormat): String {
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        val path = if (cut >= 0) url.substring(0, cut) else url
        val suffix = if (cut >= 0) url.substring(cut) else ""
        val lower = path.lowercase(Locale.US)
        val base = when {
            lower.endsWith(".ts") -> path.dropLast(3)
            lower.endsWith(".m3u8") -> path.dropLast(5)
            else -> return url
        }
        return "$base.${format.extension}$suffix"
    }

    /**
     * Stable route-memory key for a live channel/container pair.
     *
     * Deliberately excludes the stream URL because credentials and CDN tokens can
     * rotate without changing the channel's proven-good playback route.
     */
    fun routeKey(channelId: String, format: StreamFormat): String =
        "$channelId|${format.name}"
}
