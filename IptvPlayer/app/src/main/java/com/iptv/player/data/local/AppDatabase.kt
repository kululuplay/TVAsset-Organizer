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
import com.iptv.player.data.local.dao.SeriesDao
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
import com.iptv.player.data.local.entity.SeriesEntity
import com.iptv.player.data.local.entity.SeriesFtsEntity
import com.iptv.player.data.local.entity.VodCategoryEntity
import com.iptv.player.data.local.entity.VodEntity
import com.iptv.player.data.local.entity.VodFtsEntity
import com.iptv.player.data.local.entity.WatchedEntity
import com.iptv.player.data.local.dao.WatchedDao

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
        ChannelFtsEntity::class,
        WatchedEntity::class
    ],
    version = 12,
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
    abstract fun watchedDao(): WatchedDao

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

        /**
         * v9 -> v10: adds the `watched` table that records finished movies/episodes
         * so a "watched" tick survives the resume row being cleared on completion.
         * Purely additive — existing cached content and resume positions are kept.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watched` " +
                        "(`contentId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`seriesId` TEXT, `watchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`contentId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_watched_seriesId` ON `watched` (`seriesId`)")
            }
        }

        /**
         * v10 -> v11: adds the `isRadio` flag to channels so radio stations can be
         * split out of the Live TV page into their own Dashboard section. Additive
         * and non-destructive — the column is back-filled from the category name
         * (categories containing "radio"/"radyo") so existing caches classify
         * immediately, and the next refresh recomputes it at ingest.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `isRadio` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE `channels` SET `isRadio` = 1 WHERE " +
                        "LOWER(categoryName) LIKE '%radio%' OR LOWER(categoryName) LIKE '%radyo%'"
                )
            }
        }

        /**
         * v11 -> v12: stores landscape artwork separately from poster artwork.
         * Existing rows keep a null backdrop and the detail screen falls back to
         * the poster until source/TMDB enrichment fills the new field.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `vod` ADD COLUMN `backdropUrl` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `backdropUrl` TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "iptv.db"
            )
                .addMigrations(
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                )
                // Safety net for upgrades from versions older than 8 (dev-only
                // builds that predate real migrations); v8 -> v9 is non-destructive.
                .fallbackToDestructiveMigration()
                .build()
    }
}
