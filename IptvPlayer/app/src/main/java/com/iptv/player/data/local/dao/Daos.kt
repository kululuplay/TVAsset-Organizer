/*
 * Daos.kt
 * Room DAOs. Reads return Flow so the UI updates reactively; writes are
 * suspend functions executed on Room's IO dispatcher.
 */
package com.iptv.player.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iptv.player.data.local.entity.ChannelEntity
import com.iptv.player.data.local.entity.ChannelOverrideEntity
import com.iptv.player.data.local.entity.FavoriteEntity
import com.iptv.player.data.local.entity.RecentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE type = :type")
    suspend fun clearType(type: String)

    // Visible list: hides channels flagged hidden and applies any custom order.
    // Without a manual override, channels keep the server's delivery order.
    @Query("""
        SELECT c.* FROM channels c
        LEFT JOIN channel_overrides o ON o.channelId = c.id
        WHERE c.type = :type AND COALESCE(o.hidden, 0) = 0
        ORDER BY COALESCE(o.sortOrder, 2147483647), c.position
    """)
    fun observeByType(type: String): Flow<List<ChannelEntity>>

    @Query("""
        SELECT c.* FROM channels c
        LEFT JOIN channel_overrides o ON o.channelId = c.id
        WHERE c.type = :type AND c.categoryId = :categoryId AND COALESCE(o.hidden, 0) = 0
        ORDER BY COALESCE(o.sortOrder, 2147483647), c.position
    """)
    fun observeByCategory(type: String, categoryId: String): Flow<List<ChannelEntity>>

    // Manager list: ALL channels (incl. hidden) with their override state.
    @Query("""
        SELECT c.*, COALESCE(o.hidden, 0) AS hidden, o.sortOrder AS sortOrder
        FROM channels c
        LEFT JOIN channel_overrides o ON o.channelId = c.id
        WHERE c.type = :type
        ORDER BY COALESCE(o.sortOrder, 2147483647), c.position
    """)
    fun observeForManagement(type: String): Flow<List<ManagedChannelRow>>

    // Categories in the server's original order (first appearance in the list).
    @Query("""
        SELECT categoryId, categoryName FROM channels
        WHERE type = :type AND categoryId IS NOT NULL
        GROUP BY categoryId, categoryName
        ORDER BY MIN(categoryPosition)
    """)
    fun observeCategories(type: String): Flow<List<CategoryRow>>

    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' AND type = :type ORDER BY name LIMIT 200")
    fun search(query: String, type: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channels WHERE type = :type")
    suspend fun countByType(type: String): Int
}

/** Projection row for the distinct category query. */
data class CategoryRow(
    val categoryId: String,
    val categoryName: String?
)

/** Projection joining a channel with its (optional) per-user override state. */
data class ManagedChannelRow(
    @Embedded val channel: ChannelEntity,
    val hidden: Boolean,
    val sortOrder: Int?
)

@Dao
interface ChannelOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: ChannelOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(overrides: List<ChannelOverrideEntity>)

    @Query("SELECT * FROM channel_overrides WHERE channelId = :channelId LIMIT 1")
    suspend fun get(channelId: String): ChannelOverrideEntity?

    @Query("SELECT * FROM channel_overrides")
    suspend fun getAll(): List<ChannelOverrideEntity>
}

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    suspend fun remove(channelId: String)

    @Query("SELECT channelId FROM favorites")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    suspend fun isFavorite(channelId: String): Boolean

    @Query("""
        SELECT c.* FROM channels c
        INNER JOIN favorites f ON f.channelId = c.id
        ORDER BY f.addedAt DESC
    """)
    fun observeFavoriteChannels(): Flow<List<ChannelEntity>>
}

@Dao
interface RecentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(recent: RecentEntity)

    @Query("DELETE FROM recent WHERE channelId NOT IN (SELECT channelId FROM recent ORDER BY watchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("""
        SELECT c.* FROM channels c
        INNER JOIN recent r ON r.channelId = c.id
        ORDER BY r.watchedAt DESC LIMIT :limit
    """)
    fun observeRecentChannels(limit: Int): Flow<List<ChannelEntity>>
}
