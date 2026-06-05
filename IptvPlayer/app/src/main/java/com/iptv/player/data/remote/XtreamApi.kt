/*
 * XtreamApi.kt
 * Retrofit interface for the Xtream Codes API. The base URL is the user's server
 * (e.g. http://host:port/). Stream playback URLs are built separately in
 * XtreamUrlBuilder since they don't go through player_api.php.
 */
package com.iptv.player.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface XtreamApi {

    @GET("player_api.php")
    suspend fun authenticate(
        @Query("username") username: String,
        @Query("password") password: String
    ): XtreamAuth

    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): List<XtreamCategory>

    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams"
    ): List<XtreamLiveStream>
}

/**
 * Builds the actual stream URLs Xtream uses for live playback:
 *   {server}/live/{username}/{password}/{streamId}.{ext}
 * TS is the most compatible container for live on weak devices.
 */
object XtreamUrlBuilder {
    fun liveUrl(
        serverUrl: String,
        username: String,
        password: String,
        streamId: Long,
        extension: String = "ts"
    ): String {
        val base = serverUrl.trimEnd('/')
        return "$base/live/$username/$password/$streamId.$extension"
    }
}
