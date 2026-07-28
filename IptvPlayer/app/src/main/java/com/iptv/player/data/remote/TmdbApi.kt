/*
 * TmdbApi.kt
 * Optional TMDB enrichment for movie/series posters, ratings and plots when the
 * IPTV source lacks metadata. Requires a user-supplied API key (set in Settings).
 * If no key is configured the repository simply skips TMDB calls.
 */
package com.iptv.player.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    @GET("3/search/movie")
    suspend fun searchMovie(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String = "en-US"
    ): TmdbSearchResponse

    @GET("3/search/tv")
    suspend fun searchTv(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String = "en-US"
    ): TmdbSearchResponse

    @GET("3/movie/{id}")
    suspend fun movieDetail(
        @Path("id") id: String,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US"
    ): TmdbResult

    @GET("3/tv/{id}")
    suspend fun tvDetail(
        @Path("id") id: String,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US"
    ): TmdbResult

    @GET("3/movie/{id}/credits")
    suspend fun movieCredits(
        @Path("id") id: String,
        @Query("api_key") apiKey: String
    ): TmdbCreditsResponse

    @GET("3/tv/{id}/credits")
    suspend fun tvCredits(
        @Path("id") id: String,
        @Query("api_key") apiKey: String
    ): TmdbCreditsResponse

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
        // Smaller crop for cast head-shots; full w500 is overkill for avatars.
        const val PROFILE_BASE = "https://image.tmdb.org/t/p/w185"

        fun posterUrl(path: String?): String? =
            path?.takeIf { it.isNotBlank() }?.let { IMAGE_BASE + it }

        fun backdropUrl(path: String?): String? =
            path?.takeIf { it.isNotBlank() }?.let { BACKDROP_BASE + it }

        fun profileUrl(path: String?): String? =
            path?.takeIf { it.isNotBlank() }?.let { PROFILE_BASE + it }
    }
}

data class TmdbSearchResponse(
    @SerializedName("results") val results: List<TmdbResult>?
)

data class TmdbResult(
    @SerializedName("id") val id: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("first_air_date") val firstAirDate: String?
)

data class TmdbCreditsResponse(
    @SerializedName("cast") val cast: List<TmdbCastMember>?
)

data class TmdbCastMember(
    @SerializedName("name") val name: String?,
    @SerializedName("character") val character: String?,
    @SerializedName("profile_path") val profilePath: String?,
    @SerializedName("order") val order: Int?
)
