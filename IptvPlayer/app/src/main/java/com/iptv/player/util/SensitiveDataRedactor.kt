package com.iptv.player.util

/**
 * Removes credentials and bearer material from diagnostics before it reaches
 * logcat, a share sheet, or the crash receiver.
 *
 * Xtream credentials appear both as query parameters and as path segments
 * (`/live/user/pass/id.ts`), so ordinary header redaction is not sufficient.
 * The function is deliberately pure and idempotent so every logging boundary
 * can apply it defensively.
 */
object SensitiveDataRedactor {

    private const val MASK = "<redacted>"

    /** Xtream `/<kind>/user/pass/...` paths, including catch-up and HLS variants. */
    private val xtreamPath = Regex(
        """(?i)(/(?:live|movie|series|timeshift|hls)/)[^/\s?#]+/[^/\s?#]+/""",
    )

    /**
     * Bare Xtream form without a kind prefix: `http(s)://host[:port]/user/pass/`
     * followed by a numeric stream id or a media file (`42.ts`, `42/`, `1.m3u8`).
     * Two segments directly under the host followed by anything else (a REST
     * path, an image, an API script) are left alone.
     */
    private val hostCredentialPath = Regex(
        """(?i)(https?://[^/\s?#]+/)[^/\s?#]+/[^/\s?#]+/""" +
            """(?=(?:\d+|[^/\s?#]+\.(?:ts|m3u8|m3u|mp4|mkv|avi|mov|flv|m4v|webm|mpd|mpg|mpeg))(?:[/?#\s]|$))""",
    )

    /** Query values, also when the whole query string is percent-encoded. */
    private val querySecret = Regex(
        """(?i)((?:[?&]|%3F|%26)(?:username|user|password|pass|token|access_token|refresh_token|api_key|apikey|key)(?:=|%3D))(?:(?!%26|%23)[^&#\s])+""",
    )
    private val urlUserInfo = Regex(
        """(?i)(https?://)[^/@\s]+:[^/@\s]+@""",
    )

    /** Header values anywhere in a line (log prefixes, `headers={...}` dumps). */
    private val sensitiveHeader = Regex(
        """(?i)(?<![\w-])((?:authorization|proxy-authorization|x-kululu-key|cookie|set-cookie)\s*:\s*)[^\r\n"}\],]+""",
    )
    private val jsonSecret = Regex(
        """(?i)("(?:username|user|password|pass|token|access_token|refresh_token|api_key|apikey|key)"\s*:\s*")[^"]*(")""",
    )

    fun redact(value: String): String {
        if (value.isEmpty()) return value
        return value
            .replace(xtreamPath) { "${it.groupValues[1]}$MASK/$MASK/" }
            .replace(hostCredentialPath) { "${it.groupValues[1]}$MASK/$MASK/" }
            .replace(querySecret) { "${it.groupValues[1]}$MASK" }
            .replace(urlUserInfo) { "${it.groupValues[1]}$MASK@" }
            .replace(sensitiveHeader) { "${it.groupValues[1]}$MASK" }
            .replace(jsonSecret) { "${it.groupValues[1]}$MASK${it.groupValues[2]}" }
    }
}
