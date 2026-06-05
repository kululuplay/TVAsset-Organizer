/*
 * MediaEntities.kt
 * Room entities added for EPG, VOD, Series/Episodes, profiles, resume positions
 * and manual EPG id mappings. All indexed for fast list/lookup queries.
 */
package com.iptv.player.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "programs",
    indices = [Index("epgChannelId"), Index("startMs"), Index("stopMs")]
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val epgChannelId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val stopMs: Long
)

@Entity(
    tableName = "vod",
    indices = [Index("categoryId"), Index("addedAt")]
)
data class VodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val streamUrl: String,
    val posterUrl: String?,
    val categoryId: String?,
    val categoryName: String?,
    val rating: Double?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationSecs: Int?,
    val trailerUrl: String?,
    val tmdbId: String?,
    /** Unix seconds the movie was added to the catalog. */
    val addedAt: Long = 0,
    /** Index in the source's stream list, so lists keep the server's order. */
    val position: Int = 0,
    /** Index of this item's category in the source's category list. */
    val categoryPosition: Int = Int.MAX_VALUE
)

@Entity(
    tableName = "series",
    indices = [Index("categoryId"), Index("addedAt")]
)
data class SeriesEntity(
    @PrimaryKey val id: String,
    val name: String,
    val posterUrl: String?,
    val categoryId: String?,
    val categoryName: String?,
    val rating: Double?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val trailerUrl: String?,
    val tmdbId: String?,
    /** Unix seconds the series was last updated. */
    val addedAt: Long = 0,
    /** Index in the source's stream list, so lists keep the server's order. */
    val position: Int = 0,
    /** Index of this item's category in the source's category list. */
    val categoryPosition: Int = Int.MAX_VALUE
)

@Entity(
    tableName = "episodes",
    indices = [Index("seriesId")]
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val plot: String?,
    val durationSecs: Int?,
    val posterUrl: String?
)

/** Saved subscriptions / playlists (multi-profile support). */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceType: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val m3uUrl: String,
    val lockAdult: Boolean,
    val createdAt: Long
)

/**
 * Resume position (ms) for VOD movies and series episodes. Display + navigation
 * metadata is denormalized here so the "Continue Watching" rail and detail
 * screens can render and reopen content without another network/cache lookup.
 * contentId is prefixed: "vod_<id>" for movies, "ep_<id>" for episodes.
 */
@Entity(tableName = "resume", indices = [Index("updatedAt"), Index("seriesId")])
data class ResumeEntity(
    @PrimaryKey val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    /** "movie" or "episode". */
    val type: String = "movie",
    /** Primary display label: movie name, or the series name for an episode. */
    val title: String = "",
    val posterUrl: String? = null,
    /** Stream to play directly when resumed from the rail. */
    val streamUrl: String = "",
    /** Set for movies → reopen VodDetailActivity. */
    val vodId: String? = null,
    /** Set for episodes → reopen SeriesDetailActivity and group by series. */
    val seriesId: String? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0
)

/** Manual override of a channel's EPG id when tvg-id doesn't match. */
@Entity(tableName = "epg_mapping")
data class EpgMappingEntity(
    @PrimaryKey val channelId: String,
    val epgChannelId: String
)
