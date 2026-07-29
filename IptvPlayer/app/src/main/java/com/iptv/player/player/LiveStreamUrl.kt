/*
 * LiveStreamUrl.kt
 * Pure helpers shared by every live playback surface so preview and fullscreen
 * resolve the same transport URL and route-memory key.
 */
package com.iptv.player.player

import com.iptv.player.data.model.StreamFormat
import java.net.URI
import java.security.MessageDigest
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
     * Stable route-memory key for a live channel/container/source combination.
     *
     * Only a short SHA-256 fingerprint of the URL host is retained. Credentials,
     * paths and rotating CDN query tokens never enter the key, while equal channel
     * ids from two different providers can no longer reuse each other's decoder
     * decision. The policy prefix also makes future routing changes explicit.
     */
    fun routeKey(channelId: String, format: StreamFormat, streamUrl: String): String =
        "$ROUTE_POLICY|${sourceFingerprint(streamUrl)}|$channelId|${format.name}"

    private fun sourceFingerprint(streamUrl: String): String {
        val host = runCatching { URI(streamUrl).host }
            .getOrNull()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown-source"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(host.toByteArray(Charsets.UTF_8))
        return bytes.take(SOURCE_FINGERPRINT_BYTES)
            .joinToString(separator = "") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private const val ROUTE_POLICY = "p2"
    private const val SOURCE_FINGERPRINT_BYTES = 6
}
