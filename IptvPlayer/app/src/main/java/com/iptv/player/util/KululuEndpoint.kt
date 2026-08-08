package com.iptv.player.util

/** Canonical first-party endpoint and upgrade compatibility for saved accounts. */
object KululuEndpoint {
    const val HTTPS_SERVER_URL = "https://kululu.live"

    private val legacyOrigin = Regex(
        pattern = "^http://kululu\\.live(?::8080)?(?=/|$)",
        option = RegexOption.IGNORE_CASE,
    )

    /**
     * Existing installations keep their encrypted source URL across updates.
     * Upgrade only our exact legacy endpoint; arbitrary provider/profile URLs
     * must remain untouched.
     */
    fun migrateLegacyServerUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim()
        return if (legacyOrigin.containsMatchIn(trimmed)) {
            legacyOrigin.replaceFirst(trimmed, HTTPS_SERVER_URL).trimEnd('/')
        } else {
            serverUrl
        }
    }

    /** Upgrade cached first-party stream/artwork URLs while preserving their path. */
    fun migrateLegacyAssetUrl(url: String?): String? {
        val value = url ?: return null
        val trimmed = value.trim()
        return if (legacyOrigin.containsMatchIn(trimmed)) {
            legacyOrigin.replaceFirst(trimmed, HTTPS_SERVER_URL)
        } else {
            value
        }
    }
}
