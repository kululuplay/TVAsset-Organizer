/*
 * VodCatalogRepository.kt
 * Movie catalog: category snapshot + lazy per-category downloads (merged, never
 * wiped), Paging 3 views, on-demand detail with optional TMDB enrichment.
 */
package com.iptv.player.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.local.LikeEscape
import com.iptv.player.data.local.entity.VodCategoryEntity
import com.iptv.player.data.local.entity.VodEntity
import com.iptv.player.data.local.entity.VodFtsEntity
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.ContentSort
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.model.VodItem
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.remote.MetadataPolicy
import com.iptv.player.data.remote.XtreamUrlBuilder
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.Logger
import com.iptv.player.util.NewContentNotifier
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

internal class VodCatalogRepository(
    db: AppDatabase,
    private val settings: SettingsStore,
    override val secureValues: SecureValueCodec,
    private val support: CatalogSyncSupport,
    private val tmdb: TmdbMetadataRepository,
    private val profiles: ProfileRepository,
    private val pagingConfig: PagingConfig,
) : EntityMappers {

    private val vodDao = db.vodDao()
    private val vodCategoryDao = db.vodCategoryDao()
    private val recommendationDao = db.recommendationDao()
    private val vodFtsDao = db.vodFtsDao()

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
        vodDao.search(LikeEscape.escape(query)).map { it.map { e -> e.toModel() } }

    // ---- Paging 3 (bounded movie lists) ---------------------------------

    /** Whole movie cache, newest first — the "Recently added" default view. */
    fun pagingRecentVod(hidden: List<String> = emptyList()): Flow<PagingData<VodItem>> =
        Pager(pagingConfig) { vodDao.pagingRecent(hidden) }
            .flow.map { data -> data.map { it.toModel() } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    fun pagingRecommendedVod(hidden: List<String>): Flow<PagingData<VodItem>> =
        settings.activeProfileId.distinctUntilChanged().flatMapLatest { profileId ->
            combine(recommendationDao.observePreferences(profileId), vodDao.observeRecommendationCatalog()) { history, _ -> history }
                .debounce(600).mapLatest { history ->
                    val ids = profiles.recommendationIds(profileId, "movie", history, hidden)
                    val items = vodDao.recommendedItems(ids, hidden).associateBy { it.id }
                    PagingData.from(ids.mapNotNull { items[it]?.toModel() }, sourceLoadStates = androidx.paging.LoadStates(androidx.paging.LoadState.NotLoading(false), androidx.paging.LoadState.NotLoading(true), androidx.paging.LoadState.NotLoading(true)))
                }.flowOn(Dispatchers.IO)
        }

    fun pagingVodByCategory(categoryId: String): Flow<PagingData<VodItem>> =
        Pager(pagingConfig) { vodDao.pagingByCategory(categoryId) }
            .flow.map { data -> data.map { it.toModel() } }

    /**
     * Whole movie cache in the requested [sort] order, with [hidden] categories
     * (from Content Manager) excluded so they never leak into the "all" view.
     */
    fun pagingVodAll(sort: ContentSort, hidden: List<String> = emptyList()): Flow<PagingData<VodItem>> =
        Pager(pagingConfig) {
            when (sort) {
                ContentSort.RECENT -> vodDao.pagingRecent(hidden)
                ContentSort.NAME -> vodDao.pagingAllByName(hidden)
                ContentSort.RATING -> vodDao.pagingAllByRating(hidden)
                ContentSort.YEAR -> vodDao.pagingAllByYear(hidden)
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

    /**
     * Fetches and caches only the movie *categories* (not the movies themselves).
     * This is cheap and lets the Movies rail render immediately. The per-category
     * [loaded] flag is preserved across refreshes so re-syncing the category list
     * never forces a re-download of categories whose movies are already cached.
     */
    suspend fun refreshVodCategories(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        val generation = support.refreshGenerations.begin("vod_categories", config)
        try {
            val api = support.buildXtreamApi(config.serverUrl)
            val catList = api.getVodCategories(config.username, config.password)
            val cachedCategories = vodCategoryDao.getAll()
            val alreadyLoaded = cachedCategories.filter { it.loaded }.mapTo(mutableSetOf()) { it.id }
            val categories = catList.mapIndexedNotNull { index, c ->
                val id = c.categoryId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapIndexedNotNull null
                VodCategoryEntity(
                    id = id,
                    name = c.categoryName ?: "Uncategorized",
                    position = index,
                    loaded = id in alreadyLoaded
                )
            }.distinctBy { it.id }
            val cachedIds = cachedCategories.mapTo(mutableSetOf()) { it.id }
            when (
                val decision = support.evaluateRefresh(
                    CatalogDataset.VOD_CATEGORIES,
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
                    return@withContext preservedDataset(CatalogDataset.VOD_CATEGORIES, decision.reason)
            }
            val incomingIds = categories.mapTo(mutableSetOf()) { it.id }
            val staleCategoryIds = cachedCategories.map { it.id }
                .filterNot { it in incomingIds }
            val committed = support.commitSnapshot(config, generation) {
                if (staleCategoryIds.isNotEmpty()) {
                    val staleMovieIds = mutableListOf<String>()
                    for (id in staleCategoryIds) {
                        staleMovieIds += vodDao.itemsForCategory(id).map { it.id }
                    }
                    staleMovieIds.forEachSqlChunk { chunk ->
                        vodDao.deleteByIds(chunk)
                        vodFtsDao.deleteByIds(chunk)
                    }
                    staleCategoryIds.forEachSqlChunk { vodCategoryDao.deleteByIds(it) }
                }
                if (categories.isNotEmpty()) vodCategoryDao.upsertAll(categories)
            }
            if (!committed) return@withContext staleDataset(CatalogDataset.VOD_CATEGORIES)
            Outcome.Success(categories.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            e.toOutcomeFailure()
        }
    }

    /** Sequential movie refresh shared by the Movies reload and explicit dashboard refresh. */
    suspend fun refreshMovieCatalog(
        config: SourceConfig,
        forceAll: Boolean,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): MovieCatalogRefreshReport = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) {
            return@withContext MovieCatalogRefreshReport(0, 0, emptyList())
        }
        var indexFailure: Outcome.Failure? = null
        if (forceAll || vodCategoryDao.getAll().isEmpty()) {
            when (val result = refreshVodCategories(config)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> indexFailure = result
            }
        }
        // Failed index retrieval still refreshes known categories, without hiding that failure.
        val hidden = settings.hiddenCategories(ContentType.VOD).first()
        val categories = vodCategoryDao.getAll()
            .filter { it.id !in hidden && (forceAll || !it.loaded) }
            .map { MovieRefreshCategory(it.id, it.name) }
        MovieCatalogRefresh.run(categories, indexFailure, onProgress) { id ->
            refreshVodCategory(config, id, force = forceAll)
        }
    }

    /** True if this category's movies have already been downloaded into the cache. */
    suspend fun isVodCategoryLoaded(categoryId: String): Boolean = withContext(Dispatchers.IO) {
        vodCategoryDao.getById(categoryId)?.loaded == true
    }

    /** Visible movie categories that still need a first catalog download. */
    suspend fun unloadedVodCategoryIds(hidden: Set<String>): List<String> =
        withContext(Dispatchers.IO) {
            vodCategoryDao.getAll()
                .filter { !it.loaded && it.id !in hidden }
                .map { it.id }
        }

    /** Every visible movie category, used by an explicit user refresh. */
    suspend fun vodCategoryIds(hidden: Set<String>): List<String> =
        withContext(Dispatchers.IO) {
            vodCategoryDao.getAll().filter { it.id !in hidden }.map { it.id }
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
        val generation = support.refreshGenerations.begin("vod_category:$categoryId", config)
        val category = vodCategoryDao.getById(categoryId)
        if (!force && category?.loaded == true) return@withContext Outcome.Success(0)
        try {
            val api = support.buildXtreamApi(config.serverUrl)
            val catName = category?.name ?: "Uncategorized"
            val catPosition = category?.position ?: Int.MAX_VALUE
            val existing = vodDao.itemsForCategory(categoryId).associateBy { it.id }
            val received = api.getVodStreams(config.username, config.password, categoryId)
            val scoped = received.filter { it.categoryId == null || it.categoryId == categoryId }
            val items = scoped
                .mapIndexedNotNull { position, s ->
                    val id = s.streamId ?: return@mapIndexedNotNull null
                    val previous = existing[id]
                    VodEntity(
                        id = id,
                        name = s.name ?: "Unknown",
                        streamUrl = secureValues.encrypt(
                            XtreamUrlBuilder.movieUrl(
                                config.serverUrl, config.username, config.password, id,
                                s.containerExtension ?: "mp4",
                                directSource = s.directSource,
                            ),
                        ),
                        posterUrl = s.streamIcon?.takeIf { it.isNotBlank() }
                            ?: previous?.posterUrl,
                        backdropUrl = previous?.backdropUrl,
                        categoryId = categoryId,
                        categoryName = catName,
                        rating = s.rating?.toDoubleOrNull() ?: previous?.rating,
                        plot = previous?.plot,
                        cast = previous?.cast,
                        director = previous?.director,
                        genre = previous?.genre,
                        releaseDate = s.releaseDate?.takeIf { it.isNotBlank() }
                            ?: s.year?.takeIf { it.isNotBlank() }
                            ?: previous?.releaseDate,
                        durationSecs = previous?.durationSecs,
                        trailerUrl = previous?.trailerUrl,
                        tmdbId = previous?.tmdbId,
                        addedAt = MetadataPolicy.newest(previous?.addedAt ?: 0L, s.added),
                        position = position,
                        categoryPosition = catPosition
                    )
                }
            when (
                val decision = support.evaluateRefresh(
                    CatalogDataset.VOD_CATEGORY,
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
                    return@withContext preservedDataset(CatalogDataset.VOD_CATEGORY, decision.reason)
            }
            // How many of these are genuinely new to the cache. Only meaningful on a
            // forced re-check of an already-loaded category (the launch sweep): a first
            // load has no prior rows, so everything would look "new". Guard on
            // categoryId so a backend that ignores category filtering and returns the
            // whole catalog can't inflate this category's count.
            val existingIds = existing.keys
            val incomingIds = items.mapTo(mutableSetOf()) { it.id }
            val staleIds = existingIds.filterNot { it in incomingIds }
            val newCount = items.count { it.id !in existingIds }
            // Merge (REPLACE on id) — never clear, so other categories remain
            // cached. Keep the FTS index in lockstep (drop this batch's old rows
            // then re-insert), all atomically so search never sees a half index.
            val committed = support.commitSnapshot(config, generation) {
                staleIds.forEachSqlChunk { chunk ->
                    vodDao.deleteByIds(chunk)
                    vodFtsDao.deleteByIds(chunk)
                }
                if (items.isNotEmpty()) {
                    vodDao.upsertAll(items)
                    items.map { it.id }.forEachSqlChunk { vodFtsDao.deleteByIds(it) }
                    vodFtsDao.insertAll(items.map { VodFtsEntity(it.id, it.name) })
                }
                vodCategoryDao.markLoaded(categoryId)
            }
            if (!committed) return@withContext staleDataset(CatalogDataset.VOD_CATEGORY)
            // On a forced launch sweep, return the genuine new-count so the caller can
            // aggregate across categories and notify once; non-forced loads keep the
            // legacy item-count contract.
            Outcome.Success(if (force) newCount else items.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            e.toOutcomeFailure()
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
        val cachedEntity = vodDao.getById(id) ?: return@withContext null
        val cached = cachedEntity.toModel()
        val merged = try {
            if (config.type == SourceType.XTREAM) {
                val api = support.buildXtreamApi(config.serverUrl)
                val response = api.getVodInfo(config.username, config.password, id)
                check(response.movieData?.streamId == id) { "Mismatched VOD detail identifier" }
                val info = response.info
                val directStreamUrl = XtreamUrlBuilder.resolveDirectSource(
                    config.serverUrl,
                    response.movieData?.directSource,
                )
                cached.copy(
                    streamUrl = directStreamUrl ?: cached.streamUrl,
                    plot = info?.plot ?: cached.plot,
                    cast = info?.cast ?: cached.cast,
                    director = info?.director ?: cached.director,
                    genre = info?.genre ?: cached.genre,
                    releaseDate = info?.releaseDate ?: cached.releaseDate,
                    durationSecs = info?.durationSecs ?: cached.durationSecs,
                    rating = info?.rating?.toDoubleOrNull() ?: cached.rating,
                    trailerUrl = youtube(info?.youtubeTrailer) ?: cached.trailerUrl,
                    posterUrl = info?.movieImage?.takeIf { it.isNotBlank() } ?: cached.posterUrl,
                    tmdbId = info?.tmdbId ?: cached.tmdbId
                )
            } else {
                cached
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Logger.w("Xtream", "VOD detail unavailable; retaining cached metadata", t)
            cached
        }
        val enriched = tmdb.enrichWithTmdb(merged, isMovie = true)
        // Detail metadata must survive leaving this screen. Keeping it in Room
        // also makes year/rating sorts improve as titles are enriched.
        vodDao.upsertAll(
            listOf(
                cachedEntity.copy(
                    name = enriched.name,
                    streamUrl = secureValues.encrypt(enriched.streamUrl),
                    posterUrl = enriched.posterUrl,
                    backdropUrl = enriched.backdropUrl,
                    rating = enriched.rating,
                    plot = enriched.plot,
                    cast = enriched.cast,
                    director = enriched.director,
                    genre = enriched.genre,
                    releaseDate = enriched.releaseDate,
                    durationSecs = enriched.durationSecs,
                    trailerUrl = enriched.trailerUrl,
                    tmdbId = enriched.tmdbId,
                ),
            ),
        )
        enriched
    }

    /**
     * Instant, network-free VOD record straight from the local cache (populated
     * by [refreshVodCategory]). Lets the detail screen render the poster, title and a
     * working Play button immediately while [getVodDetail] enriches in the bg.
     */
    suspend fun getVodCached(id: String): VodItem? = withContext(Dispatchers.IO) {
        vodDao.getById(id)?.toModel()
    }

    // ---- Similar / recommended -----------------------------------------

    /** Other movies in the same category as [item], for the detail "Similar" rail. */
    suspend fun similarMovies(item: VodItem, limit: Int = 20): List<VodItem> =
        withContext(Dispatchers.IO) {
            val categoryId = item.categoryId ?: return@withContext emptyList()
            vodDao.sampleByCategory(categoryId, item.id, limit).map { it.toModel() }
        }
}
