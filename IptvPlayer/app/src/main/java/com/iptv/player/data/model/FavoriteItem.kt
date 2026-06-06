/*
 * FavoriteItem.kt
 * A unified favorite entry for the dedicated Favorites screen. Favorites of every
 * type (live channels, movies, series) live in the same table, distinguished by an
 * id prefix ("vod_", "series_", or a raw channel id). This model normalizes them
 * into one list so the Favorites grid can render and route each item correctly.
 */
package com.iptv.player.data.model

enum class FavoriteKind { CHANNEL, MOVIE, SERIES }

data class FavoriteItem(
    /** Raw id as stored in the favorites table (may be prefixed). */
    val favoriteId: String,
    val kind: FavoriteKind,
    val title: String,
    val posterUrl: String?,
    /** Unprefixed target id: channel id, vod id, or series id. */
    val targetId: String,
    /** Source category name, used for parental (adult) gating. */
    val categoryName: String? = null,
    /** Stream url for live channels (null for movies/series). */
    val streamUrl: String? = null
)
