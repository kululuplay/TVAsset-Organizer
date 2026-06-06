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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.iptv.player.data.local.dao.ChannelDao
import com.iptv.player.data.local.dao.ChannelFtsDao
import com.iptv.player.data.local.dao.ChannelOverrideDao
import com.iptv.player.data.local.dao.EpgDao
import com.iptv.player.data.local.dao.EpgMappingDao
import com.iptv.player.data.local.dao.FavoriteDao
import com.iptv.player.data.local.dao.ProfileDao
import com.iptv.player.data.local.dao.RecentDao
import com.iptv.player.data.local.dao.ResumeDao
import com.iptv.player.data.local.dao.SeriesCategoryDao
import com.iptv.player.data.local.dao.SeriesFtsDao
import com.iptv.player.data.local.dao.VodCategoryDao
import com.iptv.player.data.local.dao.VodDao
import com.iptv.player.data.local.dao.VodFtsDao
import com.iptv.player.data.local.entity.ChannelEntity
import com.iptv.player.data.local.entity.ChannelFtsEntity
import com.iptv.player.data.local.entity.ChannelOverrideEntity
import com.iptv.player.data.local.entity.EpgMappingEntity
import com.iptv.player.data.local.entity.EpisodeEntity
import com.iptv.player.data.local.entity.FavoriteEntity
import com.iptv.player.data.local.entity.ProfileEntity
import com.iptv.player.data.local.entity.ProgramEntity
import com.iptv.player.data.local.entity.RecentEntity
import com.iptv.player.data.local.entity.ResumeEntity
import com.iptv.player.data.local.entity.SeriesCategoryEntity
import com.iptv.player.data.local.entity.SeriesFtsEntity
import com.iptv.player.data.local.entity.VodCategoryEntity
import com.iptv.player.data.local.entity.VodEntity
import com.iptv.player.data.local.entity.VodFtsEntity

@Database(
    entities = [
        ChannelEntity::class,
        ChannelOverrideEntity::class,
        FavoriteEntity::class,
        RecentEntity::class,
        ProgramEntity::class,
        VodEntity::class,
        VodCategoryEntity::class,
        SeriesEntity::class,
        SeriesCategoryEntity::class,
        EpisodeEntity::class,
        ProfileEntity::class,
        ResumeEntity::class,
        EpgMappingEntity::class,
        VodFtsEntity::class,
        SeriesFtsEntity::class,
        ChannelFtsEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao
    abstract fun channelOverrideDao(): ChannelOverrideDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentDao(): RecentDao
    abstract fun epgDao(): EpgDao
    abstract fun vodDao(): VodDao
    abstract fun vodCategoryDao(): VodCategoryDao
    abstract fun seriesDao(): SeriesDao
    abstract fun seriesCategoryDao(): SeriesCategoryDao
    abstract fun profileDao(): ProfileDao
    abstract fun resumeDao(): ResumeDao
    abstract fun epgMappingDao(): EpgMappingDao
    abstract fun vodFtsDao(): VodFtsDao
    abstract fun seriesFtsDao(): SeriesFtsDao
    abstract fun channelFtsDao(): ChannelFtsDao

    companion object {

        /**
         * v8 -> v9: adds FTS4 search indexes (movies/series/channels) and a
         * standalone series_categories table for lazy series loading. Purely
         * additive and non-destructive — existing cached content is preserved and
         * the new indexes are back-filled from it so search works immediately
         * after the upgrade, without forcing a full re-sync.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // FTS indexes.
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `vod_fts` USING FTS4(`id`, `name`)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `series_fts` USING FTS4(`id`, `name`)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `channels_fts` USING FTS4(`id`, `name`)")
                db.execSQL("INSERT INTO `vod_fts` (`id`, `name`) SELECT `id`, `name` FROM `vod`")
                db.execSQL("INSERT INTO `series_fts` (`id`, `name`) SELECT `id`, `name` FROM `series`")
                db.execSQL("INSERT INTO `channels_fts` (`id`, `name`) SELECT `id`, `name` FROM `channels`")

                // Series categories (mirror of vod_categories). Back-fill from the
                // already-cached series and mark them loaded so nothing re-downloads.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_categories` " +
                        "(`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `loaded` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `series_categories` " +
                        "(`id`, `name`, `position`, `loaded`) " +
                        "SELECT categoryId, COALESCE(MIN(categoryName), 'Uncategorized'), " +
                        "MIN(categoryPosition), 1 FROM `series` " +
                        "WHERE categoryId IS NOT NULL GROUP BY categoryId"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "iptv.db"
            )
                .addMigrations(MIGRATION_8_9)
                // Safety net for upgrades from versions older than 8 (dev-only
                // builds that predate real migrations); v8 -> v9 is non-destructive.
                .fallbackToDestructiveMigration()
                .build()
    }
}
