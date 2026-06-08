/*
 * MediaDaos.kt
 * Room DAOs for EPG programs, VOD, series/episodes, profiles, resume positions
 * and EPG id mappings. Reads return Flow; writes are suspend functions.
 */
package com.iptv.player.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iptv.player.data.local.entity.EpgMappingEntity
import com.iptv.player.data.local.entity.EpisodeEntity
import com.iptv.player.data.local.entity.ProfileEntity
import com.iptv.player.data.local.entity.ProgramEntity
import com.iptv.player.data.local.entity.ResumeEntity
import com.iptv.player.data.local.entity.SeriesCategoryEntity
import com.iptv.player.data.local.entity.SeriesEntity
import com.iptv.player.data.local.entity.SeriesFtsEntity
import com.iptv.player.data.local.entity.VodCategoryEntity
import com.iptv.player.data.local.entity.VodEntity
import com.iptv.player.data.local.entity.VodFtsEntity
import com.iptv.player.data.local.entity.WatchedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programs: List<ProgramEntity>)

    @Query("DELETE FROM programs")
    suspend fun clearAll()

    @Query("DELETE FROM programs WHERE stopMs < :before")
    suspend fun clearOld(before: Long)

    /** Current + upcoming programs for a channel ordered by start. */
    @Query("SELECT * FROM programs WHERE epgChannelId = :epgId AND stopMs >= :now ORDER BY startMs LIMIT :limit")
    suspend fun upcoming(epgId: String, now: Long, limit: Int): List<ProgramEntity>

    /** All programs for a channel within a time window (guide timeline). */
    @Query("SELECT * FROM programs WHERE epgChannelId = :epgId AND stopMs >= :from AND startMs <= :to ORDER BY startMs")
    suspend fun inWindow(epgId: String, from: Long, to: Long): List<ProgramEntity>

    @Query("SELECT COUNT(*) FROM programs")
    suspend fun count(): Int

    /** Number of cached programs for a specific (normalized) epg channel id. */
    @Query("SELECT COUNT(*) FROM programs WHERE epgChannelId = :epgId")
    suspend fun countFor(epgId: String): Int
}

@Dao
interface VodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<VodEntity>)

    @Query("DELETE FROM vod")
    suspend fun clearAll()

    @Query("SELECT * FROM vod ORDER BY position")
    fun observeAll(): Flow<List<VodEntity>>

    /** Most recently added movies first, capped to :limit, for the "Recently added" rail. */
    @Query("SELECT * FROM vod ORDER BY addedAt DESC, name LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod WHERE categoryId = :categoryId ORDER BY addedAt DESC, name")
    fun observeByCategory(categoryId: String): Flow<List<VodEntity>>

    @Query("""
        SELECT categoryId, categoryName FROM vod
        WHERE categoryId IS NOT NULL
        GROUP BY categoryId, categoryName
        ORDER BY MIN(categoryPosition)
    """)
    fun observeCategories(): Flow<List<CategoryRow>>

    @Query("SELECT * FROM vod WHERE name LIKE '%' || :query || '%' ORDER BY addedAt DESC, name LIMIT 200")
    fun search(query: String): Flow<List<VodEntity>>

    // ---- Paging 3 sources (bounded, lazily-paged for huge catalogs) -----

    /**
     * Whole movie cache, newest first — the "Recently added" default view.
     * [hidden] category ids are excluded so Content Manager hides leak nowhere
     * (empty list = exclude nothing; rows with no category are always kept).
     */
    @Query("SELECT * FROM vod WHERE (categoryId IS NULL OR categoryId NOT IN (:hidden)) ORDER BY addedAt DESC, name")
    fun pagingRecent(hidden: List<String>): PagingSource<Int, VodEntity>

    @Query("SELECT * FROM vod WHERE categoryId = :categoryId ORDER BY addedAt DESC, name")
    fun pagingByCategory(categoryId: String): PagingSource<Int, VodEntity>

    // ---- Sorted variants (A-Z / rating / year) for the list sort control ----
    @Query("SELECT * FROM vod WHERE (categoryId IS NULL OR categoryId NOT IN (:hidden)) ORDER BY name")
    fun pagingAllByName(hidden: List<String>): PagingSource<Int, VodEntity>

    @Query("SELECT * FROM vod WHERE (categoryId IS NULL OR categoryId NOT IN (:hidden)) ORDER BY rating DESC, name")
    fun pagingAllByRating(hidden: List<String>): PagingSource<Int, VodEntity>

    @Query("SELECT * FROM vod WHERE (categoryId IS NULL OR categoryId NOT IN (:hidden)) ORDER BY releaseDate DESC, name")
    fun pagingAllByYear(hidden: List<String>): PagingSource<Int, VodEntity>

    @Query("SELECT * FROM vod WHERE categoryId = :categoryId ORDER BY name")
    fun pagingCategoryByName(categoryId: String): PagingSource<Int, VodEntity>

    @Query("SELECT * FROM vod WHERE categoryId = :categoryId ORDER BY rating DESC, name")
    fun pagingCategoryByRating(categoryId: String): PagingSource<Int, VodEntity>

    @Query("SELECT * FROM vod WHERE categoryId = :categoryId ORDER BY releaseDate DESC, name")
    fun pagingCategoryByYear(categoryId: String): PagingSource<Int, VodEntity>

    /** FTS-backed instant search, paged. [query] is a sanitized FTS MATCH expression. */
    @Query("""
        SELECT v.* FROM vod v
        JOIN vod_fts ON v.id = vod_fts.id
        WHERE vod_fts MATCH :query
          AND (v.categoryId IS NULL OR v.categoryId NOT IN (:hidden))
        ORDER BY v.addedAt DESC, v.name
    """)
    fun pagingSearch(query: String, hidden: List<String>): PagingSource<Int, VodEntity>

    /** Movie count per category id, used for the category row badges. */
    @Query("""
        SELECT categoryId AS categoryId, COUNT(*) AS count FROM vod
        WHERE categoryId IS NOT NULL
        GROUP BY categoryId
    """)
    fun observeCategoryCounts(): Flow<List<CategoryCountRow>>

    @Query("SELECT * FROM vod WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VodEntity?

    /** All movie ids in a category, used to detect newly-added movies on refresh. */
    @Query("SELECT id FROM vod WHERE categoryId = :categoryId")
    suspend fun idsForCategory(categoryId: String): List<String>

    /** A handful of other movies in the same category, for the "Similar" rail. */
    @Query("SELECT * FROM vod WHERE categoryId = :categoryId AND id != :excludeId ORDER BY addedAt DESC, name LIMIT :limit")
    suspend fun sampleByCategory(categoryId: String, excludeId: String, limit: Int): List<VodEntity>
}

@Dao
interface VodCategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<VodCategoryEntity>)

    @Query("DELETE FROM vod_categories")
    suspend fun clearAll()

    @Query("SELECT * FROM vod_categories ORDER BY position")
    fun observeAll(): Flow<List<VodCategoryEntity>>

    @Query("SELECT * FROM vod_categories ORDER BY position")
    suspend fun getAll(): List<VodCategoryEntity>

    @Query("SELECT * FROM vod_categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VodCategoryEntity?

    @Query("SELECT id FROM vod_categories WHERE loaded = 1")
    suspend fun loadedIds(): List<String>

    @Query("UPDATE vod_categories SET loaded = 1 WHERE id = :id")
    suspend fun markLoaded(id: String)
}

@Dao
interface SeriesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(items: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(items: List<EpisodeEntity>)

    @Query("DELETE FROM series")
    suspend fun clearSeries()

    @Query("SELECT * FROM series ORDER BY position")
    fun observeAll(): Flow<List<SeriesEntity>>

    /** Most recently added series first, capped to :limit, for the "Recently added" rail. */
    @Query("SELECT * FROM series ORDER BY addedAt DESC, name LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY addedAt DESC, name")
    fun observeByCategory(categoryId: String): Flow<List<SeriesEntity>>

    @Query("""
        SELECT categoryId, categoryName FROM series
        WHERE categoryId IS NOT NULL
        GROUP BY categoryId, categoryName
        ORDER BY MIN(categoryPosition)
    """)
    fun observeCategories(): Flow<List<CategoryRow>>

    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY addedAt DESC, name LIMIT 200")
    fun search(query: String): Flow<List<SeriesEntity>>

    // ---- Paging 3 sources (bounded, lazily-paged for huge catalogs) -----

    /**
     * Whole series cache, newest first — the "Recently added" default view.
     * [hidden] category ids are excluded so Content Manager hides leak nowhere
     * (empty list = exclude nothing; rows with no category are always kept).
     */
    @Query("SELECT * FROM series WHERE (categoryId IS NULL OR categoryId NOT IN (:hidden)) ORDER BY addedAt DESC, name")
    fun pagingRecent(hidden: List<String>): PagingSource<Int, SeriesEntity>

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY addedAt DESC, name")
    fun pagingByCategory(categoryId: String): PagingSource<Int, SeriesEntity>

    // ---- Sorted variants (A-Z / rating / year) for the list sort control ----
    @Query("SELECT * FROM series WHERE (categoryId IS NULL OR categoryId NOT IN (:hidden)) ORDER BY name")
    fun pagingAllByName(hidden: List<String>): PagingSource<Int, SeriesEntity>

    @Query("SELECT * FROM series WHERE (categoryId IS NULL OR categoryId NOT IN (:hidden)) ORDER BY rating DESC, name")
    fun pagingAllByRating(hidden: List<String>): PagingSource<Int, SeriesEntity>

    @Query("SELECT * FROM series WHERE (categoryId IS NULL OR categoryId NOT IN (:hidden)) ORDER BY releaseDate DESC, name")
    fun pagingAllByYear(hidden: List<String>): PagingSource<Int, SeriesEntity>

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY name")
    fun pagingCategoryByName(categoryId: String): PagingSource<Int, SeriesEntity>

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY rating DESC, name")
    fun pagingCategoryByRating(categoryId: String): PagingSource<Int, SeriesEntity>

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY releaseDate DESC, name")
    fun pagingCategoryByYear(categoryId: String): PagingSource<Int, SeriesEntity>

    /** FTS-backed instant search, paged. [query] is a sanitized FTS MATCH expression. */
    @Query("""
        SELECT s.* FROM series s
        JOIN series_fts ON s.id = series_fts.id
        WHERE series_fts MATCH :query
          AND (s.categoryId IS NULL OR s.categoryId NOT IN (:hidden))
        ORDER BY s.addedAt DESC, s.name
    """)
    fun pagingSearch(query: String, hidden: List<String>): PagingSource<Int, SeriesEntity>

    /** Series count per category id, used for the category row badges. */
    @Query("""
        SELECT categoryId AS categoryId, COUNT(*) AS count FROM series
        WHERE categoryId IS NOT NULL
        GROUP BY categoryId
    """)
    fun observeCategoryCounts(): Flow<List<CategoryCountRow>>

    @Query("SELECT * FROM series WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SeriesEntity?

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY seasonNumber, episodeNumber")
    suspend fun episodesFor(seriesId: String): List<EpisodeEntity>

    /** All series ids in a category, used to detect newly-added series on refresh. */
    @Query("SELECT id FROM series WHERE categoryId = :categoryId")
    suspend fun idsForCategory(categoryId: String): List<String>

    /** A handful of other series in the same category, for the "Similar" rail. */
    @Query("SELECT * FROM series WHERE categoryId = :categoryId AND id != :excludeId ORDER BY addedAt DESC, name LIMIT :limit")
    suspend fun sampleByCategory(categoryId: String, excludeId: String, limit: Int): List<SeriesEntity>
}

@Dao
interface ProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(profile: ProfileEntity): Long

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("SELECT * FROM profiles ORDER BY createdAt")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY createdAt")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProfileEntity?
}

@Dao
interface ResumeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(resume: ResumeEntity)

    @Query("SELECT * FROM resume WHERE contentId = :contentId LIMIT 1")
    suspend fun get(contentId: String): ResumeEntity?

    /**
     * Most recently watched, in-progress movies/episodes for the Continue Watching
     * rail. Catch-up sessions keep a resume position but are excluded by type.
     */
    @Query(
        "SELECT * FROM resume WHERE positionMs > 0 AND type IN ('movie', 'episode') " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<ResumeEntity>>

    /** All saved positions for a series' episodes (for per-row progress). */
    @Query("SELECT * FROM resume WHERE seriesId = :seriesId")
    suspend fun forSeries(seriesId: String): List<ResumeEntity>

    /** Every saved position, used to draw progress bars across a movie/series grid. */
    @Query("SELECT * FROM resume WHERE positionMs > 0 AND durationMs > 0")
    suspend fun all(): List<ResumeEntity>

    /** Last-watched episode of a series, for a one-tap "continue" action. */
    @Query("SELECT * FROM resume WHERE seriesId = :seriesId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestForSeries(seriesId: String): ResumeEntity?

    @Query("DELETE FROM resume WHERE contentId = :contentId")
    suspend fun clear(contentId: String)
}

@Dao
interface WatchedDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun mark(entry: WatchedEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM watched WHERE contentId = :contentId)")
    suspend fun isWatched(contentId: String): Boolean

    /** All watched content ids, used to badge movie/series grids. */
    @Query("SELECT contentId FROM watched")
    suspend fun allIds(): List<String>

    /** Watched episode ids for one series, used to badge the episode rail. */
    @Query("SELECT contentId FROM watched WHERE seriesId = :seriesId")
    suspend fun idsForSeries(seriesId: String): List<String>

    @Query("DELETE FROM watched WHERE contentId = :contentId")
    suspend fun clear(contentId: String)
}

@Dao
interface EpgMappingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(mapping: EpgMappingEntity)

    @Query("SELECT epgChannelId FROM epg_mapping WHERE channelId = :channelId LIMIT 1")
    suspend fun get(channelId: String): String?

    @Query("SELECT * FROM epg_mapping")
    suspend fun getAll(): List<EpgMappingEntity>
}

/**
 * Series categories DAO. Mirrors [VodCategoryDao] so the series screen can show
 * its category rail instantly and lazily load each category's series on demand.
 */
@Dao
interface SeriesCategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<SeriesCategoryEntity>)

    @Query("DELETE FROM series_categories")
    suspend fun clearAll()

    @Query("SELECT * FROM series_categories ORDER BY position")
    fun observeAll(): Flow<List<SeriesCategoryEntity>>

    @Query("SELECT * FROM series_categories ORDER BY position")
    suspend fun getAll(): List<SeriesCategoryEntity>

    @Query("SELECT * FROM series_categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SeriesCategoryEntity?

    @Query("SELECT id FROM series_categories WHERE loaded = 1")
    suspend fun loadedIds(): List<String>

    @Query("UPDATE series_categories SET loaded = 1 WHERE id = :id")
    suspend fun markLoaded(id: String)
}

// ---- Full-text search maintenance DAOs ----------------------------------
// Each mirrors the id/name of its content table. The repository keeps them in
// lockstep: full replace for live, per-category merge (deleteByIds + insertAll)
// for movies/series. FTS4 has no uniqueness constraint, so stale rows must be
// deleted before re-insert to avoid duplicate search hits.

@Dao
interface VodFtsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<VodFtsEntity>)

    @Query("DELETE FROM vod_fts")
    suspend fun clearAll()

    @Query("DELETE FROM vod_fts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface SeriesFtsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<SeriesFtsEntity>)

    @Query("DELETE FROM series_fts")
    suspend fun clearAll()

    @Query("DELETE FROM series_fts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
