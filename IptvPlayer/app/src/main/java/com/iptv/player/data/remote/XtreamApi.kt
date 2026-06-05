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

    // ---- Live -----------------------------------------------------------

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

    // ---- VOD (movies) ---------------------------------------------------

    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): List<XtreamCategory>

    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams"
    ): List<XtreamVodStream>

    @GET("player_api.php")
    suspend fun getVodInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("vod_id") vodId: String,
        @Query("action") action: String = "get_vod_info"
    ): XtreamVodInfo

    // ---- Series ---------------------------------------------------------

    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): List<XtreamCategory>

    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series"
    ): List<XtreamSeriesItem>

    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("series_id") seriesId: String,
        @Query("action") action: String = "get_series_info"
    ): XtreamSeriesInfo

    // ---- EPG ------------------------------------------------------------

    /** Short EPG (now/next + a few entries) for one stream, base64-encoded titles. */
    @GET("player_api.php")
    suspend fun getShortEpg(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("stream_id") streamId: Long,
        @Query("limit") limit: Int = 10,
        @Query("action") action: String = "get_short_epg"
    ): XtreamEpgListing
}

/**
 * Builds the actual stream URLs Xtream uses for playback.
 *   live:    {server}/live/{user}/{pass}/{id}.{ext}    (ts = most compatible)
 *   movie:   {server}/movie/{user}/{pass}/{id}.{ext}
 *   series:  {server}/series/{user}/{pass}/{id}.{ext}
 *   xmltv:   {server}/xmltv.php?username=&password=     (full EPG dump)
 */
object XtreamUrlBuilder {

    fun liveUrl(
        serverUrl: String, username: String, password: String,
        streamId: Long, extension: String = "ts"
    ): String = "${serverUrl.trimEnd('/')}/live/$username/$password/$streamId.$extension"

    fun movieUrl(
        serverUrl: String, username: String, password: String,
        streamId: String, extension: String
    ): String = "${serverUrl.trimEnd('/')}/movie/$username/$password/$streamId.${extension.ifBlank { "mp4" }}"

    fun seriesEpisodeUrl(
        serverUrl: String, username: String, password: String,
        episodeId: String, extension: String
    ): String = "${serverUrl.trimEnd('/')}/series/$username/$password/$episodeId.${extension.ifBlank { "mp4" }}"

    fun xmltvUrl(
        serverUrl: String, username: String, password: String
    ): String = "${serverUrl.trimEnd('/')}/xmltv.php?username=$username&password=$password"

    /**
     * Catch-up / timeshift URL for a past live program. Uses the widely supported
     * timeshift.php endpoint:
     *   {server}/streaming/timeshift.php?username=&password=&stream={id}
     *       &start={yyyy-MM-dd:HH-mm}&duration={minutes}
     * [startLocal] must already be formatted in the panel's expected local form.
     */
    fun catchupUrl(
        serverUrl: String, username: String, password: String,
        streamId: Long, startLocal: String, durationMinutes: Int
    ): String =
        "${serverUrl.trimEnd('/')}/streaming/timeshift.php?username=$username" +
            "&password=$password&stream=$streamId&start=$startLocal&duration=$durationMinutes"
}
