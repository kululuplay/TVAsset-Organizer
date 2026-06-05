/*
 * MediaDaos.kt
 * Room DAOs for EPG programs, VOD, series/episodes, profiles, resume positions
 * and EPG id mappings. Reads return Flow; writes are suspend functions.
 */
package com.iptv.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iptv.player.data.local.entity.EpgMappingEntity
import com.iptv.player.data.local.entity.EpisodeEntity
import com.iptv.player.data.local.entity.ProfileEntity
import com.iptv.player.data.local.entity.ProgramEntity
import com.iptv.player.data.local.entity.ResumeEntity
import com.iptv.player.data.local.entity.SeriesEntity
import com.iptv.player.data.local.entity.VodEntity
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
}

@Dao
interface VodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<VodEntity>)

    @Query("DELETE FROM vod")
    suspend fun clearAll()

    @Query("SELECT * FROM vod ORDER BY addedAt DESC, name")
    fun observeAll(): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod WHERE categoryId = :categoryId ORDER BY addedAt DESC, name")
    fun observeByCategory(categoryId: String): Flow<List<VodEntity>>

    @Query("SELECT DISTINCT categoryId, categoryName FROM vod WHERE categoryId IS NOT NULL ORDER BY categoryName")
    fun observeCategories(): Flow<List<CategoryRow>>

    @Query("SELECT * FROM vod WHERE name LIKE '%' || :query || '%' ORDER BY addedAt DESC, name LIMIT 200")
    fun search(query: String): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VodEntity?
}

@Dao
interface SeriesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(items: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(items: List<EpisodeEntity>)

    @Query("DELETE FROM series")
    suspend fun clearSeries()

    @Query("SELECT * FROM series ORDER BY addedAt DESC, name")
    fun observeAll(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY addedAt DESC, name")
    fun observeByCategory(categoryId: String): Flow<List<SeriesEntity>>

    @Query("SELECT DISTINCT categoryId, categoryName FROM series WHERE categoryId IS NOT NULL ORDER BY categoryName")
    fun observeCategories(): Flow<List<CategoryRow>>

    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY addedAt DESC, name LIMIT 200")
    fun search(query: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SeriesEntity?

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY seasonNumber, episodeNumber")
    suspend fun episodesFor(seriesId: String): List<EpisodeEntity>
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

    @Query("DELETE FROM resume WHERE contentId = :contentId")
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
