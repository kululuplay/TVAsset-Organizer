/*
 * FtsEntities.kt
 * Standalone FTS4 full-text search tables used for instant search across very
 * large catalogs (live channels, movies, series). They mirror only the id and
 * name of each item; the repository maintains them in lockstep with the content
 * tables (clear/merge) and queries join back to the content table by id. Kept
 * separate (not external-content) so maintenance is explicit and predictable.
 */
package com.iptv.player.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

@Fts4
@Entity(tableName = "vod_fts")
data class VodFtsEntity(
    val id: String,
    val name: String
)

@Fts4
@Entity(tableName = "series_fts")
data class SeriesFtsEntity(
    val id: String,
    val name: String
)

@Fts4
@Entity(tableName = "channels_fts")
data class ChannelFtsEntity(
    val id: String,
    val name: String
)
