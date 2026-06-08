/*
 * Entities.kt
 * Room entities for cached channels, favorites and recently-watched history.
 * Kept small and indexed so list queries stay fast on weak chipsets.
 */
package com.iptv.player.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    indices = [Index("categoryId"), Index("type")]
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val categoryId: String?,
    val categoryName: String?,
    val epgChannelId: String?,
    val number: Int?,
    val type: String,
    /** Catch-up archive window in days (0 = none). */
    val catchupDays: Int = 0,
    /** Index in the source's stream list, so lists keep the server's order. */
    val position: Int = 0,
    /** Index of this channel's category in the source's category list. */
    val categoryPosition: Int = Int.MAX_VALUE,
    /** True for radio stations (no video); kept off the Live TV page. */
    val isRadio: Boolean = false
)

/**
 * Per-user overrides for a channel that must SURVIVE a playlist refresh (the
 * channels table is wiped & rebuilt on every refresh). Holds the hidden flag and
 * a custom sort order set from the channel manager.
 */
@Entity(tableName = "channel_overrides")
data class ChannelOverrideEntity(
    @PrimaryKey val channelId: String,
    val hidden: Boolean = false,
    val sortOrder: Int? = null
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val channelId: String,
    val addedAt: Long
)

@Entity(tableName = "recent")
data class RecentEntity(
    @PrimaryKey val channelId: String,
    val watchedAt: Long
)
