/*
 * LibraryRepository.kt
 * The user's cross-catalog library: favorites, recents, Content Manager
 * (hidden categories / channel overrides / custom order) and catch-up lookups.
 * Reads categories through the live/VOD/series repositories so the three
 * catalogs share one ordering/visibility pipeline.
 */
package com.iptv.player.data.repository

import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.local.entity.ChannelOverrideEntity
import com.iptv.player.data.local.entity.FavoriteEntity
import com.iptv.player.data.local.entity.RecentEntity
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.FavoriteItem
import com.iptv.player.data.model.FavoriteKind
import com.iptv.player.data.model.ManagedCategory
import com.iptv.player.data.model.ManagedChannel
import com.iptv.player.data.model.Program
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.remote.XtreamUrlBuilder
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Reorders [cats] by the user's saved id [order]: ids present in the saved
 * order come first (in that order); any category not yet in the saved order
 * (e.g. newly added by the provider) keeps its default position at the end.
 */
internal fun applyCategoryOrder(cats: List<Category>, order: List<String>): List<Category> {
    if (order.isEmpty()) return cats
    val rank = order.withIndex().associate { (i, id) -> id to i }
    val (known, unknown) = cats.partition { it.id in rank }
    return known.sortedBy { rank[it.id]!! } + unknown
}

internal class LibraryRepository(
    db: AppDatabase,
    private val settings: SettingsStore,
    override val secureValues: SecureValueCodec,
    private val support: CatalogSyncSupport,
    private val live: LiveCatalogRepository,
    private val vod: VodCatalogRepository,
    private val series: SeriesCatalogRepository,
    private val epg: EpgRepository,
) : EntityMappers {

    private val channelDao = db.channelDao()
    private val channelOverrideDao = db.channelOverrideDao()
    private val favoriteDao = db.favoriteDao()
    private val recentDao = db.recentDao()
    private val vodDao = db.vodDao()
    private val seriesDao = db.seriesDao()

    /** Favorite live channels, minus those in categories hidden by Content Manager. */
    fun observeFavorites(): Flow<List<Channel>> =
        combine(
            favoriteDao.observeFavoriteChannels(),
            settings.hiddenCategories(ContentType.LIVE),
        ) { list, hidden ->
            list.filter { it.categoryId == null || it.categoryId !in hidden }
                .map { it.toModel(isFav = true) }
        }

    /**
     * All favorites across every content type (live channels, movies, series),
     * normalized into a single list for the dedicated Favorites screen. Rows whose
     * underlying content is no longer cached are skipped.
     */
    fun observeAllFavorites(): Flow<List<FavoriteItem>> =
        favoriteDao.observeAll().map { rows -> rows.mapNotNull { resolveFavorite(it.channelId) } }

    private suspend fun resolveFavorite(id: String): FavoriteItem? = when {
        id.startsWith("vod_") -> vodDao.getById(id.removePrefix("vod_"))?.let {
            FavoriteItem(id, FavoriteKind.MOVIE, it.name, it.posterUrl, it.id, it.categoryName)
        }
        id.startsWith("series_") -> seriesDao.getById(id.removePrefix("series_"))?.let {
            FavoriteItem(id, FavoriteKind.SERIES, it.name, it.posterUrl, it.id, it.categoryName)
        }
        else -> channelDao.getById(id)?.toModel(isFav = true)?.let {
            FavoriteItem(id, FavoriteKind.CHANNEL, it.name, it.logoUrl, it.id, it.categoryName, it.streamUrl)
        }
    }

    fun observeRecent(limit: Int = 20): Flow<List<Channel>> =
        recentDao.observeRecentChannels(limit).map { list -> list.map { it.toModel() } }

    /**
     * Channels of a type (including hidden) plus their manager state, in the
     * user's custom order. When [categoryId] is set, only that category's
     * channels are returned (used by the per-category channel editor).
     */
    fun observeManagedChannels(
        type: ContentType,
        categoryId: String? = null
    ): Flow<List<ManagedChannel>> =
        combine(
            channelDao.observeForManagement(type.name),
            favoriteDao.observeFavoriteChannels()
        ) { rows, favorites ->
            val favoriteIds = favorites.map { it.id }.toHashSet()
            rows.asSequence()
                .filter { categoryId == null || it.channel.categoryId == categoryId }
                .map {
                    ManagedChannel(
                        channel = it.channel.toModel(),
                        hidden = it.hidden,
                        isFavorite = it.channel.id in favoriteIds
                    )
                }
                .toList()
        }

    // ---- Managed / visible categories (Content Manager) -----------------

    /** Raw category list for [type] in source/default order (no user prefs). */
    private fun rawCategories(type: ContentType, radio: Int = -1): Flow<List<Category>> = when (type) {
        ContentType.LIVE -> live.observeCategories(ContentType.LIVE, radio)
        ContentType.VOD -> vod.observeVodCategories()
        ContentType.SERIES -> series.observeSeriesCategories()
    }

    /** Per-category content counts for [type]. */
    private fun categoryCounts(type: ContentType, radio: Int = -1): Flow<Map<String, Int>> = when (type) {
        ContentType.LIVE -> live.observeCategoryCounts(ContentType.LIVE, radio)
        ContentType.VOD -> vod.observeVodCategoryCounts()
        ContentType.SERIES -> series.observeSeriesCategoryCounts()
    }

    /**
     * All categories for [type] (incl. hidden) with their hidden flag + count,
     * in the user's custom order — drives the Content Manager editor.
     */
    fun observeManagedCategories(type: ContentType): Flow<List<ManagedCategory>> =
        combine(
            rawCategories(type),
            categoryCounts(type),
            settings.hiddenCategories(type),
            settings.categoryOrder(type)
        ) { cats, counts, hidden, order ->
            val withCounts = cats.map { it.copy(count = counts[it.id]) }
            applyCategoryOrder(withCounts, order).map { ManagedCategory(it, it.id in hidden) }
        }

    /**
     * Visible categories for [type]: hidden categories removed and the user's
     * custom order applied — used by the Live/Movies/Series browse rails.
     */
    fun observeVisibleCategories(type: ContentType, radio: Boolean = false): Flow<List<Category>> {
        // Radio split only applies to LIVE; other types ignore it (-1 = all).
        val radioFilter = if (type == ContentType.LIVE) (if (radio) 1 else 0) else -1
        return combine(
            rawCategories(type, radioFilter),
            categoryCounts(type, radioFilter),
            settings.hiddenCategories(type),
            settings.categoryOrder(type)
        ) { cats, counts, hidden, order ->
            val visible = cats.filter { it.id !in hidden }.map { it.copy(count = counts[it.id]) }
            applyCategoryOrder(visible, order)
        }
    }

    // ---- Channel overrides (hide / custom order) ------------------------

    suspend fun setChannelHidden(channelId: String, hidden: Boolean) = withContext(Dispatchers.IO) {
        val existing = channelOverrideDao.get(channelId)
        channelOverrideDao.upsert(
            ChannelOverrideEntity(channelId, hidden, existing?.sortOrder)
        )
    }

    /** Persists a custom order; index in [orderedIds] becomes the sort key. */
    suspend fun applyChannelOrder(orderedIds: List<String>) = withContext(Dispatchers.IO) {
        val existing = channelOverrideDao.getAll().associateBy { it.channelId }
        val updated = orderedIds.mapIndexed { index, id ->
            ChannelOverrideEntity(id, existing[id]?.hidden ?: false, index)
        }
        channelOverrideDao.upsertAll(updated)
    }

    // ---- Favorites / recents -------------------------------------------

    suspend fun toggleFavorite(channelId: String): Boolean = withContext(Dispatchers.IO) {
        val isFav = favoriteDao.isFavorite(channelId)
        if (isFav) favoriteDao.remove(channelId)
        else favoriteDao.add(FavoriteEntity(channelId, System.currentTimeMillis()))
        !isFav
    }

    /** Whether a generic content id (e.g. "vod_42", "series_7") is favorited. */
    suspend fun isContentFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        favoriteDao.isFavorite(id)
    }

    suspend fun markWatched(channelId: String) = withContext(Dispatchers.IO) {
        recentDao.add(RecentEntity(channelId, System.currentTimeMillis()))
        recentDao.trim(keep = 50)
    }

    // ---- Catch-up / timeshift -------------------------------------------

    /**
     * Past programs available in a channel's archive, newest first. Bounded by
     * the channel's advertised archive window and only programs that have already
     * started are returned.
     */
    suspend fun getCatchupPrograms(channel: Channel): List<Program> =
        withContext(Dispatchers.IO) {
            if (channel.catchupDays <= 0) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val from = now - channel.catchupDays.toLong() * 86_400_000L
            epg.getProgramsWindow(channel, from, now)
                .filter { it.startMs < now }
                .sortedByDescending { it.startMs }
        }

    /**
     * Builds a timeshift URL for a past [program] on [channel]. Only Xtream live
     * channels support catch-up; returns null otherwise.
     */
    suspend fun buildCatchupUrl(channel: Channel, program: Program): String? =
        withContext(Dispatchers.IO) {
            val config = settings.getSourceConfig() ?: return@withContext null
            if (config.type != SourceType.XTREAM) return@withContext null
            val streamId = channel.id.removePrefix("xt_live_").toLongOrNull()
                ?: return@withContext null
            val durationMin = ((program.stopMs - program.startMs + 59_999L) / 60_000L)
                .toInt().coerceAtLeast(1)
            val timezone = support.panelTimezones[config.serverUrl] ?: run {
                try {
                    val auth = support.buildXtreamApi(config.serverUrl).authenticate(config.username, config.password)
                    auth.serverInfo?.timezone?.also { support.panelTimezones[config.serverUrl] = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Logger.w("Xtream", "Catch-up unavailable: panel timezone could not be verified", failure)
                    null
                }
            }
            val start = com.iptv.player.data.remote.XtreamTime.formatCatchup(program.startMs, timezone)
                ?: return@withContext null
            XtreamUrlBuilder.catchupUrl(
                config.serverUrl, config.username, config.password,
                streamId, start, durationMin
            )
        }
}
