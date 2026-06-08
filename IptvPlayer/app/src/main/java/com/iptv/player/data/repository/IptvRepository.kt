/*
 * IptvRepository.kt
 * Single source of truth for all content: live channels, EPG, VOD, series,
 * favorites, recents, profiles and resume positions. Fetches from Xtream or
 * M3U (+ optional TMDB enrichment), caches into Room, and exposes reactive
 * Flows. Network/parse work runs on Dispatchers.IO; errors map to AppError.
 */
package com.iptv.player.data.repository

import android.util.Base64
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.local.entity.ChannelEntity
import com.iptv.player.data.local.entity.ChannelFtsEntity
import com.iptv.player.data.local.entity.ChannelOverrideEntity
import com.iptv.player.data.local.entity.EpgMappingEntity
import com.iptv.player.data.local.entity.EpisodeEntity
import com.iptv.player.data.local.entity.FavoriteEntity
import com.iptv.player.data.local.entity.ProfileEntity
import com.iptv.player.data.local.entity.ProgramEntity
import com.iptv.player.data.local.entity.RecentEntity
import com.iptv.player.data.local.entity.ResumeEntity
import com.iptv.player.data.local.entity.SeriesCategoryEntity
import com.iptv.player.data.local.entity.SeriesEntity
import com.iptv.player.data.local.entity.SeriesFtsEntity
import com.iptv.player.data.local.entity.VodCategoryEntity
import com.iptv.player.data.local.entity.VodEntity
import com.iptv.player.data.local.entity.VodFtsEntity
import com.iptv.player.data.model.AccountInfo
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentSort
import com.iptv.player.data.model.ContentType
import com.iptv.player.util.NewContentNotifier
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.DiagnosticResult
import com.iptv.player.data.model.FavoriteItem
import com.iptv.player.data.model.FavoriteKind
import com.iptv.player.data.model.Episode
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.ResumeMeta
import com.iptv.player.data.model.ManagedCategory
import com.iptv.player.data.model.ManagedChannel
import com.iptv.player.data.model.NowNext
import com.iptv.player.data.model.Profile
import com.iptv.player.data.model.Program
import com.iptv.player.data.model.Season
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.model.VodItem
import com.iptv.player.data.parser.M3uParser
import com.iptv.player.data.parser.XmltvParser
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.remote.TmdbApi
import com.iptv.player.data.remote.XtreamApi
import com.iptv.player.data.remote.XtreamUrlBuilder
import com.iptv.player.util.AppError
import com.iptv.player.util.Logger
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException
import java.net.InetAddress
import java.util.Locale

class IptvRepository(
    private val db: AppDatabase,
    private val httpClient: OkHttpClient,
    private val retrofitBuilder: Retrofit.Builder,
    private val settings: SettingsStore
) {

    private val channelDao = db.channelDao()
    private val channelOverrideDao = db.channelOverrideDao()
    private val favoriteDao = db.favoriteDao()
    private val recentDao = db.recentDao()
    private val epgDao = db.epgDao()
    private val vodDao = db.vodDao()
    private val vodCategoryDao = db.vodCategoryDao()
    private val seriesDao = db.seriesDao()
    private val seriesCategoryDao = db.seriesCategoryDao()
    private val profileDao = db.profileDao()
    private val resumeDao = db.resumeDao()
    private val watchedDao = db.watchedDao()
    private val epgMappingDao = db.epgMappingDao()
    private val vodFtsDao = db.vodFtsDao()
    private val seriesFtsDao = db.seriesFtsDao()
    private val channelFtsDao = db.channelFtsDao()

    /**
     * Shared Paging config. enablePlaceholders=false keeps memory bounded to the
     * loaded windows (the whole point on low-RAM TV boxes); a page of 60 fills a
     * TV poster grid comfortably while staying small.
     */
    private val pagingConfig = PagingConfig(pageSize = 60, enablePlaceholders = false)

    /**
     * Turns raw user input into a safe FTS4 MATCH expression: splits on
     * non-alphanumerics and appends a prefix wildcard to each token, AND-ed
     * together (e.g. "harry pot" -> "harry* pot*"). Returns "" when there's
     * nothing to match so callers can short-circuit to an empty result instead
     * of issuing an invalid empty MATCH (which SQLite rejects).
     */
    private fun toFtsQuery(raw: String): String =
        raw.trim().lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }

    // ---- Reactive reads (UI layer) --------------------------------------

    fun observeCategories(type: ContentType): Flow<List<Category>> =
        channelDao.observeCategories(type.name).map { rows ->
            rows.map { Category(it.categoryId, it.categoryName ?: "Uncategorized", type) }
        }

    fun observeChannels(type: ContentType): Flow<List<Channel>> =
        channelDao.observeByType(type.name).map { list -> list.map { it.toModel() } }

    fun observeChannelsByCategory(type: ContentType, categoryId: String): Flow<List<Channel>> =
        channelDao.observeByCategory(type.name, categoryId).map { list -> list.map { it.toModel() } }

    /** Channel counts per category id, used for the category row badges. */
    fun observeCategoryCounts(type: ContentType): Flow<Map<String, Int>> =
        channelDao.observeCategoryCounts(type.name).map { rows ->
            rows.associate { it.categoryId to it.count }
        }

    fun observeFavorites(): Flow<List<Channel>> =
        favoriteDao.observeFavoriteChannels().map { list -> list.map { it.toModel(isFav = true) } }

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

    fun search(query: String, type: ContentType): Flow<List<Channel>> {
        val match = toFtsQuery(query)
        if (match.isBlank()) return flowOf(emptyList())
        return channelDao.searchFts(match, type.name).map { list -> list.map { it.toModel() } }
    }

    suspend fun getChannel(id: String): Channel? = channelDao.getById(id)?.toModel()

    /** Live channels that advertise a catch-up / timeshift archive. */
    fun observeCatchupChannels(): Flow<List<Channel>> =
        channelDao.observeByType(ContentType.LIVE.name)
            .map { list -> list.map { it.toModel() }.filter { it.catchupDays > 0 } }

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
    private fun rawCategories(type: ContentType): Flow<List<Category>> = when (type) {
        ContentType.LIVE -> observeCategories(ContentType.LIVE)
        ContentType.VOD -> observeVodCategories()
        ContentType.SERIES -> observeSeriesCategories()
    }

    /** Per-category content counts for [type]. */
    private fun categoryCounts(type: ContentType): Flow<Map<String, Int>> = when (type) {
        ContentType.LIVE -> observeCategoryCounts(ContentType.LIVE)
        ContentType.VOD -> observeVodCategoryCounts()
        ContentType.SERIES -> observeSeriesCategoryCounts()
    }

    /**
     * Reorders [cats] by the user's saved id [order]: ids present in the saved
     * order come first (in that order); any category not yet in the saved order
     * (e.g. newly added by the provider) keeps its default position at the end.
     */
    private fun applyCategoryOrder(cats: List<Category>, order: List<String>): List<Category> {
        if (order.isEmpty()) return cats
        val rank = order.withIndex().associate { (i, id) -> id to i }
        val (known, unknown) = cats.partition { it.id in rank }
        return known.sortedBy { rank[it.id]!! } + unknown
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
    fun observeVisibleCategories(type: ContentType): Flow<List<Category>> =
        combine(
            rawCategories(type),
            categoryCounts(type),
            settings.hiddenCategories(type),
            settings.categoryOrder(type)
        ) { cats, counts, hidden, order ->
            val visible = cats.filter { it.id !in hidden }.map { it.copy(count = counts[it.id]) }
            applyCategoryOrder(visible, order)
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

    // ---- Source validation / live ---------------------------------------

    suspend fun testSource(config: SourceConfig): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            when (config.type) {
                SourceType.XTREAM -> {
                    val api = buildXtreamApi(config.serverUrl)
                    val info = api.authenticate(config.username, config.password).userInfo
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
                    val request = Request.Builder()
                        .url(config.m3uUrl)
                        .header("Range", "bytes=0-1023")
                        .build()
                    httpClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) Outcome.Success(Unit)
                        else Outcome.Failure(AppError.CANNOT_CONNECT)
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            Outcome.Failure(e.toAppError())
        }
    }

    suspend fun refreshLive(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        try {
            val channels = when (config.type) {
                SourceType.XTREAM -> loadXtreamLive(config)
                SourceType.M3U_URL -> loadM3u(config)
            }
            if (channels.isEmpty()) return@withContext Outcome.Failure(AppError.EMPTY_PLAYLIST)
            // Stamp each channel with its index in the source list so the visible
            // order matches what the server delivered.
            val ordered = channels.mapIndexed { index, ch -> ch.copy(position = index) }
            // Count genuinely-new live channels (Xtream ids are stable) so the Live
            // screen can flash a "N new channels" notice. Captured before the wipe
            // below; skipped on the very first load (no prior data) to avoid a
            // false popup, and skipped for M3U whose ids aren't refresh-stable.
            val newLiveCount = if (config.type == SourceType.XTREAM) {
                val existing = channelDao.idsForType(ContentType.LIVE.name).toSet()
                if (existing.isEmpty()) 0 else ordered.count { it.id !in existing }
            } else 0
            // Replace channels + their search index atomically so live search never
            // sees a half-built index (or an empty one) if this is interrupted.
            // Insert in chunks: large Xtream accounts / M3U lists can hold tens of
            // thousands of channels. Mapping + inserting all at once spikes memory and
            // can OOM on low-RAM TV boxes, so bound the peak by batching.
            db.withTransaction {
                channelDao.clearType(ContentType.LIVE.name)
                ordered.chunked(1000).forEach { batch ->
                    channelDao.upsertAll(batch.map { it.toEntity() })
                }
                channelFtsDao.clearAll()
                ordered.chunked(1000).forEach { batch ->
                    channelFtsDao.insertAll(batch.map { ChannelFtsEntity(it.id, it.name) })
                }
            }
            if (newLiveCount > 0) NewContentNotifier.addLive(newLiveCount)
            Outcome.Success(ordered.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            Outcome.Failure(e.toAppError())
        }
    }

    private suspend fun loadXtreamLive(config: SourceConfig): List<Channel> {
        val api = buildXtreamApi(config.serverUrl)
        val categoryList = api.getLiveCategories(config.username, config.password)
        val categories = categoryList
            .associate { (it.categoryId ?: "") to (it.categoryName ?: "Uncategorized") }
        // Index each category by its position in the server's category list so the
        // UI can present categories in the same order the provider returned them.
        val categoryOrder = categoryList
            .mapIndexedNotNull { index, c -> c.categoryId?.let { it to index } }
            .toMap()
        return api.getLiveStreams(config.username, config.password).mapNotNull { s ->
            val id = s.streamId ?: return@mapNotNull null
            Channel(
                id = "xt_live_$id",
                name = s.name ?: "Unknown",
                streamUrl = XtreamUrlBuilder.liveUrl(config.serverUrl, config.username, config.password, id),
                logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                categoryId = s.categoryId,
                categoryName = categories[s.categoryId] ?: "Uncategorized",
                epgChannelId = s.epgChannelId?.takeIf { it.isNotBlank() },
                number = s.num,
                type = ContentType.LIVE,
                catchupDays = if (s.tvArchive == 1) (s.tvArchiveDuration ?: 7).coerceAtLeast(1) else 0,
                categoryPosition = categoryOrder[s.categoryId] ?: Int.MAX_VALUE
            )
        }
    }

    private fun loadM3u(config: SourceConfig): List<Channel> {
        val request = Request.Builder().url(config.m3uUrl).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty body")
            val parsed = body.charStream().buffered().use { M3uParser.parse(it) }
            // M3U has no separate category list, so category order is the order in
            // which each group first appears in the playlist.
            val categoryOrder = LinkedHashMap<String?, Int>()
            parsed.forEach { c -> categoryOrder.getOrPut(c.categoryId) { categoryOrder.size } }
            return parsed.map { c ->
                c.copy(categoryPosition = categoryOrder[c.categoryId] ?: Int.MAX_VALUE)
            }
        }
    }

    // ---- VOD ------------------------------------------------------------

    fun observeVod(): Flow<List<VodItem>> = vodDao.observeAll().map { it.map { e -> e.toModel() } }

    /** Latest [limit] movies by added date, for the "Recently added" rail. */
    fun observeRecentVod(limit: Int): Flow<List<VodItem>> =
        vodDao.observeRecent(limit).map { it.map { e -> e.toModel() } }

    fun observeVodByCategory(categoryId: String): Flow<List<VodItem>> =
        vodDao.observeByCategory(categoryId).map { it.map { e -> e.toModel() } }

    /**
     * Movie categories, read from the dedicated [vod_categories] table so the rail
     * is available immediately after login — before any category's movies have
     * been lazily downloaded.
     */
    fun observeVodCategories(): Flow<List<Category>> =
        vodCategoryDao.observeAll().map { rows ->
            rows.map { Category(it.id, it.name, ContentType.VOD) }
        }

    /** Movie count per category id, used for the category row badges. */
    fun observeVodCategoryCounts(): Flow<Map<String, Int>> =
        vodDao.observeCategoryCounts().map { rows -> rows.associate { it.categoryId to it.count } }

    fun searchVod(query: String): Flow<List<VodItem>> =
        vodDao.search(query).map { it.map { e -> e.toModel() } }

    // ---- Paging 3 (bounded movie lists) ---------------------------------

    /** Whole movie cache, newest first — the "Recently added" default view. */
    fun pagingRecentVod(): Flow<PagingData<VodItem>> =
        Pager(pagingConfig) { vodDao.pagingRecent() }
            .flow.map { data -> data.map { it.toModel() } }

    fun pagingVodByCategory(categoryId: String): Flow<PagingData<VodItem>> =
        Pager(pagingConfig) { vodDao.pagingByCategory(categoryId) }
            .flow.map { data -> data.map { it.toModel() } }

    /** Whole movie cache in the requested [sort] order. */
    fun pagingVodAll(sort: ContentSort): Flow<PagingData<VodItem>> =
        Pager(pagingConfig) {
            when (sort) {
                ContentSort.RECENT -> vodDao.pagingRecent()
                ContentSort.NAME -> vodDao.pagingAllByName()
                ContentSort.RATING -> vodDao.pagingAllByRating()
                ContentSort.YEAR -> vodDao.pagingAllByYear()
            }
        }.flow.map { data -> data.map { it.toModel() } }

    /** One category's movies in the requested [sort] order. */
    fun pagingVodByCategory(categoryId: String, sort: ContentSort): Flow<PagingData<VodItem>> =
        Pager(pagingConfig) {
            when (sort) {
                ContentSort.RECENT -> vodDao.pagingByCategory(categoryId)
                ContentSort.NAME -> vodDao.pagingCategoryByName(categoryId)
                ContentSort.RATING -> vodDao.pagingCategoryByRating(categoryId)
                ContentSort.YEAR -> vodDao.pagingCategoryByYear(categoryId)
            }
        }.flow.map { data -> data.map { it.toModel() } }

    /** Instant FTS search, paged. Empty input yields an empty page (no MATCH). */
    fun pagingVodSearch(query: String): Flow<PagingData<VodItem>> {
        val match = toFtsQuery(query)
        if (match.isBlank()) return flowOf(PagingData.empty())
        return Pager(pagingConfig) { vodDao.pagingSearch(match) }
            .flow.map { data -> data.map { it.toModel() } }
    }
    /**
     * Fetches and caches only the movie *categories* (not the movies themselves).
     * This is cheap and lets the Movies rail render immediately. The per-category
     * [loaded] flag is preserved across refreshes so re-syncing the category list
     * never forces a re-download of categories whose movies are already cached.
     */
    suspend fun refreshVodCategories(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        try {
            val api = buildXtreamApi(config.serverUrl)
            val catList = api.getVodCategories(config.username, config.password)
            val alreadyLoaded = vodCategoryDao.loadedIds().toSet()
            val categories = catList.mapIndexedNotNull { index, c ->
                val id = c.categoryId ?: return@mapIndexedNotNull null
                VodCategoryEntity(
                    id = id,
                    name = c.categoryName ?: "Uncategorized",
                    position = index,
                    loaded = id in alreadyLoaded
                )
            }
            vodCategoryDao.upsertAll(categories)
            Outcome.Success(categories.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            Outcome.Failure(e.toAppError())
        }
    }

    /** True if this category's movies have already been downloaded into the cache. */
    suspend fun isVodCategoryLoaded(categoryId: String): Boolean = withContext(Dispatchers.IO) {
        vodCategoryDao.getById(categoryId)?.loaded == true
    }

    /**
     * Lazily downloads a single movie category's movies and *merges* them into the
     * cache (no full-table wipe), so other already-downloaded categories stay
     * intact. Skips the network entirely when the category is already loaded
     * unless [force] is set. Marks the category loaded on success.
     */
    suspend fun refreshVodCategory(
        config: SourceConfig,
        categoryId: String,
        force: Boolean = false
    ): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        val category = vodCategoryDao.getById(categoryId)
        if (!force && category?.loaded == true) return@withContext Outcome.Success(0)
        try {
            val api = buildXtreamApi(config.serverUrl)
            val catName = category?.name ?: "Uncategorized"
            val catPosition = category?.position ?: Int.MAX_VALUE
            val items = api.getVodStreams(config.username, config.password, categoryId)
                .mapNotNull { s ->
                    val id = s.streamId ?: return@mapNotNull null
                    VodEntity(
                        id = id,
                        name = s.name ?: "Unknown",
                        streamUrl = XtreamUrlBuilder.movieUrl(
                            config.serverUrl, config.username, config.password, id,
                            s.containerExtension ?: "mp4"
                        ),
                        posterUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                        categoryId = s.categoryId ?: categoryId,
                        categoryName = catName,
                        rating = s.rating?.toDoubleOrNull(),
                        plot = null, cast = null, director = null, genre = null,
                        releaseDate = null, durationSecs = null, trailerUrl = null, tmdbId = null,
                        addedAt = s.added?.trim()?.toLongOrNull() ?: 0L,
                        categoryPosition = catPosition
                    )
                }
            // How many of these are genuinely new to the cache. Only meaningful on a
            // forced re-check of an already-loaded category (the launch sweep): a first
            // load has no prior rows, so everything would look "new". Guard on
            // categoryId so a backend that ignores category filtering and returns the
            // whole catalog can't inflate this category's count.
            val existingIds = vodDao.idsForCategory(categoryId).toSet()
            val newCount = items.count { it.categoryId == categoryId && it.id !in existingIds }
            // Merge (REPLACE on id) — never clear, so other categories remain
            // cached. Keep the FTS index in lockstep (drop this batch's old rows
            // then re-insert), all atomically so search never sees a half index.
            db.withTransaction {
                vodDao.upsertAll(items)
                vodFtsDao.deleteByIds(items.map { it.id })
                vodFtsDao.insertAll(items.map { VodFtsEntity(it.id, it.name) })
            }
            vodCategoryDao.markLoaded(categoryId)
            // On a forced launch sweep, return the genuine new-count so the caller can
            // aggregate across categories and notify once; non-forced loads keep the
            // legacy item-count contract.
            Outcome.Success(if (force) newCount else items.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            Outcome.Failure(e.toAppError())
        }
    }

    /**
     * Best-effort prefetch of the first movie category so the "Recently added"
     * view has content right after the splash, without pulling the whole catalog.
     */
    suspend fun prefetchFirstVodCategory(config: SourceConfig): Outcome<Int> =
        withContext(Dispatchers.IO) {
            val first = vodCategoryDao.getAll().firstOrNull()
                ?: return@withContext Outcome.Success(0)
            refreshVodCategory(config, first.id)
        }

    /**
     * Re-syncs every movie category the user has already opened (loaded == true)
     * so newly added movies surface on each app launch. Force-refreshes via upsert
     * (REPLACE on id) — existing items stay put (no flicker), only new ones appear.
     * Best-effort: a single category's failure never aborts the rest. Xtream only.
     */
    suspend fun refreshLoadedVodCategories(config: SourceConfig): Outcome<Int> =
        withContext(Dispatchers.IO) {
            if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
            var added = 0
            for (id in vodCategoryDao.loadedIds()) {
                when (val r = refreshVodCategory(config, id, force = true)) {
                    is Outcome.Success -> added += r.data
                    is Outcome.Failure -> Unit // keep going; best-effort per category
                }
            }
            // Notify the Movies screen once with the launch's total new count, so it
            // shows a single "N new movies" popup instead of one per category.
            if (added > 0) NewContentNotifier.addMovies(added)
            Outcome.Success(added)
        }

    /** Loads full VOD detail on demand, enriching with TMDB when a key is set. */
    suspend fun getVodDetail(config: SourceConfig, id: String): VodItem? = withContext(Dispatchers.IO) {
        val cached = vodDao.getById(id)?.toModel() ?: return@withContext null
        if (config.type != SourceType.XTREAM) return@withContext enrichWithTmdb(cached, isMovie = true)
        val merged = try {
            val api = buildXtreamApi(config.serverUrl)
            val info = api.getVodInfo(config.username, config.password, id).info
            cached.copy(
                plot = info?.plot ?: cached.plot,
                cast = info?.cast,
                director = info?.director,
                genre = info?.genre,
                releaseDate = info?.releaseDate,
                durationSecs = info?.durationSecs,
                rating = info?.rating?.toDoubleOrNull() ?: cached.rating,
                trailerUrl = youtube(info?.youtubeTrailer),
                posterUrl = info?.movieImage?.takeIf { it.isNotBlank() } ?: cached.posterUrl,
                tmdbId = info?.tmdbId
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            cached
        }
        enrichWithTmdb(merged, isMovie = true)
    }

    /**
     * Instant, network-free VOD record straight from the local cache (populated
     * by [refreshVodCategory]). Lets the detail screen render the poster, title and a
     * working Play button immediately while [getVodDetail] enriches in the bg.
     */
    suspend fun getVodCached(id: String): VodItem? = withContext(Dispatchers.IO) {
        vodDao.getById(id)?.toModel()
    }

    // ---- Series ---------------------------------------------------------

    fun observeSeries(): Flow<List<Series>> = seriesDao.observeAll().map { it.map { e -> e.toModel() } }

    /** Latest [limit] series by added date, for the "Recently added" rail. */
    fun observeRecentSeries(limit: Int): Flow<List<Series>> =
        seriesDao.observeRecent(limit).map { it.map { e -> e.toModel() } }

    fun observeSeriesByCategory(categoryId: String): Flow<List<Series>> =
        seriesDao.observeByCategory(categoryId).map { it.map { e -> e.toModel() } }

    /**
     * Series categories, read from the dedicated [series_categories] table so the
     * rail is available immediately after login — before any category's series
     * have been lazily downloaded. Mirrors [observeVodCategories].
     */
    fun observeSeriesCategories(): Flow<List<Category>> =
        seriesCategoryDao.observeAll().map { rows ->
            rows.map { Category(it.id, it.name, ContentType.SERIES) }
        }

    /** Series count per category id, used for the category row badges. */
    fun observeSeriesCategoryCounts(): Flow<Map<String, Int>> =
        seriesDao.observeCategoryCounts().map { rows -> rows.associate { it.categoryId to it.count } }

    fun searchSeries(query: String): Flow<List<Series>> =
        seriesDao.search(query).map { it.map { e -> e.toModel() } }

    // ---- Paging 3 (bounded series lists) --------------------------------

    /** Whole series cache, newest first — the "Recently added" default view. */
    fun pagingRecentSeries(): Flow<PagingData<Series>> =
        Pager(pagingConfig) { seriesDao.pagingRecent() }
            .flow.map { data -> data.map { it.toModel() } }

    fun pagingSeriesByCategory(categoryId: String): Flow<PagingData<Series>> =
        Pager(pagingConfig) { seriesDao.pagingByCategory(categoryId) }
            .flow.map { data -> data.map { it.toModel() } }

    /** Whole series cache in the requested [sort] order. */
    fun pagingSeriesAll(sort: ContentSort): Flow<PagingData<Series>> =
        Pager(pagingConfig) {
            when (sort) {
                ContentSort.RECENT -> seriesDao.pagingRecent()
                ContentSort.NAME -> seriesDao.pagingAllByName()
                ContentSort.RATING -> seriesDao.pagingAllByRating()
                ContentSort.YEAR -> seriesDao.pagingAllByYear()
            }
        }.flow.map { data -> data.map { it.toModel() } }

    /** One category's series in the requested [sort] order. */
    fun pagingSeriesByCategory(categoryId: String, sort: ContentSort): Flow<PagingData<Series>> =
        Pager(pagingConfig) {
            when (sort) {
                ContentSort.RECENT -> seriesDao.pagingByCategory(categoryId)
                ContentSort.NAME -> seriesDao.pagingCategoryByName(categoryId)
                ContentSort.RATING -> seriesDao.pagingCategoryByRating(categoryId)
                ContentSort.YEAR -> seriesDao.pagingCategoryByYear(categoryId)
            }
        }.flow.map { data -> data.map { it.toModel() } }

    /** Instant FTS search, paged. Empty input yields an empty page (no MATCH). */
    fun pagingSeriesSearch(query: String): Flow<PagingData<Series>> {
        val match = toFtsQuery(query)
        if (match.isBlank()) return flowOf(PagingData.empty())
        return Pager(pagingConfig) { seriesDao.pagingSearch(match) }
            .flow.map { data -> data.map { it.toModel() } }
    }

    /**
     * Fetches and caches only the series *categories* (not the series themselves),
     * mirroring [refreshVodCategories]. Cheap, so the Series rail renders right
     * away; the per-category [loaded] flag is preserved across refreshes so a
     * re-sync never forces a re-download of an already-cached category.
     */
    suspend fun refreshSeriesCategories(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        try {
            val api = buildXtreamApi(config.serverUrl)
            val catList = api.getSeriesCategories(config.username, config.password)
            val alreadyLoaded = seriesCategoryDao.loadedIds().toSet()
            val categories = catList.mapIndexedNotNull { index, c ->
                val id = c.categoryId ?: return@mapIndexedNotNull null
                SeriesCategoryEntity(
                    id = id,
                    name = c.categoryName ?: "Uncategorized",
                    position = index,
                    loaded = id in alreadyLoaded
                )
            }
            seriesCategoryDao.upsertAll(categories)
            Outcome.Success(categories.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            Outcome.Failure(e.toAppError())
        }
    }

    /** True if this category's series have already been downloaded into the cache. */
    suspend fun isSeriesCategoryLoaded(categoryId: String): Boolean = withContext(Dispatchers.IO) {
        seriesCategoryDao.getById(categoryId)?.loaded == true
    }

    /**
     * Lazily downloads a single series category's series and *merges* them into
     * the cache (no full-table wipe), so other already-downloaded categories stay
     * intact. Skips the network when the category is already loaded unless [force]
     * is set. Keeps the FTS index in lockstep and marks the category loaded.
     */
    suspend fun refreshSeriesCategory(
        config: SourceConfig,
        categoryId: String,
        force: Boolean = false
    ): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        val category = seriesCategoryDao.getById(categoryId)
        if (!force && category?.loaded == true) return@withContext Outcome.Success(0)
        try {
            val api = buildXtreamApi(config.serverUrl)
            val catName = category?.name ?: "Uncategorized"
            val catPosition = category?.position ?: Int.MAX_VALUE
            val items = api.getSeries(config.username, config.password, categoryId).mapNotNull { s ->
                val id = s.seriesId ?: return@mapNotNull null
                SeriesEntity(
                    id = id,
                    name = s.name ?: "Unknown",
                    posterUrl = s.cover?.takeIf { it.isNotBlank() },
                    categoryId = s.categoryId ?: categoryId,
                    categoryName = catName,
                    rating = s.rating?.toDoubleOrNull(),
                    plot = s.plot, cast = s.cast, director = s.director, genre = s.genre,
                    releaseDate = s.releaseDate, trailerUrl = youtube(s.youtubeTrailer), tmdbId = null,
                    addedAt = s.lastModified?.trim()?.toLongOrNull() ?: 0L,
                    categoryPosition = catPosition
                )
            }
            // How many of these are genuinely new to the cache. Only meaningful on a
            // forced re-check of an already-loaded category (the launch sweep). Guard on
            // categoryId so a backend that ignores category filtering can't inflate it.
            val existingIds = seriesDao.idsForCategory(categoryId).toSet()
            val newCount = items.count { it.categoryId == categoryId && it.id !in existingIds }
            // Merge (REPLACE on id), keeping the FTS index in lockstep, atomically.
            db.withTransaction {
                seriesDao.upsertSeries(items)
                seriesFtsDao.deleteByIds(items.map { it.id })
                seriesFtsDao.insertAll(items.map { SeriesFtsEntity(it.id, it.name) })
            }
            seriesCategoryDao.markLoaded(categoryId)
            Outcome.Success(if (force) newCount else items.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            Outcome.Failure(e.toAppError())
        }
    }

    /**
     * Best-effort prefetch of the first series category so the "Recently added"
     * view has content right after the splash, without pulling the whole catalog.
     */
    suspend fun prefetchFirstSeriesCategory(config: SourceConfig): Outcome<Int> =
        withContext(Dispatchers.IO) {
            val first = seriesCategoryDao.getAll().firstOrNull()
                ?: return@withContext Outcome.Success(0)
            refreshSeriesCategory(config, first.id)
        }

    /**
     * Re-syncs every series category the user has already opened (loaded == true)
     * so newly added series surface on each app launch. Force-refreshes via upsert,
     * so existing items stay put (no flicker) and only new ones appear. Best-effort:
     * a single category's failure never aborts the rest. Xtream only.
     */
    suspend fun refreshLoadedSeriesCategories(config: SourceConfig): Outcome<Int> =
        withContext(Dispatchers.IO) {
            if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
            var added = 0
            for (id in seriesCategoryDao.loadedIds()) {
                when (val r = refreshSeriesCategory(config, id, force = true)) {
                    is Outcome.Success -> added += r.data
                    is Outcome.Failure -> Unit // keep going; best-effort per category
                }
            }
            // Notify the Series screen once with the launch's total new count.
            if (added > 0) NewContentNotifier.addSeries(added)
            Outcome.Success(added)
        }

    /** Loads seasons + episodes for a series and caches the episodes. */
    suspend fun getSeasons(config: SourceConfig, seriesId: String): List<Season> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext emptyList()
        try {
            val api = buildXtreamApi(config.serverUrl)
            val info = api.getSeriesInfo(config.username, config.password, seriesId)
            val episodeEntities = mutableListOf<EpisodeEntity>()
            val seasons = (info.episodes ?: emptyMap()).map { (seasonKey, eps) ->
                val seasonNum = seasonKey.toIntOrNull() ?: 0
                val episodes = eps.mapNotNull { e ->
                    val eid = e.id ?: return@mapNotNull null
                    val entity = EpisodeEntity(
                        id = eid,
                        seriesId = seriesId,
                        seasonNumber = e.season ?: seasonNum,
                        episodeNumber = e.episodeNum ?: 0,
                        title = e.title ?: "Episode ${e.episodeNum ?: 0}",
                        streamUrl = XtreamUrlBuilder.seriesEpisodeUrl(
                            config.serverUrl, config.username, config.password, eid,
                            e.containerExtension ?: "mp4"
                        ),
                        plot = e.info?.plot,
                        durationSecs = e.info?.durationSecs,
                        posterUrl = e.info?.movieImage?.takeIf { it.isNotBlank() }
                    )
                    episodeEntities += entity
                    entity.toModel()
                }.sortedBy { it.episodeNumber }
                Season(seriesId, seasonNum, episodes)
            }.sortedBy { it.seasonNumber }
            if (episodeEntities.isNotEmpty()) seriesDao.upsertEpisodes(episodeEntities)
            seasons
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Fall back to cached episodes if the network call fails.
            cachedSeasons(seriesId)
        }
    }

    /**
     * Instant, network-free seasons/episodes from the local cache (populated by a
     * previous [getSeasons] call). Lets the series screen show episodes right away
     * on a repeat visit while [getSeasons] refreshes the cache in the background.
     */
    suspend fun getCachedSeasons(seriesId: String): List<Season> = withContext(Dispatchers.IO) {
        cachedSeasons(seriesId)
    }

    private suspend fun cachedSeasons(seriesId: String): List<Season> =
        seriesDao.episodesFor(seriesId)
            .groupBy { it.seasonNumber }
            .map { (num, eps) ->
                Season(seriesId, num, eps.map { e -> e.toModel() }.sortedBy { it.episodeNumber })
            }
            .sortedBy { it.seasonNumber }

    // ---- EPG ------------------------------------------------------------

    /**
     * Display-name → normalized epg id map, built from the XMLTV <channel>
     * entries on each [refreshEpg]. Used as a fallback when a channel's tvg-id
     * is missing or doesn't match any program id. In-memory only (rebuilt on
     * every guide refresh) to avoid a destructive Room schema migration.
     */
    @Volatile
    private var epgNameIndex: Map<String, String> = emptyMap()

    /** Lower-cased, trimmed epg id for case/space-insensitive matching. */
    private fun normalizeEpgId(raw: String?): String? =
        raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }

    /** Normalized channel display name (trim + lowercase + collapse whitespace). */
    private fun normalizeName(raw: String?): String =
        raw?.trim()?.lowercase(Locale.US)?.replace(Regex("\\s+"), " ").orEmpty()

    /** Downloads and caches the full XMLTV guide (Xtream xmltv.php). */
    suspend fun refreshEpg(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        try {
            val url = XtreamUrlBuilder.xmltvUrl(config.serverUrl, config.username, config.password)
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Outcome.Failure(AppError.CANNOT_CONNECT)
                val body = resp.body ?: return@withContext Outcome.Failure(AppError.CANNOT_CONNECT)
                val batch = ArrayList<ProgramEntity>(500)
                var total = 0
                val nameIndex = HashMap<String, String>()
                epgDao.clearAll()
                body.byteStream().use { stream ->
                    XmltvParser.parse(
                        stream,
                        onChannel = { id, displayName ->
                            val nId = normalizeEpgId(id)
                            val nName = normalizeName(displayName)
                            // First display-name wins; don't let aliases overwrite it.
                            if (nId != null && nName.isNotEmpty() && !nameIndex.containsKey(nName)) {
                                nameIndex[nName] = nId
                            }
                        }
                    ) { p ->
                        batch += ProgramEntity(
                            // Normalize on store so lookups match case/space-insensitively.
                            epgChannelId = normalizeEpgId(p.epgChannelId) ?: p.epgChannelId.trim(),
                            title = p.title,
                            description = p.description,
                            startMs = p.startMs,
                            stopMs = p.stopMs
                        )
                        if (batch.size >= 500) {
                            // Blocking insert inside IO context; chunked to bound memory.
                            kotlinx.coroutines.runBlocking { epgDao.insertAll(batch.toList()) }
                            total += batch.size
                            batch.clear()
                        }
                    }
                }
                if (batch.isNotEmpty()) {
                    epgDao.insertAll(batch.toList()); total += batch.size
                }
                epgNameIndex = nameIndex
                settings.setEpgUpdatedAt(System.currentTimeMillis())
                Outcome.Success(total)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            Outcome.Failure(e.toAppError())
        }
    }

    suspend fun getNowNext(channel: Channel): NowNext = withContext(Dispatchers.IO) {
        val epgId = resolveEpgId(channel) ?: return@withContext NowNext(null, null)
        val now = System.currentTimeMillis()
        val upcoming = epgDao.upcoming(epgId, now, limit = 2).map { it.toModel() }
        val current = upcoming.firstOrNull { it.isLiveAt(now) }
        val next = upcoming.firstOrNull { it.startMs > now }
        NowNext(current, next)
    }

    suspend fun getProgramsWindow(channel: Channel, fromMs: Long, toMs: Long): List<Program> =
        withContext(Dispatchers.IO) {
            val epgId = resolveEpgId(channel) ?: return@withContext emptyList()
            epgDao.inWindow(epgId, fromMs, toMs).map { it.toModel() }
        }

    suspend fun setEpgMapping(channelId: String, epgChannelId: String) =
        withContext(Dispatchers.IO) {
            epgMappingDao.set(EpgMappingEntity(channelId, epgChannelId))
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
            getProgramsWindow(channel, from, now)
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
            val durationMin = ((program.stopMs - program.startMs) / 60_000L)
                .toInt().coerceAtLeast(1)
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            val start = formatter.format(java.util.Date(program.startMs))
            XtreamUrlBuilder.catchupUrl(
                config.serverUrl, config.username, config.password,
                streamId, start, durationMin
            )
        }

    // ---- Background sync ------------------------------------------------

    /**
     * Refreshes live channels + EPG (and VOD/series when present) for background
     * auto-sync. Returns true if at least the live refresh succeeded.
     */
    suspend fun syncAll(config: SourceConfig): Boolean = withContext(Dispatchers.IO) {
        val liveOk = refreshLive(config) is Outcome.Success
        refreshEpg(config)
        if (config.type == SourceType.XTREAM) {
            // Movies and series are both lazy per-category, so background sync only
            // refreshes the category lists (cheap) — never the whole catalog.
            // Best-effort: a category-list failure must not fail the whole sync,
            // but coroutine cancellation must always propagate.
            try {
                refreshVodCategories(config)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
            try {
                refreshSeriesCategories(config)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        liveOk
    }

    private suspend fun resolveEpgId(channel: Channel): String? {
        // 1) Manual user override (EPG correction screen) always wins.
        val override = normalizeEpgId(epgMappingDao.get(channel.id))
        if (override != null) return override
        // 2) The channel's own tvg-id / epg_channel_id, matched case/space-insensitively.
        val direct = normalizeEpgId(channel.epgChannelId)
        if (direct != null && epgDao.countFor(direct) > 0) return direct
        // 3) Fallback: match the channel's display name against the XMLTV <channel>
        //    display-names — covers a missing or mismatched tvg-id.
        val byName = epgNameIndex[normalizeName(channel.name)]
        if (byName != null && epgDao.countFor(byName) > 0) return byName
        // 4) Last resort: the normalized direct id even if no programs are present
        //    yet (guide may still be downloading); null when nothing to match on.
        return direct
    }

    // ---- Account info ---------------------------------------------------

    suspend fun getAccountInfo(config: SourceConfig): AccountInfo? = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext null
        runCatching {
            val info = buildXtreamApi(config.serverUrl)
                .authenticate(config.username, config.password).userInfo ?: return@runCatching null
            val expMs = info.expDate?.toLongOrNull()?.times(1000)
            val daysLeft = expMs?.let { (it - System.currentTimeMillis()) / 86_400_000L }
            AccountInfo(
                status = info.status,
                isActive = info.auth == 1 && !info.status.equals("Expired", true),
                expiryDateMs = expMs,
                daysRemaining = daysLeft,
                activeConnections = info.activeConnections?.toIntOrNull(),
                maxConnections = info.maxConnections?.toIntOrNull()
            )
        }.getOrNull()
    }

    // ---- Diagnostics ----------------------------------------------------

    suspend fun pingServer(config: SourceConfig): DiagnosticResult = withContext(Dispatchers.IO) {
        val target = config.serverUrl.ifBlank { config.m3uUrl }
        runCatching {
            val start = System.currentTimeMillis()
            val request = Request.Builder().url(target).header("Range", "bytes=0-0").build()
            httpClient.newCall(request).execute().use { resp ->
                val ms = System.currentTimeMillis() - start
                DiagnosticResult("ping", resp.isSuccessful || resp.code in 200..416, "${ms}ms")
            }
        }.getOrElse { DiagnosticResult("ping", false, it.message ?: "error") }
    }

    /** Rough download speed in Mbps by pulling up to ~2MB from the server. */
    suspend fun speedTestMbps(config: SourceConfig): DiagnosticResult = withContext(Dispatchers.IO) {
        val target = config.serverUrl.ifBlank { config.m3uUrl }
        runCatching {
            val request = Request.Builder().url(target).header("Range", "bytes=0-2097151").build()
            val start = System.currentTimeMillis()
            httpClient.newCall(request).execute().use { resp ->
                val bytes = resp.body?.bytes()?.size ?: 0
                val secs = (System.currentTimeMillis() - start) / 1000.0
                val mbps = if (secs > 0) (bytes * 8 / 1_000_000.0) / secs else 0.0
                DiagnosticResult("speed", bytes > 0, String.format("%.1f Mbps", mbps))
            }
        }.getOrElse { DiagnosticResult("speed", false, it.message ?: "error") }
    }

    suspend fun checkDns(config: SourceConfig): DiagnosticResult = withContext(Dispatchers.IO) {
        val host = runCatching {
            java.net.URI(config.serverUrl.ifBlank { config.m3uUrl }).host
        }.getOrNull() ?: return@withContext DiagnosticResult("dns", false, "no host")
        runCatching {
            val addr = InetAddress.getByName(host)
            DiagnosticResult("dns", true, addr.hostAddress ?: host)
        }.getOrElse { DiagnosticResult("dns", false, "cannot resolve") }
    }

    // ---- Profiles -------------------------------------------------------

    fun observeProfiles(): Flow<List<Profile>> =
        profileDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun addProfile(name: String, config: SourceConfig, lockAdult: Boolean): Long =
        withContext(Dispatchers.IO) {
            profileDao.add(
                ProfileEntity(
                    name = name,
                    sourceType = config.type.name,
                    serverUrl = config.serverUrl,
                    username = config.username,
                    password = config.password,
                    m3uUrl = config.m3uUrl,
                    lockAdult = lockAdult,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

    /**
     * Ensures the given source is also saved as a switchable profile and returns
     * its id. Reuses an existing profile that points at the same source so that
     * repeated logins don't create duplicates; otherwise inserts a new one. This
     * is what makes a freshly logged-in account show up on the Profiles screen.
     */
    suspend fun ensureProfile(config: SourceConfig, lockAdult: Boolean = false): Long =
        withContext(Dispatchers.IO) {
            profileDao.getAll().firstOrNull { it.matches(config) }?.id
                ?: addProfile(defaultProfileName(config), config, lockAdult)
        }

    /**
     * One-time backfill for accounts that connected before profiles were created
     * automatically: if a source is saved but no profile exists yet, mirror it
     * into a profile and make it active so the Profiles screen isn't empty.
     */
    suspend fun backfillProfileFromSource() = withContext(Dispatchers.IO) {
        if (profileDao.getAll().isNotEmpty()) return@withContext
        val config = settings.getSourceConfig() ?: return@withContext
        settings.setActiveProfileId(ensureProfile(config))
    }

    private fun ProfileEntity.matches(config: SourceConfig): Boolean =
        sourceType == config.type.name &&
            serverUrl == config.serverUrl &&
            username == config.username &&
            m3uUrl == config.m3uUrl

    /** A friendly default profile name: the Xtream username, else the source host. */
    private fun defaultProfileName(config: SourceConfig): String = when (config.type) {
        SourceType.XTREAM -> config.username.ifBlank { hostOf(config.serverUrl) }
        SourceType.M3U_URL -> hostOf(config.m3uUrl)
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Playlist"

    suspend fun removeProfile(id: Long) = withContext(Dispatchers.IO) { profileDao.remove(id) }

    suspend fun getProfile(id: Long): Profile? = withContext(Dispatchers.IO) {
        profileDao.getById(id)?.toModel()
    }

    // ---- Resume positions ----------------------------------------------

    suspend fun saveResume(meta: ResumeMeta, positionMs: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            // Don't persist trivial or near-complete positions: clearing them keeps
            // the Continue Watching rail to genuinely in-progress content.
            if (positionMs < 10_000 || (durationMs > 0 && positionMs > durationMs - 30_000)) {
                resumeDao.clear(meta.contentId)
                // A near-complete position means the title was finished: record it
                // as watched so the tick survives the resume row being cleared.
                if (durationMs > 0 && positionMs > durationMs - 30_000) {
                    markWatched(meta.contentId, meta.kind.raw, meta.seriesId)
                }
            } else {
                resumeDao.save(
                    ResumeEntity(
                        contentId = meta.contentId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = System.currentTimeMillis(),
                        type = meta.kind.raw,
                        title = meta.title,
                        posterUrl = meta.posterUrl,
                        streamUrl = meta.streamUrl,
                        vodId = meta.vodId,
                        seriesId = meta.seriesId,
                        seasonNumber = meta.seasonNumber,
                        episodeNumber = meta.episodeNumber
                    )
                )
            }
        }

    suspend fun getResume(contentId: String): Long = withContext(Dispatchers.IO) {
        resumeDao.get(contentId)?.positionMs ?: 0L
    }

    suspend fun clearResume(contentId: String) = withContext(Dispatchers.IO) {
        resumeDao.clear(contentId)
    }

    /** Reactive Continue Watching rail: most recent in-progress items first. */
    fun observeContinueWatching(limit: Int = 20): Flow<List<ContinueItem>> =
        resumeDao.observeRecent(limit).map { rows -> rows.map { it.toContinueItem() } }

    /** Saved positions (ms) keyed by raw episode id for one series. */
    suspend fun episodeProgress(seriesId: String): Map<String, Long> =
        withContext(Dispatchers.IO) {
            resumeDao.forSeries(seriesId).associate { it.contentId.removePrefix("ep_") to it.positionMs }
        }

    /** Last-watched episode of a series for a one-tap continue action. */
    suspend fun latestSeriesResume(seriesId: String): ContinueItem? =
        withContext(Dispatchers.IO) {
            resumeDao.latestForSeries(seriesId)?.toContinueItem()
        }

    private fun ResumeEntity.toContinueItem() = ContinueItem(
        contentId = contentId,
        kind = ResumeKind.fromRaw(type),
        title = title,
        posterUrl = posterUrl,
        streamUrl = streamUrl,
        positionMs = positionMs,
        durationMs = durationMs,
        vodId = vodId,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber
    )

    /** Watch-progress percent (0..100) keyed by resume contentId, for grid bars. */
    suspend fun allWatchProgress(): Map<String, Int> = withContext(Dispatchers.IO) {
        resumeDao.all().associate { row ->
            val pct = if (row.durationMs > 0) {
                ((row.positionMs * 100) / row.durationMs).toInt().coerceIn(0, 100)
            } else 0
            row.contentId to pct
        }
    }

    // ---- Watched (finished) state --------------------------------------

    suspend fun markWatched(contentId: String, type: String, seriesId: String? = null) =
        withContext(Dispatchers.IO) {
            watchedDao.mark(
                com.iptv.player.data.local.entity.WatchedEntity(
                    contentId = contentId,
                    type = type,
                    seriesId = seriesId,
                    watchedAt = System.currentTimeMillis()
                )
            )
        }

    suspend fun isWatched(contentId: String): Boolean =
        withContext(Dispatchers.IO) { watchedDao.isWatched(contentId) }

    /** All watched content ids, for badging the movie/series grids. */
    suspend fun watchedIds(): Set<String> =
        withContext(Dispatchers.IO) { watchedDao.allIds().toSet() }

    /** Watched episode ids for one series (raw episode ids, "ep_" stripped). */
    suspend fun watchedEpisodeIds(seriesId: String): Set<String> =
        withContext(Dispatchers.IO) {
            watchedDao.idsForSeries(seriesId).map { it.removePrefix("ep_") }.toSet()
        }

    // ---- Similar / recommended -----------------------------------------

    /** Other movies in the same category as [item], for the detail "Similar" rail. */
    suspend fun similarMovies(item: VodItem, limit: Int = 20): List<VodItem> =
        withContext(Dispatchers.IO) {
            val categoryId = item.categoryId ?: return@withContext emptyList()
            vodDao.sampleByCategory(categoryId, item.id, limit).map { it.toModel() }
        }

    /** Other series in the same category as [item], for the detail "Similar" rail. */
    suspend fun similarSeries(item: Series, limit: Int = 20): List<Series> =
        withContext(Dispatchers.IO) {
            val categoryId = item.categoryId ?: return@withContext emptyList()
            seriesDao.sampleByCategory(categoryId, item.id, limit).map { it.toModel() }
        }

    // ---- TMDB enrichment ------------------------------------------------

    private suspend fun enrichWithTmdb(item: VodItem, isMovie: Boolean): VodItem {
        val key = settings.getTmdbKey()
        if (key.isBlank() || !item.posterUrl.isNullOrBlank()) return item
        return runCatching {
            val api = retrofitBuilder.baseUrl(TmdbApi.BASE_URL).build().create(TmdbApi::class.java)
            val result = (if (isMovie) api.searchMovie(key, item.name)
            else api.searchTv(key, item.name)).results?.firstOrNull() ?: return item
            item.copy(
                posterUrl = TmdbApi.posterUrl(result.posterPath) ?: item.posterUrl,
                plot = item.plot ?: result.overview,
                rating = item.rating ?: result.voteAverage
            )
        }.getOrDefault(item)
    }

    // ---- Mapping helpers ------------------------------------------------

    private fun ChannelEntity.toModel(isFav: Boolean = false) = Channel(
        id = id, name = name, streamUrl = streamUrl, logoUrl = logoUrl,
        categoryId = categoryId, categoryName = categoryName,
        epgChannelId = epgChannelId, number = number,
        type = ContentType.valueOf(type), isFavorite = isFav, catchupDays = catchupDays,
        position = position, categoryPosition = categoryPosition
    )

    private fun Channel.toEntity() = ChannelEntity(
        id = id, name = name, streamUrl = streamUrl, logoUrl = logoUrl,
        categoryId = categoryId, categoryName = categoryName,
        epgChannelId = epgChannelId, number = number, type = type.name,
        catchupDays = catchupDays, position = position, categoryPosition = categoryPosition
    )

    private fun VodEntity.toModel() = VodItem(
        id = id, name = name, streamUrl = streamUrl, posterUrl = posterUrl,
        categoryId = categoryId, categoryName = categoryName, rating = rating,
        plot = plot, cast = cast, director = director, genre = genre,
        releaseDate = releaseDate, durationSecs = durationSecs,
        trailerUrl = trailerUrl, tmdbId = tmdbId
    )

    private fun SeriesEntity.toModel() = Series(
        id = id, name = name, posterUrl = posterUrl, categoryId = categoryId,
        categoryName = categoryName, rating = rating, plot = plot, cast = cast,
        director = director, genre = genre, releaseDate = releaseDate,
        trailerUrl = trailerUrl, tmdbId = tmdbId
    )

    private fun EpisodeEntity.toModel() = Episode(
        id = id, seriesId = seriesId, seasonNumber = seasonNumber,
        episodeNumber = episodeNumber, title = title, streamUrl = streamUrl,
        plot = plot, durationSecs = durationSecs, posterUrl = posterUrl
    )

    private fun ProgramEntity.toModel() = Program(
        epgChannelId = epgChannelId, title = title, description = description,
        startMs = startMs, stopMs = stopMs
    )

    private fun ProfileEntity.toModel() = Profile(
        id = id,
        name = name,
        config = SourceConfig(
            type = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.XTREAM),
            serverUrl = serverUrl, username = username, password = password, m3uUrl = m3uUrl
        ),
        lockAdult = lockAdult
    )

    private fun youtube(idOrUrl: String?): String? {
        val v = idOrUrl?.trim().orEmpty()
        if (v.isBlank()) return null
        return if (v.startsWith("http")) v else "https://www.youtube.com/watch?v=$v"
    }

    /** Decodes Xtream's base64 EPG fields (used by short-EPG callers). */
    fun decodeEpgText(b64: String?): String =
        runCatching { String(Base64.decode(b64 ?: "", Base64.DEFAULT)) }.getOrDefault("")

    private fun buildXtreamApi(serverUrl: String): XtreamApi {
        val base = serverUrl.trimEnd('/') + "/"
        return retrofitBuilder.baseUrl(base).build().create(XtreamApi::class.java)
    }

    private fun Throwable.toAppError(): AppError {
        val mapped = when (this) {
            is HttpException -> when (code()) {
                401, 403 -> AppError.BAD_CREDENTIALS
                else -> AppError.CANNOT_CONNECT
            }
            is IOException -> AppError.CANNOT_CONNECT
            // OutOfMemoryError (huge playlists/accounts on low-RAM TV boxes) and other
            // non-Exception Throwables must surface as a friendly error, not crash the app.
            is OutOfMemoryError -> AppError.EMPTY_PLAYLIST
            else -> AppError.UNKNOWN
        }
        // Record the underlying cause so field logs explain provider failures that
        // the user only sees as a friendly message.
        Logger.w("IptvRepository", "Operation failed -> $mapped", this)
        return mapped
    }
}
