/*
 * Models.kt
 * Plain domain models used across the UI and player layers. These are decoupled
 * from both the Room entities and the Xtream/M3U network DTOs so the rest of the
 * app never depends on a specific source format.
 */
package com.iptv.player.data.model

/** A content category / group (Live TV group, VOD category, etc.). */
data class Category(
    val id: String,
    val name: String,
    val type: ContentType = ContentType.LIVE
)

/** A single playable channel / stream. */
data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val epgChannelId: String? = null,
    val number: Int? = null,
    val type: ContentType = ContentType.LIVE,
    var isFavorite: Boolean = false
)

enum class ContentType { LIVE, VOD, SERIES }

/** Where the channel list came from. Stored so we can refresh on next launch. */
enum class SourceType { XTREAM, M3U_URL }

/** Player engine selection exposed in Settings. */
enum class PlayerMode {
    /** ExoPlayer first, automatically fall back to libVLC on failure. */
    AUTO,
    EXOPLAYER,
    VLC;

    companion object {
        fun fromName(value: String?): PlayerMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** Stored connection profile (one active source at a time for this milestone). */
data class SourceConfig(
    val type: SourceType,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = ""
)
