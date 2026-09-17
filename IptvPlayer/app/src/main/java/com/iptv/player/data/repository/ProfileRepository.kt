/*
 * ProfileRepository.kt
 * Profiles and everything scoped to one: resume positions, watched ticks,
 * durable watch-time signals and the recommendation ranking they feed. Also
 * owns the one-time claim of v12's profile-less playback rows.
 */
package com.iptv.player.data.repository

import androidx.room.withTransaction
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.local.dao.WatchSignalEntity
import com.iptv.player.data.local.entity.ProfileEntity
import com.iptv.player.data.local.entity.ResumeEntity
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.Profile
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.ResumeMeta
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.recommendation.RecommendationCandidate
import com.iptv.player.data.recommendation.RecommendationRanker
import com.iptv.player.data.recommendation.WatchPreference
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.KululuEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ProfileRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    override val secureValues: SecureValueCodec,
) : EntityMappers {

    private val channelOverrideDao = db.channelOverrideDao()
    private val favoriteDao = db.favoriteDao()
    private val recentDao = db.recentDao()
    private val vodDao = db.vodDao()
    private val seriesDao = db.seriesDao()
    private val profileDao = db.profileDao()
    private val resumeDao = db.resumeDao()
    private val watchedDao = db.watchedDao()
    private val recommendationDao = db.recommendationDao()
    private val epgMappingDao = db.epgMappingDao()
    private val playbackStateMigrationMutex = Mutex()
    /** Process-local fast path; guarded exclusively by [playbackStateMigrationMutex]. */
    private val claimedLegacyPlaybackProfiles = mutableSetOf<Long>()

    // ---- Profiles -------------------------------------------------------

    fun observeProfiles(): Flow<List<Profile>> =
        profileDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun addProfile(name: String, config: SourceConfig, lockAdult: Boolean): Long =
        withContext(Dispatchers.IO) {
            profileDao.add(
                ProfileEntity(
                    name = name,
                    sourceType = config.type.name,
                    serverUrl = secureValues.encrypt(
                        KululuEndpoint.migrateLegacyServerUrl(config.serverUrl),
                    ),
                    username = secureValues.encrypt(config.username),
                    password = secureValues.encrypt(config.password),
                    m3uUrl = secureValues.encrypt(config.m3uUrl),
                    lockAdult = lockAdult,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

    /**
     * Ensures the given source is also saved as a switchable profile and returns
     * its id. Reuses an existing profile that points at the same source so that
     * repeated logins don't create duplicates; otherwise inserts a new one. This
     * is what makes a freshly logged-in account show up on the Profiles screen.
     */
    suspend fun ensureProfile(config: SourceConfig, lockAdult: Boolean = false): Long =
        withContext(Dispatchers.IO) {
            profileDao.getAll().firstOrNull { it.matches(config) }?.id
                ?: addProfile(defaultProfileName(config), config, lockAdult)
        }

    /**
     * One-time backfill for accounts that connected before profiles were created
     * automatically: if a source is saved but no profile exists yet, mirror it
     * into a profile and make it active so the Profiles screen isn't empty.
     */
    suspend fun backfillProfileFromSource() = withContext(Dispatchers.IO) {
        if (profileDao.getAll().isNotEmpty()) return@withContext
        val config = settings.getSourceConfig() ?: return@withContext
        settings.setActiveProfileId(ensureProfile(config))
    }

    private fun ProfileEntity.matches(config: SourceConfig): Boolean =
        sourceType == config.type.name &&
            KululuEndpoint.migrateLegacyServerUrl(secureValues.decrypt(serverUrl)) ==
                KululuEndpoint.migrateLegacyServerUrl(config.serverUrl) &&
            secureValues.decrypt(username) == config.username &&
            secureValues.decrypt(password) == config.password &&
            secureValues.decrypt(m3uUrl) == config.m3uUrl

    /** A friendly default profile name: the Xtream username, else the source host. */
    private fun defaultProfileName(config: SourceConfig): String = when (config.type) {
        SourceType.XTREAM -> config.username.ifBlank { hostOf(config.serverUrl) }
        SourceType.M3U_URL -> hostOf(config.m3uUrl)
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Playlist"

    suspend fun removeProfile(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            resumeDao.clearProfile(id)
            watchedDao.clearProfile(id)
            recommendationDao.clearProfile(id)
            profileDao.remove(id)
            // Favorites, recents, overrides and EPG mappings carry no profile
            // column and their ids collide across providers, so they can only
            // be attributed (and dropped) once no profile remains at all.
            if (profileDao.getAll().isEmpty()) {
                favoriteDao.clearAll()
                recentDao.clearAll()
                channelOverrideDao.clearAll()
                epgMappingDao.clearAll()
            }
        }
    }

    suspend fun getProfile(id: Long): Profile? = withContext(Dispatchers.IO) {
        profileDao.getById(id)?.toModel()
    }

    // ---- Resume positions ----------------------------------------------

    /**
     * v12 could not associate its global playback rows with a DataStore profile
     * during Room migration. Claim that one-time legacy scope for the currently
     * active profile before the first state access, without exposing it to a later
     * profile switch. Existing data is copied before deletion in one transaction.
     */
    private suspend fun claimLegacyPlaybackState(profileId: Long) {
        if (profileId < 0L) return
        playbackStateMigrationMutex.withLock {
            if (profileId in claimedLegacyPlaybackProfiles) return@withLock
            if (settings.getActiveProfileId() != profileId) return@withLock
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                sql.execSQL(
                    "INSERT OR REPLACE INTO resume " +
                        "(profileId, contentId, positionMs, durationMs, updatedAt, type, title, " +
                        "posterUrl, streamUrl, vodId, seriesId, seasonNumber, episodeNumber) " +
                        "SELECT ?, contentId, positionMs, durationMs, updatedAt, type, title, " +
                        "posterUrl, streamUrl, vodId, seriesId, seasonNumber, episodeNumber " +
                        "FROM resume WHERE profileId = -1",
                    arrayOf(profileId),
                )
                sql.execSQL(
                    "INSERT OR REPLACE INTO watched " +
                        "(profileId, contentId, type, seriesId, watchedAt) " +
                        "SELECT ?, contentId, type, seriesId, watchedAt " +
                        "FROM watched WHERE profileId = -1",
                    arrayOf(profileId),
                )
                sql.execSQL("DELETE FROM resume WHERE profileId = -1")
                sql.execSQL("DELETE FROM watched WHERE profileId = -1")
            }
            claimedLegacyPlaybackProfiles += profileId
        }
    }

    private suspend fun activePlaybackProfileId(): Long {
        val profileId = settings.getActiveProfileId()
        claimLegacyPlaybackState(profileId)
        return profileId
    }

    suspend fun saveResume(meta: ResumeMeta, positionMs: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            val profileId = activePlaybackProfileId()
            saveResumeRow(profileId, meta, positionMs, durationMs)
        }

    /**
     * Persist against the profile that opened the player. Lifecycle-final writes
     * may outlive the Activity and race a profile switch, so resolving DataStore
     * again inside that coroutine would leak the old title into the new profile.
     */
    suspend fun saveResumeForProfile(
        profileId: Long,
        meta: ResumeMeta,
        positionMs: Long,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        claimLegacyPlaybackState(profileId)
        saveResumeRow(profileId, meta, positionMs, durationMs)
    }

    private suspend fun saveResumeRow(
        profileId: Long,
        meta: ResumeMeta,
        positionMs: Long,
        durationMs: Long,
    ) {
        // Don't persist trivial or near-complete positions: clearing them keeps
        // the Continue Watching rail to genuinely in-progress content.
        if (positionMs < 10_000 || (durationMs > 0 && positionMs > durationMs - 30_000)) {
            resumeDao.clear(profileId, meta.contentId)
            // A near-complete position means the title was finished: record it
            // as watched so the tick survives the resume row being cleared.
            if (durationMs > 0 && positionMs > durationMs - 30_000) {
                markWatchedRow(profileId, meta.contentId, meta.kind.raw, meta.seriesId)
            }
        } else {
            resumeDao.save(
                ResumeEntity(
                    profileId = profileId,
                    contentId = meta.contentId,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                    type = meta.kind.raw,
                    title = meta.title,
                    posterUrl = meta.posterUrl,
                    streamUrl = secureValues.encrypt(meta.streamUrl),
                    vodId = meta.vodId,
                    seriesId = meta.seriesId,
                    seasonNumber = meta.seasonNumber,
                    episodeNumber = meta.episodeNumber,
                )
            )
        }
    }

    suspend fun getResume(contentId: String): Long = withContext(Dispatchers.IO) {
        val profileId = activePlaybackProfileId()
        resumeDao.get(profileId, contentId)?.positionMs ?: 0L
    }

    suspend fun getResumeForProfile(profileId: Long, contentId: String): Long =
        withContext(Dispatchers.IO) {
            claimLegacyPlaybackState(profileId)
            resumeDao.get(profileId, contentId)?.positionMs ?: 0L
        }

    suspend fun clearResume(contentId: String) = withContext(Dispatchers.IO) {
        val profileId = activePlaybackProfileId()
        resumeDao.clear(profileId, contentId)
    }

    suspend fun clearResumeForProfile(profileId: Long, contentId: String) =
        withContext(Dispatchers.IO) {
            claimLegacyPlaybackState(profileId)
            resumeDao.clear(profileId, contentId)
        }

    /**
     * Commit EndReached state for the profile that opened the player. Resume
     * removal and the watched badge are one Room transaction, so process death or
     * a concurrent observer can never expose a half-completed title.
     */
    suspend fun completePlaybackForProfile(
        profileId: Long,
        contentId: String,
        type: String,
        seriesId: String? = null,
    ) = withContext(Dispatchers.IO) {
        claimLegacyPlaybackState(profileId)
        db.withTransaction {
            resumeDao.clear(profileId, contentId)
            markWatchedRow(profileId, contentId, type, seriesId)
        }
    }

    /** Reactive Continue Watching rail: most recent in-progress items first. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeContinueWatching(limit: Int = 20): Flow<List<ContinueItem>> =
        settings.activeProfileId
            .distinctUntilChanged()
            .flatMapLatest { profileId ->
                flow {
                    claimLegacyPlaybackState(profileId)
                    emitAll(resumeDao.observeRecent(profileId, limit))
                }
            }
            .map { rows -> rows.map { it.toContinueItem() } }

    /** Saved positions (ms) keyed by raw episode id for one series. */
    suspend fun episodeProgress(seriesId: String): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val profileId = activePlaybackProfileId()
            resumeDao.forSeries(profileId, seriesId)
                .associate { it.contentId.removePrefix("ep_") to it.positionMs }
        }

    /** Last-watched episode of a series for a one-tap continue action. */
    suspend fun latestSeriesResume(seriesId: String): ContinueItem? =
        withContext(Dispatchers.IO) {
            val profileId = activePlaybackProfileId()
            resumeDao.latestForSeries(profileId, seriesId)?.toContinueItem()
        }

    /** Watch-progress percent (0..100) keyed by resume contentId, for grid bars. */
    suspend fun allWatchProgress(): Map<String, Int> = withContext(Dispatchers.IO) {
        val profileId = activePlaybackProfileId()
        resumeDao.all(profileId).associate { row ->
            val pct = if (row.durationMs > 0) {
                ((row.positionMs * 100) / row.durationMs).toInt().coerceIn(0, 100)
            } else 0
            row.contentId to pct
        }
    }

    /**
     * In-progress percent (0..100) keyed by *series* id, for the series grid bars.
     * A series has no resume row of its own — progress is tracked per episode — so
     * we surface the most recently watched episode's progress as the series's
     * "continue" hint. (There is no series-level *watched* tick: the grid can't
     * cheaply know whether every episode is finished.)
     */
    suspend fun seriesWatchProgress(): Map<String, Int> = withContext(Dispatchers.IO) {
        val profileId = activePlaybackProfileId()
        resumeDao.all(profileId)
            .filter { it.seriesId != null && it.durationMs > 0 }
            .groupBy { it.seriesId!! }
            .mapValues { (_, rows) ->
                val latest = rows.maxByOrNull { it.updatedAt } ?: return@mapValues 0
                ((latest.positionMs * 100) / latest.durationMs).toInt().coerceIn(0, 100)
            }
    }

    // ---- Watched (finished) state --------------------------------------

    suspend fun markWatched(contentId: String, type: String, seriesId: String? = null) =
        withContext(Dispatchers.IO) {
            val profileId = activePlaybackProfileId()
            markWatchedRow(profileId, contentId, type, seriesId)
        }

    suspend fun markWatchedForProfile(
        profileId: Long,
        contentId: String,
        type: String,
        seriesId: String? = null,
    ) = withContext(Dispatchers.IO) {
        claimLegacyPlaybackState(profileId)
        markWatchedRow(profileId, contentId, type, seriesId)
    }

    private suspend fun markWatchedRow(
        profileId: Long,
        contentId: String,
        type: String,
        seriesId: String?,
    ) {
        watchedDao.mark(
            com.iptv.player.data.local.entity.WatchedEntity(
                profileId = profileId,
                contentId = contentId,
                type = type,
                seriesId = seriesId,
                watchedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun isWatched(contentId: String): Boolean =
        withContext(Dispatchers.IO) {
            val profileId = activePlaybackProfileId()
            watchedDao.isWatched(profileId, contentId)
        }

    /** All watched content ids, for badging the movie/series grids. */
    suspend fun watchedIds(): Set<String> =
        withContext(Dispatchers.IO) {
            val profileId = activePlaybackProfileId()
            watchedDao.allIds(profileId).toSet()
        }

    /** Watched episode ids for one series (raw episode ids, "ep_" stripped). */
    suspend fun watchedEpisodeIds(seriesId: String): Set<String> =
        withContext(Dispatchers.IO) {
            val profileId = activePlaybackProfileId()
            watchedDao.idsForSeries(profileId, seriesId)
                .map { it.removePrefix("ep_") }
                .toSet()
        }

    /** Durable actual-playback deltas, isolated to the profile that opened the player. */
    suspend fun recordWatchTime(profileId: Long, meta: ResumeMeta, deltaMs: Long, nowMs: Long) =
        withContext(Dispatchers.IO) {
            if (profileId < 0 || deltaMs <= 0) return@withContext
            val kind = when (meta.kind) {
                ResumeKind.MOVIE -> "movie"
                ResumeKind.EPISODE -> "series"
                else -> return@withContext
            }
            val id = (if (kind == "movie") meta.vodId else meta.seriesId)
                ?.takeIf { it.isNotBlank() } ?: return@withContext
            val day = nowMs / RecommendationRanker.DAY_MS
            db.withTransaction {
                // A late lifecycle write must not recreate a deleted profile's history.
                if (profileDao.getById(profileId) == null) return@withTransaction
                recommendationDao.insert(WatchSignalEntity(profileId, kind, id, day, 0, nowMs))
                recommendationDao.add(profileId, kind, id, day, deltaMs.coerceAtMost(60_000), nowMs)
                recommendationDao.prune(profileId, nowMs - 90 * RecommendationRanker.DAY_MS)
            }
        }

    suspend fun recommendationIds(
        profileId: Long, kind: String, history: List<WatchPreference>, hidden: List<String>,
    ): List<String> {
        val excluded = if (kind == "movie") watchedDao.allIds(profileId)
            .filter { it.startsWith("vod_") }.map { it.removePrefix("vod_") }.toSet() else emptySet()
        val ranker = RecommendationRanker(profileId, kind, history, System.currentTimeMillis(), excluded)
        var afterId = ""
        do {
            val chunk: List<RecommendationCandidate> = if (kind == "movie")
                vodDao.recommendationCandidates(afterId, hidden) else seriesDao.recommendationCandidates(afterId, hidden)
            chunk.forEach(ranker::offer)
            if (chunk.isEmpty()) break
            afterId = chunk.last().id
        } while (chunk.size == 256)
        return ranker.results(50)
    }
}
