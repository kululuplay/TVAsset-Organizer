/*
 * XtreamApi.kt
 * Retrofit interface for the Xtream Codes API. The base URL is the user's server
 * (e.g. http://host:port/). Stream playback URLs are built separately in
 * XtreamUrlBuilder since they don't go through player_api.php.
 */
package com.iptv.player.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /**
     * Movie streams. When [categoryId] is supplied, Xtream returns only that
     * category's movies (`&category_id={id}`), which is how the app lazily loads
     * one category at a time instead of pulling the entire (often huge) catalog.
     */
    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("category_id") categoryId: String? = null,
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
        // Optional: scope to one category for lazy per-category loading. When null
        // the provider returns the full series catalog (legacy behaviour).
        @Query("category_id") categoryId: String? = null,
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
        streamId: Long, extension: String = "ts", directSource: String? = null,
    ): String = resolveDirectSource(serverUrl, directSource) ?: playbackUrl(
        serverUrl = serverUrl,
        kind = "live",
        username = username,
        password = password,
        id = streamId.toString(),
        extension = extension,
    )

    fun movieUrl(
        serverUrl: String, username: String, password: String,
        streamId: String, extension: String, directSource: String? = null,
    ): String = resolveDirectSource(serverUrl, directSource) ?: playbackUrl(
        serverUrl = serverUrl,
        kind = "movie",
        username = username,
        password = password,
        id = streamId,
        extension = extension,
    )

    fun seriesEpisodeUrl(
        serverUrl: String, username: String, password: String,
        episodeId: String, extension: String, directSource: String? = null,
    ): String = resolveDirectSource(serverUrl, directSource) ?: playbackUrl(
        serverUrl = serverUrl,
        kind = "series",
        username = username,
        password = password,
        id = episodeId,
        extension = extension,
    )

    fun xmltvUrl(
        serverUrl: String, username: String, password: String
    ): String = serverBase(serverUrl).newBuilder()
        .addPathSegment("xmltv.php")
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .build()
        .toString()

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
    ): String = serverBase(serverUrl).newBuilder()
        .addPathSegment("streaming")
        .addPathSegment("timeshift.php")
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .addQueryParameter("stream", streamId.toString())
        .addQueryParameter("start", startLocal)
        .addQueryParameter("duration", durationMinutes.coerceAtLeast(1).toString())
        .build()
        .toString()

    /** Retrofit-compatible base that preserves scheme, host, explicit port and path prefix. */
    fun apiBaseUrl(serverUrl: String): String = serverBase(serverUrl).toString()

    /**
     * Accept only HTTP(S) direct sources. Absolute, scheme-relative, root-relative
     * and panel-path-relative values are resolved without rewriting their port or
     * query. Invalid/custom-scheme values safely fall back to the Xtream route.
     */
    fun resolveDirectSource(serverUrl: String, directSource: String?): String? {
        val raw = directSource?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val panel = serverBase(serverUrl)
        val resolved = runCatching { panel.resolve(raw) }.getOrNull() ?: return null
        if (resolved.scheme != "http" && resolved.scheme != "https") return null
        // Credentials live in reviewed path/query fields; URI userinfo is both
        // ambiguous and easy to leak through platform diagnostics.
        if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) return null
        // Never let an HTTPS portal silently downgrade account-bearing playback.
        if (panel.isHttps && !resolved.isHttps) return null
        // A public panel may not redirect a TV into its LAN. Same-host private
        // deployments remain supported for legitimate home/provider setups.
        if (resolved.host != panel.host && isPrivateOrLoopbackLiteral(resolved.host)) return null
        return resolved.toString()
    }

    private fun isPrivateOrLoopbackLiteral(host: String): Boolean {
        val value = host.trim('[', ']').lowercase()
        if (value == "localhost" || value == "::1" || value.startsWith("fe80:")) return true
        if (value.startsWith("fc") || value.startsWith("fd")) return ':' in value
        val parts = value.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 0 ||
            parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 169 && parts[1] == 254) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    private fun playbackUrl(
        serverUrl: String,
        kind: String,
        username: String,
        password: String,
        id: String,
        extension: String,
    ): String = serverBase(serverUrl).newBuilder()
        .addPathSegment(kind)
        .addPathSegment(username)
        .addPathSegment(password)
        // Keep the id + suffix in one path segment. addPathSegment percent-encodes
        // slashes, spaces, # and ? instead of letting credentials reshape the URL.
        .addPathSegment("$id.${safeExtension(extension)}")
        .build()
        .toString()

    private fun safeExtension(value: String): String = value
        .trim()
        .removePrefix(".")
        .takeIf { it.length in 1..16 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
        ?: "mp4"

    private fun serverBase(serverUrl: String): HttpUrl {
        val parsed = serverUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid Xtream server URL")
        require(parsed.scheme == "http" || parsed.scheme == "https") {
            "Xtream server must use HTTP or HTTPS"
        }
        val cleanPath = parsed.encodedPath.trimEnd('/')
        return parsed.newBuilder()
            .query(null)
            .fragment(null)
            .encodedPath(if (cleanPath.isEmpty()) "/" else "$cleanPath/")
            .build()
    }
}
