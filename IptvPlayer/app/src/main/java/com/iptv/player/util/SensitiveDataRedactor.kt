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

    private val xtreamPath = Regex(
        """(?i)(/(?:live|movie|series)/)[^/\s?#]+/[^/\s?#]+/""",
    )
    private val querySecret = Regex(
        """(?i)([?&](?:username|user|password|pass|token|access_token|refresh_token|api_key|apikey|key)=)[^&#\s]+""",
    )
    private val urlUserInfo = Regex(
        """(?i)(https?://)[^/@\s]+:[^/@\s]+@""",
    )
    private val sensitiveHeader = Regex(
        """(?im)^((?:authorization|proxy-authorization|x-kululu-key|cookie|set-cookie)\s*:\s*).+$""",
    )
    private val jsonSecret = Regex(
        """(?i)("(?:username|user|password|pass|token|access_token|refresh_token|api_key|apikey|key)"\s*:\s*")[^"]*(")""",
    )

    fun redact(value: String): String {
        if (value.isEmpty()) return value
        return value
            .replace(xtreamPath) { "${it.groupValues[1]}$MASK/$MASK/" }
            .replace(querySecret) { "${it.groupValues[1]}$MASK" }
            .replace(urlUserInfo) { "${it.groupValues[1]}$MASK@" }
            .replace(sensitiveHeader) { "${it.groupValues[1]}$MASK" }
            .replace(jsonSecret) { "${it.groupValues[1]}$MASK${it.groupValues[2]}" }
    }
}
