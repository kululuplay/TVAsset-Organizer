/*
 * SeriesCatalogRepository.kt
 * Series catalog: category snapshot + lazy per-category downloads, Paging 3
 * views, seasons/episodes with cache fallback, and the throttled episode-date
 * sweep for panels that never bump get_series.last_modified.
 */
package com.iptv.player.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.local.LikeEscape
import com.iptv.player.data.local.entity.EpisodeEntity
import com.iptv.player.data.local.entity.SeriesCategoryEntity
import com.iptv.player.data.local.entity.SeriesEntity
import com.iptv.player.data.local.entity.SeriesFtsEntity
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.ContentSort
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.Season
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.remote.MetadataPolicy
import com.iptv.player.data.remote.XtreamUrlBuilder
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.Logger
import com.iptv.player.util.NewContentNotifier
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Pure scheduling rules of the episode-date sweep, separated from the network
 * loop so the due/backoff decisions can be unit-tested without Room.
 */
internal object SeriesDateSweepPolicy {
    const val SERIES_DATES_PER_CALL = 40
    const val SERIES_DATES_CATEGORY_INTERVAL_MS = 15L * 60_000L
    const val SERIES_DATES_CATALOG_INTERVAL_MS = 6L * 60L * 60_000L
    const val SERIES_DATES_BACKOFF_429_MS = 15L * 60_000L
    const val SERIES_DATES_BACKOFF_5XX_MS = 5L * 60_000L

    /** True while a previous 429/5xx still pauses every sweep. */
    fun isPaused(now: Long, backoffUntil: Long): Boolean = now < backoffUntil

    /** Series checked at or before this instant are due; [force] makes everything due. */
    fun dueBefore(now: Long, categoryId: String?, force: Boolean): Long {
        val minInterval = if (categoryId == null) SERIES_DATES_CATALOG_INTERVAL_MS else SERIES_DATES_CATEGORY_INTERVAL_MS
        return if (force) now + 1 else now - minInterval
    }

    /** How long a sweep pauses after the panel answered with [status]. */
    fun backoffMs(status: Int): Long =
        if (status == 429) SERIES_DATES_BACKOFF_429_MS else SERIES_DATES_BACKOFF_5XX_MS

    /** Only the first 429/5xx counts; any other status is retried normally. */
    fun isThrottleStatus(code: Int): Boolean = code == 429 || code in 500..599
}

internal class SeriesCatalogRepository(
    db: AppDatabase,
    private val settings: SettingsStore,
    override val secureValues: SecureValueCodec,
    private val support: CatalogSyncSupport,
    private val tmdb: TmdbMetadataRepository,
    private val profiles: ProfileRepository,
    private val pagingConfig: PagingConfig,
) : EntityMappers {

    private val seriesDao = db.seriesDao()
    private val seriesCategoryDao = db.seriesCategoryDao()
    private val recommendationDao = db.recommendationDao()
    private val seriesFtsDao = db.seriesFtsDao()

    // ---- Series ---------------------------------------------------------

    fun observeSeries(): Flow<List<Series>> = seriesDao.observeAll().map { it.map { e -> e.toModel() } }

    /**
     * Instant, network-free series record straight from the local cache (populated
     * by [refreshSeriesCategory]). Lets the detail header render immediately instead
     * of scanning the entire series list in memory.
     */
    suspend fun getSeriesCached(id: String): Series? = withContext(Dispatchers.IO) {
        seriesDao.getById(id)?.toModel()
    }

    /** Latest [limit] series by added date, for the "Recently added" rail. */
    fun observeRecentSeries(limit: Int): Flow<List<Series>> =
        seriesDao.observeRecent(limit).map { it.map { e -> e.toModel() } }

    fun observeSeriesByCategory(categoryId: String): Flow<List<Series>> =
        seriesDao.observeByCategory(categoryId).map { it.map { e -> e.toModel() } }

    /**
     * Series categories, read from the dedicated [series_categories] table so the
     * rail is available immediately after login — before any category's series
     * have been lazily downloaded. Mirrors [VodCatalogRepository.observeVodCategories].
     */
    fun observeSeriesCategories(): Flow<List<Category>> =
        seriesCategoryDao.observeAll().map { rows ->
            rows.map { Category(it.id, it.name, ContentType.SERIES) }
        }

    /** Series count per category id, used for the category row badges. */
    fun observeSeriesCategoryCounts(): Flow<Map<String, Int>> =
        seriesDao.observeCategoryCounts().map { rows -> rows.associate { it.categoryId to it.count } }

    fun searchSeries(query: String): Flow<List<Series>> =
        seriesDao.search(LikeEscape.escape(query)).map { it.map { e -> e.toModel() } }

    // ---- Paging 3 (bounded series lists) --------------------------------

    /** Whole series cache, newest first — the "Recently added" default view. */
    fun pagingRecentSeries(hidden: List<String> = emptyList()): Flow<PagingData<Series>> =
        Pager(pagingConfig) { seriesDao.pagingRecent(hidden) }
            .flow.map { data -> data.map { it.toModel() } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    fun pagingRecommendedSeries(hidden: List<String>): Flow<PagingData<Series>> =
        settings.activeProfileId.distinctUntilChanged().flatMapLatest { profileId ->
            combine(recommendationDao.observePreferences(profileId), seriesDao.observeRecommendationCatalog()) { history, _ -> history }
                .debounce(600).mapLatest { history ->
                    val ids = profiles.recommendationIds(profileId, "series", history, hidden)
                    val items = seriesDao.recommendedItems(ids, hidden).associateBy { it.id }
                    PagingData.from(ids.mapNotNull { items[it]?.toModel() }, sourceLoadStates = androidx.paging.LoadStates(androidx.paging.LoadState.NotLoading(false), androidx.paging.LoadState.NotLoading(true), androidx.paging.LoadState.NotLoading(true)))
                }.flowOn(Dispatchers.IO)
        }

    fun pagingSeriesByCategory(categoryId: String): Flow<PagingData<Series>> =
        Pager(pagingConfig) { seriesDao.pagingByCategory(categoryId) }
            .flow.map { data -> data.map { it.toModel() } }

    /**
     * Whole series cache in the requested [sort] order, with [hidden] categories
     * (from Content Manager) excluded so they never leak into the "all" view.
     */
    fun pagingSeriesAll(sort: ContentSort, hidden: List<String> = emptyList()): Flow<PagingData<Series>> =
        Pager(pagingConfig) {
            when (sort) {
                ContentSort.RECENT -> seriesDao.pagingRecent(hidden)
                ContentSort.NAME -> seriesDao.pagingAllByName(hidden)
                ContentSort.RATING -> seriesDao.pagingAllByRating(hidden)
                ContentSort.YEAR -> seriesDao.pagingAllByYear(hidden)
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

    /**
     * Fetches and caches only the series *categories* (not the series themselves),
     * mirroring [VodCatalogRepository.refreshVodCategories]. Cheap, so the Series rail renders right
     * away; the per-category [loaded] flag is preserved across refreshes so a
     * re-sync never forces a re-download of an already-cached category.
     */
    suspend fun refreshSeriesCategories(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        val generation = support.refreshGenerations.begin("series_categories", config)
        try {
            val api = support.buildXtreamApi(config.serverUrl)
            val catList = api.getSeriesCategories(config.username, config.password)
            val cachedCategories = seriesCategoryDao.getAll()
            val alreadyLoaded = cachedCategories.filter { it.loaded }.mapTo(mutableSetOf()) { it.id }
            val categories = catList.mapIndexedNotNull { index, c ->
                val id = c.categoryId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapIndexedNotNull null
                SeriesCategoryEntity(
                    id = id,
                    name = c.categoryName ?: "Uncategorized",
                    position = index,
                    loaded = id in alreadyLoaded
                )
            }.distinctBy { it.id }
            val cachedIds = cachedCategories.mapTo(mutableSetOf()) { it.id }
            when (
                val decision = support.evaluateRefresh(
                    CatalogDataset.SERIES_CATEGORIES,
                    generation,
                    DatasetSnapshot(
                        generation.policyExistingCount(cachedCategories.size),
                        catList.size,
                        categories.size,
                        overlapCount = categories.count { it.id in cachedIds },
                    ),
                )
            ) {
                DatasetRefreshDecision.Apply -> Unit
                is DatasetRefreshDecision.PreserveCache ->
                    return@withContext preservedDataset(CatalogDataset.SERIES_CATEGORIES, decision.reason)
            }
            val incomingIds = categories.mapTo(mutableSetOf()) { it.id }
            val staleCategoryIds = cachedCategories.map { it.id }
                .filterNot { it in incomingIds }
            val committed = support.commitSnapshot(config, generation) {
                if (staleCategoryIds.isNotEmpty()) {
                    val staleSeriesIds = mutableListOf<String>()
                    for (id in staleCategoryIds) {
                        staleSeriesIds += seriesDao.itemsForCategory(id).map { it.id }
                    }
                    if (staleSeriesIds.isNotEmpty()) {
                        seriesDao.deleteEpisodesForSeriesIds(staleSeriesIds)
                        seriesDao.deleteSeriesByIds(staleSeriesIds)
                        seriesFtsDao.deleteByIds(staleSeriesIds)
                    }
                    seriesCategoryDao.deleteByIds(staleCategoryIds)
                }
                if (categories.isNotEmpty()) seriesCategoryDao.upsertAll(categories)
            }
            if (!committed) return@withContext staleDataset(CatalogDataset.SERIES_CATEGORIES)
            Outcome.Success(categories.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            e.toOutcomeFailure()
        }
    }

    /** True if this category's series have already been downloaded into the cache. */
    suspend fun isSeriesCategoryLoaded(categoryId: String): Boolean = withContext(Dispatchers.IO) {
        seriesCategoryDao.getById(categoryId)?.loaded == true
    }

    /** Visible series categories that still need a first catalog download. */
    suspend fun unloadedSeriesCategoryIds(hidden: Set<String>): List<String> =
        withContext(Dispatchers.IO) {
            seriesCategoryDao.getAll()
                .filter { !it.loaded && it.id !in hidden }
                .map { it.id }
        }

    /** Every visible series category, used by an explicit user refresh. */
    suspend fun seriesCategoryIds(hidden: Set<String>): List<String> =
        withContext(Dispatchers.IO) {
            seriesCategoryDao.getAll().filter { it.id !in hidden }.map { it.id }
        }

    /**
     * Lazily downloads a single series category's series and *merges* them into
     * the cache (no full-table wipe), so other already-downloaded categories stay
     * intact. Skips the network when the category is already loaded unless [force]
     * is set. Keeps the FTS index in lockstep and marks the category loaded.
     */
    /** Some panels never update get_series.last_modified when adding episodes.
     * Check the visible category every 15 minutes, or the whole visible catalog
     * every 6 hours per series, at most [SeriesDateSweepPolicy.SERIES_DATES_PER_CALL] series per call
     * with two requests in flight, so a 5,000-title catalog never turns into a
     * request storm. A 429/5xx aborts the sweep and backs off. No TMDB, posters,
     * playback or episode writes. One batch commit keeps Paging focus stable.
     */
    suspend fun refreshSeriesEpisodeDates(
        config: SourceConfig, categoryId: String?, hidden: List<String>, force: Boolean = false,
    ): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        val now = System.currentTimeMillis()
        if (SeriesDateSweepPolicy.isPaused(now, support.seriesDatesBackoffUntil)) return@withContext Outcome.Success(0)
        val ids = seriesDao.episodeDatesDue(
            categoryId, hidden, SeriesDateSweepPolicy.dueBefore(now, categoryId, force), SeriesDateSweepPolicy.SERIES_DATES_PER_CALL,
        )
        if (ids.isEmpty()) return@withContext Outcome.Success(0)
        val generation = support.refreshGenerations.begin("series_dates:${categoryId ?: "all"}", config)
        val api = support.buildXtreamApi(config.serverUrl)
        var applied = 0
        // First 429/5xx status seen; the panel is asking us to stop, not retry.
        val throttledStatus = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            // Small categories commit together so posters do not jump every time
            // one response arrives. Large categories publish bounded batches.
            ids.chunked(20).forEach { batch ->
                val updates = mutableListOf<Pair<String, Long>>()
                coroutineScope {
                    batch.chunked(2).forEach { pair ->
                        if (throttledStatus.get() != 0) return@forEach
                        pair.map { id -> async {
                            try {
                                val info = api.getSeriesInfo(config.username, config.password, id)
                                val episodes = info.episodes ?: return@async null
                                id to MetadataPolicy.episodeTimestamp(
                                    episodes.values.flatten().map { it.added } + info.info?.lastEpisodeAdded, now,
                                )
                            } catch (e: CancellationException) { throw e }
                            catch (e: HttpException) {
                                if (SeriesDateSweepPolicy.isThrottleStatus(e.code())) throttledStatus.compareAndSet(0, e.code())
                                null
                            }
                            catch (_: Exception) { null }
                        } }.awaitAll().filterNotNull().let(updates::addAll)
                    }
                }
                val committed = support.commitSnapshot(config, generation) {
                    updates.forEach { (id, date) -> seriesDao.updateEpisodeDate(id, date, now) }
                }
                if (!committed) return@withContext staleDataset(CatalogDataset.SERIES_CATEGORY)
                applied += updates.size
                val status = throttledStatus.get()
                if (status != 0) {
                    val backoff = SeriesDateSweepPolicy.backoffMs(status)
                    support.seriesDatesBackoffUntil = System.currentTimeMillis() + backoff
                    Logger.w("CatalogSync", "episode date sweep paused ${backoff / 60_000} min after HTTP $status")
                    return@withContext Outcome.Success(applied)
                }
            }
            Outcome.Success(applied)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { e.toOutcomeFailure() }
    }

    suspend fun refreshSeriesCategory(
        config: SourceConfig,
        categoryId: String,
        force: Boolean = false
    ): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        val generation = support.refreshGenerations.begin("series_category:$categoryId", config)
        val category = seriesCategoryDao.getById(categoryId)
        if (!force && category?.loaded == true) return@withContext Outcome.Success(0)
        try {
            val api = support.buildXtreamApi(config.serverUrl)
            val catName = category?.name ?: "Uncategorized"
            val catPosition = category?.position ?: Int.MAX_VALUE
            val existing = seriesDao.itemsForCategory(categoryId).associateBy { it.id }
            val received = api.getSeries(config.username, config.password, categoryId)
            val scoped = received.filter { it.categoryId == null || it.categoryId == categoryId }
            val items = scoped
                .mapIndexedNotNull { position, s ->
                    val id = s.seriesId ?: return@mapIndexedNotNull null
                    val previous = existing[id]
                    SeriesEntity(
                        id = id,
                        name = s.name ?: "Unknown",
                        posterUrl = s.cover?.takeIf { it.isNotBlank() }
                            ?: previous?.posterUrl,
                        backdropUrl = previous?.backdropUrl,
                        categoryId = categoryId,
                        categoryName = catName,
                        rating = s.rating?.toDoubleOrNull() ?: previous?.rating,
                        plot = s.plot ?: previous?.plot,
                        cast = s.cast ?: previous?.cast,
                        director = s.director ?: previous?.director,
                        genre = s.genre ?: previous?.genre,
                        releaseDate = s.releaseDate ?: previous?.releaseDate,
                        trailerUrl = youtube(s.youtubeTrailer) ?: previous?.trailerUrl,
                        tmdbId = MetadataPolicy.tmdbId(s.tmdbId) ?: previous?.tmdbId,
                        addedAt = MetadataPolicy.newest(
                            previous?.addedAt ?: 0L, s.lastModified, s.added,
                        ),
                        latestEpisodeAt = MetadataPolicy.newest(previous?.latestEpisodeAt ?: 0L, s.lastEpisodeAdded),
                        episodeCheckedAt = previous?.episodeCheckedAt ?: 0L,
                        position = position,
                        categoryPosition = catPosition
                    )
                }
            when (
                val decision = support.evaluateRefresh(
                    CatalogDataset.SERIES_CATEGORY,
                    generation,
                    DatasetSnapshot(
                        generation.policyExistingCount(existing.size),
                        scoped.size,
                        items.size,
                    ),
                )
            ) {
                DatasetRefreshDecision.Apply -> Unit
                is DatasetRefreshDecision.PreserveCache ->
                    return@withContext preservedDataset(CatalogDataset.SERIES_CATEGORY, decision.reason)
            }
            // How many of these are genuinely new to the cache. Only meaningful on a
            // forced re-check of an already-loaded category (the launch sweep). Guard on
            // categoryId so a backend that ignores category filtering can't inflate it.
            val existingIds = existing.keys
            val incomingIds = items.mapTo(mutableSetOf()) { it.id }
            val staleIds = existingIds.filterNot { it in incomingIds }
            val newCount = items.count { it.id !in existingIds }
            // Merge (REPLACE on id), keeping the FTS index in lockstep, atomically.
            val committed = support.commitSnapshot(config, generation) {
                if (staleIds.isNotEmpty()) {
                    seriesDao.deleteEpisodesForSeriesIds(staleIds)
                    seriesDao.deleteSeriesByIds(staleIds)
                    seriesFtsDao.deleteByIds(staleIds)
                }
                if (items.isNotEmpty()) {
                    seriesDao.upsertSeries(items)
                    seriesFtsDao.deleteByIds(items.map { it.id })
                    seriesFtsDao.insertAll(items.map { SeriesFtsEntity(it.id, it.name) })
                }
                seriesCategoryDao.markLoaded(categoryId)
            }
            if (!committed) return@withContext staleDataset(CatalogDataset.SERIES_CATEGORY)
            Outcome.Success(if (force) newCount else items.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            e.toOutcomeFailure()
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
        val generation = support.refreshGenerations.begin("series_episodes:$seriesId", config)
        try {
            val api = support.buildXtreamApi(config.serverUrl)
            val info = api.getSeriesInfo(config.username, config.password, seriesId)
            val cachedEntity = seriesDao.getById(seriesId)
            val cachedEpisodeEntities = seriesDao.episodesFor(seriesId)
            val detail = info.info
            val enrichedSeries = cachedEntity?.toModel()?.let { cached ->
                tmdb.enrichWithTmdb(
                    cached.copy(
                        plot = detail?.plot ?: cached.plot,
                        cast = detail?.cast ?: cached.cast,
                        director = detail?.director ?: cached.director,
                        genre = detail?.genre ?: cached.genre,
                        releaseDate = detail?.releaseDate ?: cached.releaseDate,
                        rating = detail?.rating?.toDoubleOrNull() ?: cached.rating,
                        posterUrl = detail?.cover?.takeIf { it.isNotBlank() }
                            ?: cached.posterUrl,
                        trailerUrl = youtube(detail?.youtubeTrailer) ?: cached.trailerUrl,
                        tmdbId = detail?.tmdbId ?: cached.tmdbId,
                    ),
                )
            }
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
                        streamUrl = secureValues.encrypt(
                            XtreamUrlBuilder.seriesEpisodeUrl(
                                config.serverUrl, config.username, config.password, eid,
                                e.containerExtension ?: "mp4",
                                directSource = e.directSource ?: e.info?.directSource,
                            ),
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
            val receivedEpisodeCount = info.episodes?.values?.sumOf { it.size } ?: 0
            when (
                val decision = support.evaluateRefresh(
                    CatalogDataset.SERIES_EPISODES,
                    generation,
                    DatasetSnapshot(
                        generation.policyExistingCount(cachedEpisodeEntities.size),
                        receivedEpisodeCount,
                        episodeEntities.size,
                    ),
                )
            ) {
                DatasetRefreshDecision.Apply -> Unit
                is DatasetRefreshDecision.PreserveCache -> {
                    Logger.w(
                        "CatalogSync",
                        "preserved SERIES_EPISODES cache: ${decision.reason}",
                    )
                    return@withContext cachedSeasons(seriesId)
                }
            }
            val committed = support.commitSnapshot(config, generation) {
                if (cachedEntity != null && enrichedSeries != null) {
                    seriesDao.upsertSeries(
                        listOf(
                            cachedEntity.copy(
                                name = enrichedSeries.name,
                                posterUrl = enrichedSeries.posterUrl,
                                backdropUrl = enrichedSeries.backdropUrl,
                                rating = enrichedSeries.rating,
                                plot = enrichedSeries.plot,
                                cast = enrichedSeries.cast,
                                director = enrichedSeries.director,
                                genre = enrichedSeries.genre,
                                releaseDate = enrichedSeries.releaseDate,
                                trailerUrl = enrichedSeries.trailerUrl,
                                tmdbId = enrichedSeries.tmdbId,
                                addedAt = MetadataPolicy.newest(cachedEntity.addedAt, detail?.lastModified),
                                latestEpisodeAt = MetadataPolicy.episodeTimestamp(
                                    info.episodes.orEmpty().values.flatten().map { it.added } + detail?.lastEpisodeAdded,
                                    System.currentTimeMillis(),
                                ),
                                episodeCheckedAt = System.currentTimeMillis(),
                            ),
                        ),
                    )
                }
                // A successful response is authoritative: remove episodes deleted
                // by the provider instead of leaving stale playable rows behind.
                seriesDao.deleteEpisodesForSeries(seriesId)
                if (episodeEntities.isNotEmpty()) seriesDao.upsertEpisodes(episodeEntities)
            }
            if (!committed) return@withContext cachedSeasons(seriesId)
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

    // ---- Similar / recommended -----------------------------------------

    /** Other series in the same category as [item], for the detail "Similar" rail. */
    suspend fun similarSeries(item: Series, limit: Int = 20): List<Series> =
        withContext(Dispatchers.IO) {
            val categoryId = item.categoryId ?: return@withContext emptyList()
            seriesDao.sampleByCategory(categoryId, item.id, limit).map { it.toModel() }
        }
}
