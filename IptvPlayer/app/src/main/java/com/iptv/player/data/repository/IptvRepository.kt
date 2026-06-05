/*
 * IptvRepository.kt
 * Single source of truth for channels/categories/favorites/recents. Fetches from
 * Xtream or M3U, caches into Room, and exposes reactive Flows to the UI.
 * Network/parse work runs on Dispatchers.IO; errors are mapped to AppError.
 */
package com.iptv.player.data.repository

import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.local.entity.ChannelEntity
import com.iptv.player.data.local.entity.FavoriteEntity
import com.iptv.player.data.local.entity.RecentEntity
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.parser.M3uParser
import com.iptv.player.data.remote.XtreamApi
import com.iptv.player.data.remote.XtreamUrlBuilder
import com.iptv.player.util.AppError
import com.iptv.player.util.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.HttpException
import java.io.IOException

class IptvRepository(
    private val db: AppDatabase,
    private val httpClient: OkHttpClient,
    private val retrofitBuilder: Retrofit.Builder
) {

    private val channelDao = db.channelDao()
    private val favoriteDao = db.favoriteDao()
    private val recentDao = db.recentDao()

    // ---- Reactive reads (UI layer) --------------------------------------

    fun observeCategories(type: ContentType): Flow<List<Category>> =
        channelDao.observeCategories(type.name).map { rows ->
            rows.map { Category(it.categoryId, it.categoryName ?: "Uncategorized", type) }
        }

    fun observeChannels(type: ContentType): Flow<List<Channel>> =
        channelDao.observeByType(type.name).map { list -> list.map { it.toModel() } }

    fun observeChannelsByCategory(type: ContentType, categoryId: String): Flow<List<Channel>> =
        channelDao.observeByCategory(type.name, categoryId).map { list -> list.map { it.toModel() } }

    fun observeFavorites(): Flow<List<Channel>> =
        favoriteDao.observeFavoriteChannels().map { list -> list.map { it.toModel(isFav = true) } }

    fun observeRecent(limit: Int = 20): Flow<List<Channel>> =
        recentDao.observeRecentChannels(limit).map { list -> list.map { it.toModel() } }

    fun search(query: String, type: ContentType): Flow<List<Channel>> =
        channelDao.search(query, type.name).map { list -> list.map { it.toModel() } }

    suspend fun getChannel(id: String): Channel? =
        channelDao.getById(id)?.toModel()

    // ---- Favorites / recents -------------------------------------------

    suspend fun toggleFavorite(channelId: String): Boolean = withContext(Dispatchers.IO) {
        val isFav = favoriteDao.isFavorite(channelId)
        if (isFav) favoriteDao.remove(channelId)
        else favoriteDao.add(FavoriteEntity(channelId, System.currentTimeMillis()))
        !isFav
    }

    suspend fun markWatched(channelId: String) = withContext(Dispatchers.IO) {
        recentDao.add(RecentEntity(channelId, System.currentTimeMillis()))
        recentDao.trim(keep = 50)
    }

    // ---- Source loading -------------------------------------------------

    /** Validate credentials / reachability without committing to cache. */
    suspend fun testSource(config: SourceConfig): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            when (config.type) {
                SourceType.XTREAM -> {
                    val api = buildXtreamApi(config.serverUrl)
                    val auth = api.authenticate(config.username, config.password)
                    val info = auth.userInfo
                        ?: return@withContext Outcome.Failure(AppError.CANNOT_CONNECT)
                    when {
                        info.auth == 0 || info.status.equals("Disabled", true) ->
                            Outcome.Failure(AppError.BAD_CREDENTIALS)
                        info.status.equals("Expired", true) ->
                            Outcome.Failure(AppError.SUBSCRIPTION_EXPIRED)
                        else -> Outcome.Success(Unit)
                    }
                }
                SourceType.M3U_URL -> {
                    // Use a ranged GET, not HEAD: many IPTV servers reject HEAD
                    // (405/403) while GET works fine. Range keeps it lightweight.
                    val request = Request.Builder()
                        .url(config.m3uUrl)
                        .header("Range", "bytes=0-1023")
                        .build()
                    httpClient.newCall(request).execute().use { resp ->
                        // 200 OK or 206 Partial Content both mean reachable.
                        if (resp.isSuccessful) Outcome.Success(Unit)
                        else Outcome.Failure(AppError.CANNOT_CONNECT)
                    }
                }
            }
        } catch (e: Exception) {
            Outcome.Failure(e.toAppError())
        }
    }

    /** Fetch channels from the source and replace the live cache. */
    suspend fun refreshLive(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        try {
            val channels = when (config.type) {
                SourceType.XTREAM -> loadXtreamLive(config)
                SourceType.M3U_URL -> loadM3u(config)
            }
            if (channels.isEmpty()) return@withContext Outcome.Failure(AppError.EMPTY_PLAYLIST)
            channelDao.clearType(ContentType.LIVE.name)
            channelDao.upsertAll(channels.map { it.toEntity() })
            Outcome.Success(channels.size)
        } catch (e: Exception) {
            Outcome.Failure(e.toAppError())
        }
    }

    private suspend fun loadXtreamLive(config: SourceConfig): List<Channel> {
        val api = buildXtreamApi(config.serverUrl)
        val categories = api.getLiveCategories(config.username, config.password)
            .associate { (it.categoryId ?: "") to (it.categoryName ?: "Uncategorized") }
        val streams = api.getLiveStreams(config.username, config.password)
        return streams.mapNotNull { s ->
            val id = s.streamId ?: return@mapNotNull null
            Channel(
                id = "xt_live_$id",
                name = s.name ?: "Unknown",
                streamUrl = XtreamUrlBuilder.liveUrl(
                    config.serverUrl, config.username, config.password, id
                ),
                logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                categoryId = s.categoryId,
                categoryName = categories[s.categoryId] ?: "Uncategorized",
                epgChannelId = s.epgChannelId?.takeIf { it.isNotBlank() },
                number = s.num,
                type = ContentType.LIVE
            )
        }
    }

    private fun loadM3u(config: SourceConfig): List<Channel> {
        val request = Request.Builder().url(config.m3uUrl).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty body")
            return body.charStream().buffered().use { M3uParser.parse(it) }
        }
    }

    private fun buildXtreamApi(serverUrl: String): XtreamApi {
        val base = serverUrl.trimEnd('/') + "/"
        return retrofitBuilder.baseUrl(base).build().create(XtreamApi::class.java)
    }

    // ---- Mapping helpers ------------------------------------------------

    private fun ChannelEntity.toModel(isFav: Boolean = false) = Channel(
        id = id, name = name, streamUrl = streamUrl, logoUrl = logoUrl,
        categoryId = categoryId, categoryName = categoryName,
        epgChannelId = epgChannelId, number = number,
        type = ContentType.valueOf(type), isFavorite = isFav
    )

    private fun Channel.toEntity() = ChannelEntity(
        id = id, name = name, streamUrl = streamUrl, logoUrl = logoUrl,
        categoryId = categoryId, categoryName = categoryName,
        epgChannelId = epgChannelId, number = number, type = type.name
    )

    private fun Exception.toAppError(): AppError = when (this) {
        is HttpException -> when (code()) {
            401, 403 -> AppError.BAD_CREDENTIALS
            else -> AppError.CANNOT_CONNECT
        }
        is IOException -> AppError.CANNOT_CONNECT
        else -> AppError.UNKNOWN
    }
}
