/*
 * FtsEntities.kt
 * Standalone FTS4 full-text search tables used for instant search across very
 * large catalogs (live channels, movies, series). They mirror only the id and
 * name of each item; the repository maintains them in lockstep with the content
 * tables (clear/merge) and queries join back to the content table by id. Kept
 * separate (not external-content) so maintenance is explicit and predictable.
 *
 * unicode61 folds case and diacritics beyond ASCII ("Öğle" matches "ogle"), which
 * the default "simple" tokenizer cannot. Queries restrict MATCH to the name
 * column so id fragments ("xt_live_12") never match a search.
 */
package com.iptv.player.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "vod_fts")
data class VodFtsEntity(
    val id: String,
    val name: String
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "series_fts")
data class SeriesFtsEntity(
    val id: String,
    val name: String
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "channels_fts")
data class ChannelFtsEntity(
    val id: String,
    val name: String
)
