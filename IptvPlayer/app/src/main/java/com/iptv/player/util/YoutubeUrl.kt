/*
 * YoutubeUrl.kt
 * Small helper to pull the 11-char video id out of the many shapes a trailer
 * field can take: a bare id, or a real YouTube URL (?v=..., youtu.be/...,
 * /embed/..., /v/..., /shorts/...). It deliberately requires a YouTube host so a
 * direct media URL that merely contains a "v=..." token is NOT misread as a
 * YouTube link — those fall through to null and are played in the in-app player.
 */
package com.iptv.player.util

object YoutubeUrl {

    private val BARE_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HOST = Regex(
        "(?i)://(?:www\\.|m\\.)?(?:youtube\\.com|youtube-nocookie\\.com|youtu\\.be)/"
    )
    private val IN_URL = Regex("(?:v=|/embed/|youtu\\.be/|/v/|/shorts/)([A-Za-z0-9_-]{11})")

    /** Returns the YouTube video id, or null if [raw] is not a YouTube reference. */
    fun extractId(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        if (BARE_ID.matches(url)) return url
        // Only parse ids out of genuine YouTube hosts.
        if (!HOST.containsMatchIn(url)) return null
        return IN_URL.find(url)?.groupValues?.getOrNull(1)
    }

    /** True when [raw] points at a YouTube video we can play in the IFrame player. */
    fun isYoutube(raw: String?): Boolean = extractId(raw) != null

    /** Canonical watch URL for an id, used for the external-app fallback. */
    fun watchUrl(id: String): String = "https://www.youtube.com/watch?v=$id"
}
