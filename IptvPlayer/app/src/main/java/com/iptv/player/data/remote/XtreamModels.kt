/*
 * XtreamModels.kt
 * Gson DTOs for the Xtream Codes player_api.php responses. Only the fields we
 * currently use are mapped; the rest are ignored by Gson.
 */
package com.iptv.player.data.remote

import com.google.gson.annotations.SerializedName

/** Response of action=null login: user_info + server_info. */
data class XtreamAuth(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerInfo?
)

data class UserInfo(
    @SerializedName("auth") val auth: Int?,
    @SerializedName("status") val status: String?,
    @SerializedName("active_cons") val activeConnections: String?,
    @SerializedName("max_connections") val maxConnections: String?,
    @SerializedName("exp_date") val expDate: String?
)

data class ServerInfo(
    @SerializedName("url") val url: String?,
    @SerializedName("port") val port: String?,
    @SerializedName("https_port") val httpsPort: String?,
    @SerializedName("server_protocol") val protocol: String?
)

data class XtreamCategory(
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("category_name") val categoryName: String?
)

data class XtreamLiveStream(
    @SerializedName("stream_id") val streamId: Long?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("num") val num: Int?,
    // 1 when the channel supports catch-up / timeshift.
    @SerializedName("tv_archive") val tvArchive: Int?,
    // How many days of archive the server keeps for this channel.
    @SerializedName("tv_archive_duration") val tvArchiveDuration: Int?,
    /** Provider/CDN playback URL when the panel exposes one explicitly. */
    @SerializedName("direct_source") val directSource: String? = null
)

// ---- VOD ----------------------------------------------------------------

data class XtreamVodStream(
    @SerializedName("stream_id") val streamId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName(value = "releasedate", alternate = ["releaseDate"]) val releaseDate: String?,
    @SerializedName("year") val year: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    // Unix timestamp (seconds, as string) when the movie was added to the catalog.
    @SerializedName("added") val added: String?,
    @SerializedName("direct_source") val directSource: String? = null
)

data class XtreamVodInfo(
    @SerializedName("info") val info: XtreamVodDetail?,
    @SerializedName("movie_data") val movieData: XtreamMovieData?
)

data class XtreamVodDetail(
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releasedate") val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("duration_secs") val durationSecs: Int?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?,
    @SerializedName("tmdb_id") val tmdbId: String?,
    @SerializedName("movie_image") val movieImage: String?
)

data class XtreamMovieData(
    @SerializedName("stream_id") val streamId: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("direct_source") val directSource: String? = null
)

// ---- Series -------------------------------------------------------------

data class XtreamSeriesItem(
    @SerializedName("series_id") val seriesId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName(value = "releaseDate", alternate = ["release_date", "releasedate"])
    val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?,
    // Unix timestamp (seconds, as string) when the series was last updated
    // (e.g. a new episode added). Used to surface freshly-updated series first.
    @SerializedName("last_modified") val lastModified: String?
)

data class XtreamSeriesInfo(
    @SerializedName("info") val info: XtreamSeriesDetail?,
    // Episodes keyed by season number string -> list of episodes.
    @SerializedName("episodes") val episodes: Map<String, List<XtreamEpisode>>?
)

data class XtreamSeriesDetail(
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName(value = "releaseDate", alternate = ["release_date", "releasedate"])
    val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?,
    @SerializedName("tmdb_id") val tmdbId: String?
)

data class XtreamEpisode(
    @SerializedName("id") val id: String?,
    @SerializedName("episode_num") val episodeNum: Int?,
    @SerializedName("season") val season: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("info") val info: XtreamEpisodeInfo?,
    @SerializedName("direct_source") val directSource: String? = null
)

data class XtreamEpisodeInfo(
    @SerializedName("plot") val plot: String?,
    @SerializedName("duration_secs") val durationSecs: Int?,
    @SerializedName("movie_image") val movieImage: String?,
    // A few Xtream forks nest direct_source under episode.info.
    @SerializedName("direct_source") val directSource: String? = null
)

// ---- EPG ----------------------------------------------------------------

data class XtreamEpgListing(
    @SerializedName("epg_listings") val listings: List<XtreamEpgEntry>?
)

data class XtreamEpgEntry(
    // Xtream base64-encodes these two fields.
    @SerializedName("title") val titleB64: String?,
    @SerializedName("description") val descriptionB64: String?,
    @SerializedName("start_timestamp") val startTimestamp: String?,
    @SerializedName("stop_timestamp") val stopTimestamp: String?
)
