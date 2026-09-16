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
import com.iptv.player.data.local.dao.RecommendationDao
import com.iptv.player.data.local.dao.WatchSignalEntity

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
        WatchedEntity::class,
        WatchSignalEntity::class
    ],
    version = 15,
    // Schema JSON lives under app/schemas (room.schemaLocation) so migrations
    // can be reviewed and tested against the real previous layout.
    exportSchema = true
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
    abstract fun recommendationDao(): RecommendationDao

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

        /**
         * v12 -> v13: scope VOD resume/watched state to a subscription profile.
         *
         * Room cannot read the active profile stored in DataStore while executing a
         * SQLite migration. Existing rows therefore move losslessly into a one-time
         * legacy scope (-1); the repository atomically claims that scope for the
         * active profile before its first playback-state read/write.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `resume_new` (" +
                        "`profileId` INTEGER NOT NULL, `contentId` TEXT NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `posterUrl` TEXT, `streamUrl` TEXT NOT NULL, " +
                        "`vodId` TEXT, `seriesId` TEXT, `seasonNumber` INTEGER NOT NULL, " +
                        "`episodeNumber` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `contentId`))"
                )
                db.execSQL(
                    "INSERT INTO `resume_new` " +
                        "(`profileId`, `contentId`, `positionMs`, `durationMs`, `updatedAt`, " +
                        "`type`, `title`, `posterUrl`, `streamUrl`, `vodId`, `seriesId`, " +
                        "`seasonNumber`, `episodeNumber`) " +
                        "SELECT -1, `contentId`, `positionMs`, `durationMs`, `updatedAt`, " +
                        "`type`, `title`, `posterUrl`, `streamUrl`, `vodId`, `seriesId`, " +
                        "`seasonNumber`, `episodeNumber` FROM `resume`"
                )
                db.execSQL("DROP TABLE `resume`")
                db.execSQL("ALTER TABLE `resume_new` RENAME TO `resume`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_resume_profileId_updatedAt` " +
                        "ON `resume` (`profileId`, `updatedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_resume_profileId_seriesId` " +
                        "ON `resume` (`profileId`, `seriesId`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watched_new` (" +
                        "`profileId` INTEGER NOT NULL, `contentId` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, `seriesId` TEXT, `watchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `contentId`))"
                )
                db.execSQL(
                    "INSERT INTO `watched_new` " +
                        "(`profileId`, `contentId`, `type`, `seriesId`, `watchedAt`) " +
                        "SELECT -1, `contentId`, `type`, `seriesId`, `watchedAt` FROM `watched`"
                )
                db.execSQL("DROP TABLE `watched`")
                db.execSQL("ALTER TABLE `watched_new` RENAME TO `watched`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watched_profileId_seriesId` " +
                        "ON `watched` (`profileId`, `seriesId`)"
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE series ADD COLUMN latestEpisodeAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN episodeCheckedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS watch_signals (" +
                    "profileId INTEGER NOT NULL, kind TEXT NOT NULL, itemId TEXT NOT NULL, " +
                    "day INTEGER NOT NULL, watchedMs INTEGER NOT NULL, lastWatchedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(profileId, kind, itemId, day))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_signals_profileId_lastWatchedAt " +
                    "ON watch_signals (profileId, lastWatchedAt)")
            }
        }

        /**
         * v14 -> v15: rebuilds the three FTS4 search indexes with the unicode61
         * tokenizer (non-ASCII case folding) and re-fills them from the content
         * tables, so search works immediately and nothing is re-downloaded.
         * The CREATE statements mirror what Room generates for the entities.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for ((fts, source) in listOf("vod_fts" to "vod", "series_fts" to "series", "channels_fts" to "channels")) {
                    db.execSQL("DROP TABLE IF EXISTS `$fts`")
                    db.execSQL(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS `$fts` USING FTS4(" +
                            "`id` TEXT NOT NULL, `name` TEXT NOT NULL, tokenize=unicode61)"
                    )
                    db.execSQL("INSERT INTO `$fts` (`id`, `name`) SELECT `id`, `name` FROM `$source`")
                }
            }
        }

        /** Versions before 8 were dev-only builds without real migrations. */
        private val PRE_MIGRATION_VERSIONS = intArrayOf(1, 2, 3, 4, 5, 6, 7)

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
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                )
                // Only pre-v8 dev builds and downgrades may wipe the cache. A
                // missing forward migration must fail loudly in development
                // instead of silently deleting favorites and history.
                .fallbackToDestructiveMigrationFrom(*PRE_MIGRATION_VERSIONS)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
