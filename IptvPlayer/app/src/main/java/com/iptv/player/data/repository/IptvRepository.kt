/*
 * IptvRepository.kt
 * Single source of truth for all content: live channels, EPG, VOD, series,
 * favorites, recents, profiles and resume positions. Fetches from Xtream or
 * M3U (+ optional TMDB enrichment), caches into Room, and exposes reactive
 * Flows. Network/parse work runs on Dispatchers.IO; errors map to AppError.
 *
 * This class is the facade every caller uses. The work is split into
 * per-domain repositories (live, VOD, series, EPG, library, profiles, search,
 * source) that share one [CatalogSyncSupport] instance so the commit mutex,
 * generation gate and shrink ledger stay process-global.
 */
package com.iptv.player.data.repository

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.model.AccountInfo
import com.iptv.player.data.model.CastMember
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentSort
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.DiagnosticResult
import com.iptv.player.data.model.FavoriteItem
import com.iptv.player.data.model.ManagedCategory
import com.iptv.player.data.model.ManagedChannel
import com.iptv.player.data.model.NowNext
import com.iptv.player.data.model.Profile
import com.iptv.player.data.model.Program
import com.iptv.player.data.model.ResumeMeta
import com.iptv.player.data.model.Season
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.model.VodItem
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit

class IptvRepository(
    private val db: AppDatabase,
    httpClient: OkHttpClient,
    retrofitBuilder: Retrofit.Builder,
    private val settings: SettingsStore,
    secureValues: SecureValueCodec,
    shrinkLedger: ShrinkGuardLedger = ShrinkGuardLedger(),
) {

    /**
     * Shared Paging config. enablePlaceholders=false keeps memory bounded to the
     * loaded windows (the whole point on low-RAM TV boxes); a page of 60 fills a
     * TV poster grid comfortably while staying small.
     */
    private val pagingConfig = PagingConfig(pageSize = 60, enablePlaceholders = false)

    private val support = CatalogSyncSupport(db, retrofitBuilder, settings, shrinkLedger)
    private val tmdb = TmdbMetadataRepository(retrofitBuilder, settings)
    private val source = SourceRepository(db, httpClient, settings, secureValues, support)
    private val live = LiveCatalogRepository(db, httpClient, settings, secureValues, support)
    private val profiles = ProfileRepository(db, settings, secureValues)
    private val vod = VodCatalogRepository(db, settings, secureValues, support, tmdb, profiles, pagingConfig)
    private val series = SeriesCatalogRepository(db, settings, secureValues, support, tmdb, profiles, pagingConfig)
    private val epg = EpgRepository(db, httpClient, settings, secureValues, support)
    private val library = LibraryRepository(db, settings, secureValues, support, live, vod, series, epg)
    private val searchIndex = SearchRepository(db, secureValues, pagingConfig)

    // Only read by [syncAllReport] to size the "cached before" counts.
    private val channelDao = db.channelDao()
    private val epgDao = db.epgDao()
    private val vodCategoryDao = db.vodCategoryDao()
    private val seriesCategoryDao = db.seriesCategoryDao()

    // ---- Secure storage migration ---------------------------------------

    suspend fun migrateSensitiveStorage() = source.migrateSensitiveStorage()

    // ---- Reactive reads (UI layer) --------------------------------------

    fun observeCategories(type: ContentType, radio: Int = -1): Flow<List<Category>> =
        live.observeCategories(type, radio)

    fun observeChannels(type: ContentType, radio: Boolean = false): Flow<List<Channel>> =
        live.observeChannels(type, radio)

    fun observeChannelsByCategory(type: ContentType, categoryId: String, radio: Boolean = false): Flow<List<Channel>> =
        live.observeChannelsByCategory(type, categoryId, radio)

    /** Channel counts per category id, used for the category row badges. */
    fun observeCategoryCounts(type: ContentType, radio: Int = -1): Flow<Map<String, Int>> =
        live.observeCategoryCounts(type, radio)

    /** Favorite live channels, minus those in categories hidden by Content Manager. */
    fun observeFavorites(): Flow<List<Channel>> = library.observeFavorites()

    /** All favorites across every content type, for the dedicated Favorites screen. */
    fun observeAllFavorites(): Flow<List<FavoriteItem>> = library.observeAllFavorites()

    fun observeRecent(limit: Int = 20): Flow<List<Channel>> = library.observeRecent(limit)

    fun search(
        query: String,
        type: ContentType,
        hiddenCategories: List<String> = emptyList(),
        radio: Int = -1,
    ): Flow<List<Channel>> = searchIndex.search(query, type, hiddenCategories, radio)

    suspend fun getChannel(id: String): Channel? = live.getChannel(id)

    suspend fun liveChannelCount(): Int = live.liveChannelCount()

    /** Live channels that advertise a catch-up / timeshift archive. */
    fun observeCatchupChannels(): Flow<List<Channel>> = live.observeCatchupChannels()

    /** Channels of a type (including hidden) plus their manager state. */
    fun observeManagedChannels(
        type: ContentType,
        categoryId: String? = null
    ): Flow<List<ManagedChannel>> = library.observeManagedChannels(type, categoryId)

    // ---- Managed / visible categories (Content Manager) -----------------

    /** All categories for [type] (incl. hidden) with their hidden flag + count. */
    fun observeManagedCategories(type: ContentType): Flow<List<ManagedCategory>> =
        library.observeManagedCategories(type)

    /** Visible categories for [type] in the user's custom order. */
    fun observeVisibleCategories(type: ContentType, radio: Boolean = false): Flow<List<Category>> =
        library.observeVisibleCategories(type, radio)

    // ---- Channel overrides (hide / custom order) ------------------------

    suspend fun setChannelHidden(channelId: String, hidden: Boolean) =
        library.setChannelHidden(channelId, hidden)

    /** Persists a custom order; index in [orderedIds] becomes the sort key. */
    suspend fun applyChannelOrder(orderedIds: List<String>) = library.applyChannelOrder(orderedIds)

    // ---- Favorites / recents -------------------------------------------

    suspend fun toggleFavorite(channelId: String): Boolean = library.toggleFavorite(channelId)

    /** Whether a generic content id (e.g. "vod_42", "series_7") is favorited. */
    suspend fun isContentFavorite(id: String): Boolean = library.isContentFavorite(id)

    suspend fun markWatched(channelId: String) = library.markWatched(channelId)

    // ---- Source validation / live ---------------------------------------

    suspend fun testSource(config: SourceConfig): Outcome<Unit> = source.testSource(config)

    /**
     * Replaces the live channel snapshot. [force] (a manual user refresh) trusts
     * a much smaller server list that the shrink guard would otherwise reject.
     */
    suspend fun refreshLive(config: SourceConfig, force: Boolean = false): Outcome<Int> =
        live.refreshLive(config, force)

    // ---- VOD ------------------------------------------------------------

    fun observeVod(): Flow<List<VodItem>> = vod.observeVod()

    /** Latest [limit] movies by added date, for the "Recently added" rail. */
    fun observeRecentVod(limit: Int): Flow<List<VodItem>> = vod.observeRecentVod(limit)

    fun observeVodByCategory(categoryId: String): Flow<List<VodItem>> = vod.observeVodByCategory(categoryId)

    /** Movie categories from the dedicated [vod_categories] table. */
    fun observeVodCategories(): Flow<List<Category>> = vod.observeVodCategories()

    /** Movie count per category id, used for the category row badges. */
    fun observeVodCategoryCounts(): Flow<Map<String, Int>> = vod.observeVodCategoryCounts()

    fun searchVod(query: String): Flow<List<VodItem>> = vod.searchVod(query)

    // ---- Paging 3 (bounded movie lists) ---------------------------------

    /** Whole movie cache, newest first — the "Recently added" default view. */
    fun pagingRecentVod(hidden: List<String> = emptyList()): Flow<PagingData<VodItem>> =
        vod.pagingRecentVod(hidden)

    fun pagingRecommendedVod(hidden: List<String>): Flow<PagingData<VodItem>> =
        vod.pagingRecommendedVod(hidden)

    fun pagingVodByCategory(categoryId: String): Flow<PagingData<VodItem>> =
        vod.pagingVodByCategory(categoryId)

    /** Whole movie cache in the requested [sort] order, [hidden] categories excluded. */
    fun pagingVodAll(sort: ContentSort, hidden: List<String> = emptyList()): Flow<PagingData<VodItem>> =
        vod.pagingVodAll(sort, hidden)

    /** One category's movies in the requested [sort] order. */
    fun pagingVodByCategory(categoryId: String, sort: ContentSort): Flow<PagingData<VodItem>> =
        vod.pagingVodByCategory(categoryId, sort)

    /** Instant FTS search, paged. Empty input yields an empty page (no MATCH). */
    fun pagingVodSearch(
        query: String,
        hidden: List<String> = emptyList(),
        sort: ContentSort = ContentSort.RECENT,
    ): Flow<PagingData<VodItem>> = searchIndex.pagingVodSearch(query, hidden, sort)

    /** Fetches and caches only the movie *categories* (not the movies themselves). */
    suspend fun refreshVodCategories(config: SourceConfig): Outcome<Int> = vod.refreshVodCategories(config)

    /** True if this category's movies have already been downloaded into the cache. */
    suspend fun isVodCategoryLoaded(categoryId: String): Boolean = vod.isVodCategoryLoaded(categoryId)

    /** Visible movie categories that still need a first catalog download. */
    suspend fun unloadedVodCategoryIds(hidden: Set<String>): List<String> = vod.unloadedVodCategoryIds(hidden)

    /** Every visible movie category, used by an explicit user refresh. */
    suspend fun vodCategoryIds(hidden: Set<String>): List<String> = vod.vodCategoryIds(hidden)

    /** Lazily downloads a single movie category's movies and merges them into the cache. */
    suspend fun refreshVodCategory(
        config: SourceConfig,
        categoryId: String,
        force: Boolean = false
    ): Outcome<Int> = vod.refreshVodCategory(config, categoryId, force)

    /** Best-effort prefetch of the first movie category. */
    suspend fun prefetchFirstVodCategory(config: SourceConfig): Outcome<Int> =
        vod.prefetchFirstVodCategory(config)

    /** Re-syncs every movie category the user has already opened. Xtream only. */
    suspend fun refreshLoadedVodCategories(config: SourceConfig): Outcome<Int> =
        vod.refreshLoadedVodCategories(config)

    /** Loads full VOD detail on demand, enriching with TMDB when a key is set. */
    suspend fun getVodDetail(config: SourceConfig, id: String): VodItem? = vod.getVodDetail(config, id)

    /** Instant, network-free VOD record straight from the local cache. */
    suspend fun getVodCached(id: String): VodItem? = vod.getVodCached(id)

    // ---- Series ---------------------------------------------------------

    fun observeSeries(): Flow<List<Series>> = series.observeSeries()

    /** Instant, network-free series record straight from the local cache. */
    suspend fun getSeriesCached(id: String): Series? = series.getSeriesCached(id)

    /** Latest [limit] series by added date, for the "Recently added" rail. */
    fun observeRecentSeries(limit: Int): Flow<List<Series>> = series.observeRecentSeries(limit)

    fun observeSeriesByCategory(categoryId: String): Flow<List<Series>> =
        series.observeSeriesByCategory(categoryId)

    /** Series categories from the dedicated [series_categories] table. */
    fun observeSeriesCategories(): Flow<List<Category>> = series.observeSeriesCategories()

    /** Series count per category id, used for the category row badges. */
    fun observeSeriesCategoryCounts(): Flow<Map<String, Int>> = series.observeSeriesCategoryCounts()

    fun searchSeries(query: String): Flow<List<Series>> = series.searchSeries(query)

    // ---- Paging 3 (bounded series lists) --------------------------------

    /** Whole series cache, newest first — the "Recently added" default view. */
    fun pagingRecentSeries(hidden: List<String> = emptyList()): Flow<PagingData<Series>> =
        series.pagingRecentSeries(hidden)

    fun pagingRecommendedSeries(hidden: List<String>): Flow<PagingData<Series>> =
        series.pagingRecommendedSeries(hidden)

    fun pagingSeriesByCategory(categoryId: String): Flow<PagingData<Series>> =
        series.pagingSeriesByCategory(categoryId)

    /** Whole series cache in the requested [sort] order, [hidden] categories excluded. */
    fun pagingSeriesAll(sort: ContentSort, hidden: List<String> = emptyList()): Flow<PagingData<Series>> =
        series.pagingSeriesAll(sort, hidden)

    /** One category's series in the requested [sort] order. */
    fun pagingSeriesByCategory(categoryId: String, sort: ContentSort): Flow<PagingData<Series>> =
        series.pagingSeriesByCategory(categoryId, sort)

    /** Instant FTS search, paged. Empty input yields an empty page (no MATCH). */
    fun pagingSeriesSearch(
        query: String,
        hidden: List<String> = emptyList(),
        sort: ContentSort = ContentSort.RECENT,
    ): Flow<PagingData<Series>> = searchIndex.pagingSeriesSearch(query, hidden, sort)

    /** Fetches and caches only the series *categories* (not the series themselves). */
    suspend fun refreshSeriesCategories(config: SourceConfig): Outcome<Int> =
        series.refreshSeriesCategories(config)

    /** True if this category's series have already been downloaded into the cache. */
    suspend fun isSeriesCategoryLoaded(categoryId: String): Boolean = series.isSeriesCategoryLoaded(categoryId)

    /** Visible series categories that still need a first catalog download. */
    suspend fun unloadedSeriesCategoryIds(hidden: Set<String>): List<String> =
        series.unloadedSeriesCategoryIds(hidden)

    /** Every visible series category, used by an explicit user refresh. */
    suspend fun seriesCategoryIds(hidden: Set<String>): List<String> = series.seriesCategoryIds(hidden)

    /** Throttled episode-date sweep for panels that never bump last_modified. */
    suspend fun refreshSeriesEpisodeDates(
        config: SourceConfig, categoryId: String?, hidden: List<String>, force: Boolean = false,
    ): Outcome<Int> = series.refreshSeriesEpisodeDates(config, categoryId, hidden, force)

    /** Lazily downloads a single series category's series and merges them into the cache. */
    suspend fun refreshSeriesCategory(
        config: SourceConfig,
        categoryId: String,
        force: Boolean = false
    ): Outcome<Int> = series.refreshSeriesCategory(config, categoryId, force)

    /** Best-effort prefetch of the first series category. */
    suspend fun prefetchFirstSeriesCategory(config: SourceConfig): Outcome<Int> =
        series.prefetchFirstSeriesCategory(config)

    /** Re-syncs every series category the user has already opened. Xtream only. */
    suspend fun refreshLoadedSeriesCategories(config: SourceConfig): Outcome<Int> =
        series.refreshLoadedSeriesCategories(config)

    /** Loads seasons + episodes for a series and caches the episodes. */
    suspend fun getSeasons(config: SourceConfig, seriesId: String): List<Season> =
        series.getSeasons(config, seriesId)

    /** Instant, network-free seasons/episodes from the local cache. */
    suspend fun getCachedSeasons(seriesId: String): List<Season> = series.getCachedSeasons(seriesId)

    // ---- EPG ------------------------------------------------------------

    /** Downloads and caches the full XMLTV guide (Xtream xmltv.php). */
    suspend fun refreshEpg(config: SourceConfig): Outcome<Int> = epg.refreshEpg(config)

    suspend fun getNowNext(channel: Channel): NowNext = epg.getNowNext(channel)

    suspend fun getProgramsWindow(channel: Channel, fromMs: Long, toMs: Long): List<Program> =
        epg.getProgramsWindow(channel, fromMs, toMs)

    suspend fun setEpgMapping(channelId: String, epgChannelId: String) =
        epg.setEpgMapping(channelId, epgChannelId)

    // ---- Catch-up / timeshift -------------------------------------------

    /** Past programs available in a channel's archive, newest first. */
    suspend fun getCatchupPrograms(channel: Channel): List<Program> = library.getCatchupPrograms(channel)

    /** Builds a timeshift URL for a past [program] on [channel]; Xtream live only. */
    suspend fun buildCatchupUrl(channel: Channel, program: Program): String? =
        library.buildCatchupUrl(channel, program)

    // ---- Background sync ------------------------------------------------

    /**
     * Refreshes live channels + EPG (and VOD/series when present) for background
     * auto-sync. Returns true if at least the live refresh succeeded.
     */
    suspend fun syncAll(config: SourceConfig): Boolean = syncAllReport(config).liveRefreshSucceeded

    /**
     * Dataset-level report used by diagnostics and future health UI. Unlike the
     * legacy Boolean, this distinguishes a successful refresh from a failed fetch
     * whose previous cache was deliberately preserved.
     */
    suspend fun syncAllReport(config: SourceConfig): SyncReport = withContext(Dispatchers.IO) {
        val results = mutableListOf<DatasetSyncResult>()

        val liveCached = channelDao.idsForType(ContentType.LIVE.name).size
        results += datasetSyncResult("live", liveCached, refreshLive(config))

        if (config.type == SourceType.XTREAM) {
            val epgCached = epgDao.count()
            results += datasetSyncResult("epg", epgCached, refreshEpg(config))

            // Movies and series remain lazy per category: only the cheap category
            // snapshots are refreshed here, never the complete catalogs.
            val vodCached = vodCategoryDao.getAll().size
            results += datasetSyncResult(
                "vod_categories",
                vodCached,
                refreshVodCategories(config),
            )

            val seriesCached = seriesCategoryDao.getAll().size
            results += datasetSyncResult(
                "series_categories",
                seriesCached,
                refreshSeriesCategories(config),
            )
        }
        SyncReport(results)
    }

    // ---- Account info ---------------------------------------------------

    suspend fun getAccountInfo(config: SourceConfig): AccountInfo? = source.getAccountInfo(config)

    // ---- Diagnostics ----------------------------------------------------

    suspend fun pingServer(config: SourceConfig): DiagnosticResult = source.pingServer(config)

    /** The panel root and M3U are not throughput endpoints; do not download them. */
    suspend fun speedTestMbps(config: SourceConfig): DiagnosticResult = source.speedTestMbps(config)

    suspend fun checkDns(config: SourceConfig): DiagnosticResult = source.checkDns(config)

    // ---- Profiles -------------------------------------------------------

    fun observeProfiles(): Flow<List<Profile>> = profiles.observeProfiles()

    suspend fun addProfile(name: String, config: SourceConfig, lockAdult: Boolean): Long =
        profiles.addProfile(name, config, lockAdult)

    /** Ensures the given source is also saved as a switchable profile and returns its id. */
    suspend fun ensureProfile(config: SourceConfig, lockAdult: Boolean = false): Long =
        profiles.ensureProfile(config, lockAdult)

    /** One-time backfill for accounts that connected before profiles were automatic. */
    suspend fun backfillProfileFromSource() = profiles.backfillProfileFromSource()

    suspend fun removeProfile(id: Long) = profiles.removeProfile(id)

    suspend fun getProfile(id: Long): Profile? = profiles.getProfile(id)

    // ---- Resume positions ----------------------------------------------

    suspend fun saveResume(meta: ResumeMeta, positionMs: Long, durationMs: Long) =
        profiles.saveResume(meta, positionMs, durationMs)

    /** Persist against the profile that opened the player. */
    suspend fun saveResumeForProfile(
        profileId: Long,
        meta: ResumeMeta,
        positionMs: Long,
        durationMs: Long,
    ) = profiles.saveResumeForProfile(profileId, meta, positionMs, durationMs)

    suspend fun getResume(contentId: String): Long = profiles.getResume(contentId)

    suspend fun getResumeForProfile(profileId: Long, contentId: String): Long =
        profiles.getResumeForProfile(profileId, contentId)

    suspend fun clearResume(contentId: String) = profiles.clearResume(contentId)

    suspend fun clearResumeForProfile(profileId: Long, contentId: String) =
        profiles.clearResumeForProfile(profileId, contentId)

    /** Commit EndReached state for the profile that opened the player, atomically. */
    suspend fun completePlaybackForProfile(
        profileId: Long,
        contentId: String,
        type: String,
        seriesId: String? = null,
    ) = profiles.completePlaybackForProfile(profileId, contentId, type, seriesId)

    /** Reactive Continue Watching rail: most recent in-progress items first. */
    fun observeContinueWatching(limit: Int = 20): Flow<List<ContinueItem>> =
        profiles.observeContinueWatching(limit)

    /** Saved positions (ms) keyed by raw episode id for one series. */
    suspend fun episodeProgress(seriesId: String): Map<String, Long> = profiles.episodeProgress(seriesId)

    /** Last-watched episode of a series for a one-tap continue action. */
    suspend fun latestSeriesResume(seriesId: String): ContinueItem? = profiles.latestSeriesResume(seriesId)

    /** Watch-progress percent (0..100) keyed by resume contentId, for grid bars. */
    suspend fun allWatchProgress(): Map<String, Int> = profiles.allWatchProgress()

    /** In-progress percent (0..100) keyed by *series* id, for the series grid bars. */
    suspend fun seriesWatchProgress(): Map<String, Int> = profiles.seriesWatchProgress()

    // ---- Watched (finished) state --------------------------------------

    suspend fun markWatched(contentId: String, type: String, seriesId: String? = null) =
        profiles.markWatched(contentId, type, seriesId)

    suspend fun markWatchedForProfile(
        profileId: Long,
        contentId: String,
        type: String,
        seriesId: String? = null,
    ) = profiles.markWatchedForProfile(profileId, contentId, type, seriesId)

    suspend fun isWatched(contentId: String): Boolean = profiles.isWatched(contentId)

    /** All watched content ids, for badging the movie/series grids. */
    suspend fun watchedIds(): Set<String> = profiles.watchedIds()

    /** Watched episode ids for one series (raw episode ids, "ep_" stripped). */
    suspend fun watchedEpisodeIds(seriesId: String): Set<String> = profiles.watchedEpisodeIds(seriesId)

    /** Durable actual-playback deltas, isolated to the profile that opened the player. */
    suspend fun recordWatchTime(profileId: Long, meta: ResumeMeta, deltaMs: Long, nowMs: Long) =
        profiles.recordWatchTime(profileId, meta, deltaMs, nowMs)

    // ---- Similar / recommended -----------------------------------------

    /** Other movies in the same category as [item], for the detail "Similar" rail. */
    suspend fun similarMovies(item: VodItem, limit: Int = 20): List<VodItem> = vod.similarMovies(item, limit)

    /** Other series in the same category as [item], for the detail "Similar" rail. */
    suspend fun similarSeries(item: Series, limit: Int = 20): List<Series> = series.similarSeries(item, limit)

    // ---- TMDB enrichment ------------------------------------------------

    /** Cast list for a detail screen; degrades to the source's name-only list. */
    suspend fun castFor(
        name: String,
        tmdbId: String?,
        rawCast: String?,
        isMovie: Boolean
    ): List<CastMember> = tmdb.castFor(name, tmdbId, rawCast, isMovie)

    /** Fetch the selected season only. Metadata never creates a playable provider episode. */
    suspend fun enrichSeason(series: Series, season: Season): Season = tmdb.enrichSeason(series, season)

    /** Decodes Xtream's base64 EPG fields (used by short-EPG callers). */
    fun decodeEpgText(b64: String?): String = epg.decodeEpgText(b64)
}
