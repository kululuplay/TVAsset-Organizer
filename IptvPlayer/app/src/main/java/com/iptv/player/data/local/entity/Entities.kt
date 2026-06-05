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
    val type: String
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
