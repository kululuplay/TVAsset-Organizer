package com.iptv.player.data.local.dao

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iptv.player.data.recommendation.WatchPreference
import kotlinx.coroutines.flow.Flow

/** Only IDs and actual watch time; no credentials, URLs, or cloud tracking. */
@Entity(tableName = "watch_signals", primaryKeys = ["profileId", "kind", "itemId", "day"],
    indices = [Index(value = ["profileId", "lastWatchedAt"])])
data class WatchSignalEntity(
    val profileId: Long,
    val kind: String,
    val itemId: String,
    val day: Long,
    val watchedMs: Long,
    val lastWatchedAt: Long,
)

@Dao
interface RecommendationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: WatchSignalEntity)

    @Query("""UPDATE watch_signals SET watchedMs = MIN(7200000, watchedMs + :deltaMs),
        lastWatchedAt = MAX(lastWatchedAt, :nowMs)
        WHERE profileId = :profileId AND kind = :kind AND itemId = :itemId AND day = :day""")
    suspend fun add(profileId: Long, kind: String, itemId: String, day: Long, deltaMs: Long, nowMs: Long)

    @Query("""DELETE FROM watch_signals WHERE profileId = :profileId AND
        (lastWatchedAt < :beforeMs OR rowid NOT IN (SELECT rowid FROM watch_signals
        WHERE profileId = :profileId ORDER BY lastWatchedAt DESC LIMIT 300))""")
    suspend fun prune(profileId: Long, beforeMs: Long)

    @Query("DELETE FROM watch_signals WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: Long)

    @Query("""SELECT w.profileId, w.kind, w.itemId, w.day, w.watchedMs, w.lastWatchedAt,
        CASE WHEN w.kind = 'movie' THEN v.genre ELSE s.genre END AS genre,
        CASE WHEN w.kind = 'movie' THEN v.categoryId ELSE s.categoryId END AS categoryId
        FROM watch_signals w LEFT JOIN vod v ON w.kind = 'movie' AND w.itemId = v.id
        LEFT JOIN series s ON w.kind = 'series' AND w.itemId = s.id
        WHERE w.profileId = :profileId ORDER BY w.lastWatchedAt DESC LIMIT 300""")
    fun observePreferences(profileId: Long): Flow<List<WatchPreference>>
}
