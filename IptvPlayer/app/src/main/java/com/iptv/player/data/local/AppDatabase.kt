/*
 * AppDatabase.kt
 * Room database. EPG / VOD / Series tables will be added here as new entities
 * with bumped version + migrations when those features land.
 */
package com.iptv.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.iptv.player.data.local.dao.ChannelDao
import com.iptv.player.data.local.dao.FavoriteDao
import com.iptv.player.data.local.dao.RecentDao
import com.iptv.player.data.local.entity.ChannelEntity
import com.iptv.player.data.local.entity.FavoriteEntity
import com.iptv.player.data.local.entity.RecentEntity

@Database(
    entities = [
        ChannelEntity::class,
        FavoriteEntity::class,
        RecentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentDao(): RecentDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "iptv.db"
            )
                // For early development; replace with real migrations before release.
                .fallbackToDestructiveMigration()
                .build()
    }
}
