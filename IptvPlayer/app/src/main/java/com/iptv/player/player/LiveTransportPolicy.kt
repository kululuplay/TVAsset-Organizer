package com.iptv.player.player

import com.iptv.player.data.model.StreamFormat
import java.net.URI

/** Pure, bounded TS/HLS source recovery policy. */
internal object LiveTransportPolicy {

    enum class Failure { SOURCE_STARTUP, MANIFEST, DECODE, OTHER }

    data class Alternative(
        val url: String,
        val format: StreamFormat,
    )

    /**
     * Returns the one safe alternate container, never another endpoint.
     * Authentication/not-found responses are authoritative and must not be
     * retried under a different extension; neither should codec/output failures.
     */
    fun alternate(
        currentUrl: String,
        currentFormat: StreamFormat?,
        failure: Failure,
        httpStatus: Int? = null,
        alreadyTried: Boolean,
    ): Alternative? {
        if (alreadyTried) return null
        if (failure != Failure.SOURCE_STARTUP && failure != Failure.MANIFEST) return null
        if (httpStatus == 401 || httpStatus == 403 || httpStatus == 404) return null
        val format = currentFormat ?: LiveStreamUrl.detectFormat(currentUrl) ?: return null
        val alternateFormat = when (format) {
            StreamFormat.TS -> StreamFormat.HLS
            StreamFormat.HLS -> StreamFormat.TS
        }
        val candidate = LiveStreamUrl.applyFormat(currentUrl, alternateFormat)
        if (candidate == currentUrl || !sameEndpointAndResource(currentUrl, candidate)) return null
        return Alternative(candidate, alternateFormat)
    }

    private fun sameEndpointAndResource(first: String, second: String): Boolean = runCatching {
        val a = URI(first)
        val b = URI(second)
        if (!a.scheme.equals(b.scheme, ignoreCase = true)) return@runCatching false
        if (!a.rawAuthority.equals(b.rawAuthority, ignoreCase = true)) return@runCatching false
        if (a.rawQuery != b.rawQuery || a.rawFragment != b.rawFragment) return@runCatching false
        stripFormat(a.rawPath) == stripFormat(b.rawPath)
    }.getOrDefault(false)

    private fun stripFormat(path: String?): String? = when {
        path == null -> null
        path.endsWith(".m3u8", ignoreCase = true) -> path.dropLast(5)
        path.endsWith(".ts", ignoreCase = true) -> path.dropLast(3)
        else -> path
    }
}
