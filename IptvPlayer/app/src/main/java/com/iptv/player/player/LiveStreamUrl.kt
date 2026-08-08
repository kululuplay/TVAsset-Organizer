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

    fun detectFormat(url: String): StreamFormat? {
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        val path = if (cut >= 0) url.substring(0, cut) else url
        return when {
            path.endsWith(".ts", ignoreCase = true) -> StreamFormat.TS
            path.endsWith(".m3u8", ignoreCase = true) -> StreamFormat.HLS
            else -> null
        }
    }

    /**
     * Stable route-memory key for a live channel/container/source combination.
     *
     * Only a short SHA-256 fingerprint of the normalized endpoint is retained.
     * Credentials, paths and rotating CDN query tokens never enter the key, while
     * different schemes/ports on the same host cannot reuse each other's decoder
     * or transport decision. The policy prefix makes future changes explicit.
     */
    fun routeKey(channelId: String, format: StreamFormat, streamUrl: String): String =
        "$ROUTE_POLICY|${sourceFingerprint(streamUrl)}|$channelId|${format.name}"

    /**
     * Key for the bounded TS/HLS winner memory. The configured format is part of
     * the namespace: changing the explicit Settings choice must never be silently
     * overridden by a winner learned under the previous choice.
     */
    fun transportKey(
        channelId: String,
        streamUrl: String,
        configuredFormat: StreamFormat,
    ): String =
        "$TRANSPORT_POLICY|${sourceFingerprint(streamUrl)}|$channelId|${configuredFormat.name}"

    fun routeKeyWithFormat(routeKey: String?, format: StreamFormat): String? {
        val key = routeKey ?: return null
        val marker = key.substringAfterLast('|', missingDelimiterValue = "")
        if (marker != StreamFormat.TS.name && marker != StreamFormat.HLS.name) return key
        return key.substringBeforeLast('|') + "|" + format.name
    }

    private fun sourceFingerprint(streamUrl: String): String {
        val endpoint = runCatching {
            val uri = URI(streamUrl)
            val scheme = uri.scheme
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val host = uri.host
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val effectivePort = when {
                uri.port >= 0 -> uri.port
                scheme == "https" -> 443
                scheme == "http" -> 80
                else -> -1
            }
            "$scheme://$host:$effectivePort"
        }.getOrNull() ?: "unknown-source"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(endpoint.toByteArray(Charsets.UTF_8))
        return bytes.take(SOURCE_FINGERPRINT_BYTES)
            .joinToString(separator = "") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private const val ROUTE_POLICY = "p3"
    private const val TRANSPORT_POLICY = "t2"
    private const val SOURCE_FINGERPRINT_BYTES = 6
}
