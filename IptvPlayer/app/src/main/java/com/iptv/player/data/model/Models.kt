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
    val type: ContentType = ContentType.LIVE,
    /** Number of channels in this category (null = unknown / not shown). */
    val count: Int? = null
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
    var isFavorite: Boolean = false,
    /** Catch-up / archive window in days (0 = no timeshift available). */
    val catchupDays: Int = 0,
    /** 0-based index in the source's stream list, preserving server order. */
    val position: Int = 0,
    /** 0-based index of this channel's category in the source's category list. */
    val categoryPosition: Int = Int.MAX_VALUE
)

/** A channel paired with its manager state (hidden flag) for the channel editor. */
data class ManagedChannel(
    val channel: Channel,
    val hidden: Boolean,
    val isFavorite: Boolean = false
)

/** A category paired with its manager state (hidden flag) for the content editor. */
data class ManagedCategory(
    val category: Category,
    val hidden: Boolean
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
            entries.firstOrNull { it.name == value } ?: VLC
    }
}

/**
 * How aggressively to use the hardware decoder versus software (libVLC). In AUTO
 * the player starts on hardware and automatically drops a stream to software
 * decode when it greens / blanks or errors. Exposed in Settings.
 */
enum class DecoderMode {
    /** Hardware first, automatic software fallback on green/blank or failure. */
    AUTO,
    /** Always use the hardware decoder (no software fallback). */
    HARDWARE,
    /** Always decode in software (libVLC) — most compatible, heaviest on weak sticks. */
    SOFTWARE;

    companion object {
        fun fromName(value: String?): DecoderMode =
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
