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
    /** Unix seconds the movie was added to the catalog; newest sorts first. */
    val addedAt: Long = 0
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
    /** Unix seconds the series was last updated; newest sorts first. */
    val addedAt: Long = 0
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

/** Resume position (ms) for VOD movies and series episodes. */
@Entity(tableName = "resume")
data class ResumeEntity(
    @PrimaryKey val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

/** Manual override of a channel's EPG id when tvg-id doesn't match. */
@Entity(tableName = "epg_mapping")
data class EpgMappingEntity(
    @PrimaryKey val channelId: String,
    val epgChannelId: String
)
