/*
 * TmdbApi.kt
 * Optional TMDB enrichment for movie/series posters, ratings and plots when the
 * IPTV source lacks metadata. Requires a user-supplied API key (set in Settings).
 * If no key is configured the repository simply skips TMDB calls.
 */
package com.iptv.player.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
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

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

        fun posterUrl(path: String?): String? =
            path?.takeIf { it.isNotBlank() }?.let { IMAGE_BASE + it }
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
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("first_air_date") val firstAirDate: String?
)
