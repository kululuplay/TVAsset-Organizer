/*
 * IptvRepository.kt
 * Single source of truth for all content: live channels, EPG, VOD, series,
 * favorites, recents, profiles and resume positions. Fetches from Xtream or
 * M3U (+ optional TMDB enrichment), caches into Room, and exposes reactive
 * Flows. Network/parse work runs on Dispatchers.IO; errors map to AppError.
 */
package com.iptv.player.data.repository

import android.util.Base64
import androidx.room.withTransaction
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.local.entity.ChannelEntity
import com.iptv.player.data.local.entity.ChannelOverrideEntity
import com.iptv.player.data.local.entity.EpgMappingEntity
import com.iptv.player.data.local.entity.EpisodeEntity
import com.iptv.player.data.local.entity.FavoriteEntity
import com.iptv.player.data.local.entity.ProfileEntity
import com.iptv.player.data.local.entity.ProgramEntity
import com.iptv.player.data.local.entity.RecentEntity
import com.iptv.player.data.local.entity.ResumeEntity
import com.iptv.player.data.local.entity.SeriesEntity
import com.iptv.player.data.local.entity.VodEntity
import com.iptv.player.data.model.AccountInfo
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.DiagnosticResult
import com.iptv.player.data.model.Episode
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.ResumeMeta
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
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException
import java.net.InetAddress

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
    private val seriesDao = db.seriesDao()
    private val profileDao = db.profileDao()
    private val resumeDao = db.resumeDao()
    private val epgMappingDao = db.epgMappingDao()

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

    suspend fun getChannel(id: String): Channel? = channelDao.getById(id)?.toModel()

    /** Live channels that advertise a catch-up / timeshift archive. */
    fun observeCatchupChannels(): Flow<List<Channel>> =
        channelDao.observeByType(ContentType.LIVE.name)
            .map { list -> list.map { it.toModel() }.filter { it.catchupDays > 0 } }

    /** All channels of a type (including hidden) plus their manager state. */
    fun observeManagedChannels(type: ContentType): Flow<List<ManagedChannel>> =
        combine(
            channelDao.observeForManagement(type.name),
            favoriteDao.observeFavoriteChannels()
        ) { rows, favorites ->
            val favoriteIds = favorites.map { it.id }.toHashSet()
            rows.map {
                ManagedChannel(
                    channel = it.channel.toModel(),
                    hidden = it.hidden,
                    isFavorite = it.channel.id in favoriteIds
                )
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
            channelDao.clearType(ContentType.LIVE.name)
            // Stamp each channel with its index in the source list so the visible
            // order matches what the server delivered.
            val ordered = channels.mapIndexed { index, ch -> ch.copy(position = index) }
            // Insert in chunks: large Xtream accounts / M3U lists can hold tens of
            // thousands of channels. Mapping + inserting all at once spikes memory and
            // can OOM on low-RAM TV boxes, so bound the peak by batching.
            ordered.chunked(1000).forEach { batch ->
                channelDao.upsertAll(batch.map { it.toEntity() })
            }
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

    fun observeVodByCategory(categoryId: String): Flow<List<VodItem>> =
        vodDao.observeByCategory(categoryId).map { it.map { e -> e.toModel() } }

    fun observeVodCategories(): Flow<List<Category>> =
        vodDao.observeCategories().map { rows ->
            rows.map { Category(it.categoryId, it.categoryName ?: "Uncategorized", ContentType.VOD) }
        }

    fun searchVod(query: String): Flow<List<VodItem>> =
        vodDao.search(query).map { it.map { e -> e.toModel() } }

    suspend fun refreshVod(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        try {
            val api = buildXtreamApi(config.serverUrl)
            val catList = api.getVodCategories(config.username, config.password)
            val cats = catList
                .associate { (it.categoryId ?: "") to (it.categoryName ?: "Uncategorized") }
            val catOrder = catList
                .mapIndexedNotNull { index, c -> c.categoryId?.let { it to index } }
                .toMap()
            val items = api.getVodStreams(config.username, config.password).mapNotNull { s ->
                val id = s.streamId ?: return@mapNotNull null
                VodEntity(
                    id = id,
                    name = s.name ?: "Unknown",
                    streamUrl = XtreamUrlBuilder.movieUrl(
                        config.serverUrl, config.username, config.password, id,
                        s.containerExtension ?: "mp4"
                    ),
                    posterUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                    categoryId = s.categoryId,
                    categoryName = cats[s.categoryId] ?: "Uncategorized",
                    rating = s.rating?.toDoubleOrNull(),
                    plot = null, cast = null, director = null, genre = null,
                    releaseDate = null, durationSecs = null, trailerUrl = null, tmdbId = null,
                    addedAt = s.added?.trim()?.toLongOrNull() ?: 0L,
                    categoryPosition = catOrder[s.categoryId] ?: Int.MAX_VALUE
                )
            }.mapIndexed { index, e -> e.copy(position = index) }
            // Atomic replace so a cancellation between clear and insert can never
            // leave the table empty for other readers (e.g. global search).
            db.withTransaction {
                vodDao.clearAll()
                vodDao.upsertAll(items)
            }
            Outcome.Success(items.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            Outcome.Failure(e.toAppError())
        }
    }

    /** Loads full VOD detail on demand, enriching with TMDB when a key is set. */
    suspend fun getVodDetail(config: SourceConfig, id: String): VodItem? = withContext(Dispatchers.IO) {
        val cached = vodDao.getById(id)?.toModel() ?: return@withContext null
        if (config.type != SourceType.XTREAM) return@withContext enrichWithTmdb(cached, isMovie = true)
        runCatching {
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
        }.getOrDefault(cached).let { enrichWithTmdb(it, isMovie = true) }
    }

    // ---- Series ---------------------------------------------------------

    fun observeSeries(): Flow<List<Series>> = seriesDao.observeAll().map { it.map { e -> e.toModel() } }

    fun observeSeriesByCategory(categoryId: String): Flow<List<Series>> =
        seriesDao.observeByCategory(categoryId).map { it.map { e -> e.toModel() } }

    fun observeSeriesCategories(): Flow<List<Category>> =
        seriesDao.observeCategories().map { rows ->
            rows.map { Category(it.categoryId, it.categoryName ?: "Uncategorized", ContentType.SERIES) }
        }

    fun searchSeries(query: String): Flow<List<Series>> =
        seriesDao.search(query).map { it.map { e -> e.toModel() } }

    suspend fun refreshSeries(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        try {
            val api = buildXtreamApi(config.serverUrl)
            val catList = api.getSeriesCategories(config.username, config.password)
            val cats = catList
                .associate { (it.categoryId ?: "") to (it.categoryName ?: "Uncategorized") }
            val catOrder = catList
                .mapIndexedNotNull { index, c -> c.categoryId?.let { it to index } }
                .toMap()
            val items = api.getSeries(config.username, config.password).mapNotNull { s ->
                val id = s.seriesId ?: return@mapNotNull null
                SeriesEntity(
                    id = id,
                    name = s.name ?: "Unknown",
                    posterUrl = s.cover?.takeIf { it.isNotBlank() },
                    categoryId = s.categoryId,
                    categoryName = cats[s.categoryId] ?: "Uncategorized",
                    rating = s.rating?.toDoubleOrNull(),
                    plot = s.plot, cast = s.cast, director = s.director, genre = s.genre,
                    releaseDate = s.releaseDate, trailerUrl = youtube(s.youtubeTrailer), tmdbId = null,
                    addedAt = s.lastModified?.trim()?.toLongOrNull() ?: 0L,
                    categoryPosition = catOrder[s.categoryId] ?: Int.MAX_VALUE
                )
            }.mapIndexed { index, e -> e.copy(position = index) }
            // Atomic replace so a cancellation between clear and insert can never
            // leave the table empty for other readers (e.g. global search).
            db.withTransaction {
                seriesDao.clearSeries()
                seriesDao.upsertSeries(items)
            }
            Outcome.Success(items.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            Outcome.Failure(e.toAppError())
        }
    }

    /** Loads seasons + episodes for a series and caches the episodes. */
    suspend fun getSeasons(config: SourceConfig, seriesId: String): List<Season> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext emptyList()
        runCatching {
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
        }.getOrElse {
            // Fall back to cached episodes if the network call fails.
            seriesDao.episodesFor(seriesId)
                .groupBy { it.seasonNumber }
                .map { (num, eps) -> Season(seriesId, num, eps.map { e -> e.toModel() }) }
                .sortedBy { it.seasonNumber }
        }
    }

    // ---- EPG ------------------------------------------------------------

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
                epgDao.clearAll()
                body.byteStream().use { stream ->
                    XmltvParser.parse(stream) { p ->
                        batch += ProgramEntity(
                            epgChannelId = p.epgChannelId,
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
            runCatching { refreshVod(config) }
            runCatching { refreshSeries(config) }
        }
        liveOk
    }

    private suspend fun resolveEpgId(channel: Channel): String? =
        epgMappingDao.get(channel.id) ?: channel.epgChannelId

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

    private fun Throwable.toAppError(): AppError = when (this) {
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
}
