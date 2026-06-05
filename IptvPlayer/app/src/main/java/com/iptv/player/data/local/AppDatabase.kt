/*
 * AppDatabase.kt
 * Room database holding live channels, favorites, recents, EPG programs, VOD,
 * series/episodes, profiles, resume positions and EPG id mappings.
 */
package com.iptv.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.iptv.player.data.local.dao.ChannelDao
import com.iptv.player.data.local.dao.ChannelOverrideDao
import com.iptv.player.data.local.dao.EpgDao
import com.iptv.player.data.local.dao.EpgMappingDao
import com.iptv.player.data.local.dao.FavoriteDao
import com.iptv.player.data.local.dao.ProfileDao
import com.iptv.player.data.local.dao.RecentDao
import com.iptv.player.data.local.dao.ResumeDao
import com.iptv.player.data.local.dao.SeriesDao
import com.iptv.player.data.local.dao.VodDao
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

@Database(
    entities = [
        ChannelEntity::class,
        ChannelOverrideEntity::class,
        FavoriteEntity::class,
        RecentEntity::class,
        ProgramEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        ProfileEntity::class,
        ResumeEntity::class,
        EpgMappingEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao
    abstract fun channelOverrideDao(): ChannelOverrideDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentDao(): RecentDao
    abstract fun epgDao(): EpgDao
    abstract fun vodDao(): VodDao
    abstract fun seriesDao(): SeriesDao
    abstract fun profileDao(): ProfileDao
    abstract fun resumeDao(): ResumeDao
    abstract fun epgMappingDao(): EpgMappingDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "iptv.db"
            )
                // Destructive migration is fine during development; replace with
                // real Migration objects before a production release.
                .fallbackToDestructiveMigration()
                .build()
    }
}
