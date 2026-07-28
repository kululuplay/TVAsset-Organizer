/*
 * VodModels.kt
 * Domain models for Video-On-Demand (movies) and Series (Season -> Episode).
 * Decoupled from Xtream/TMDB DTOs so the UI stays source-agnostic.
 */
package com.iptv.player.data.model

/** A single VOD movie. */
data class VodItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val durationSecs: Int? = null,
    val trailerUrl: String? = null,
    val tmdbId: String? = null,
    var isFavorite: Boolean = false
)

/** A series header (its episodes are loaded on demand). */
data class Series(
    val id: String,
    val name: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val trailerUrl: String? = null,
    val tmdbId: String? = null,
    var isFavorite: Boolean = false
)

/** A cast member shown on detail screens: name + optional head-shot URL. */
data class CastMember(
    val name: String,
    val photoUrl: String? = null
)

data class Season(
    val seriesId: String,
    val seasonNumber: Int,
    val episodes: List<Episode>
)

data class Episode(
    val id: String,
    val seriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val plot: String? = null,
    val durationSecs: Int? = null,
    val posterUrl: String? = null
)
