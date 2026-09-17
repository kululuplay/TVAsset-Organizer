/*
 * EpgRepository.kt
 * XMLTV guide: streamed download into a raw staging table, one atomic swap into
 * Room, plus now/next + window lookups with the tvg-id / name / override
 * resolution chain.
 */
package com.iptv.player.data.repository

import android.util.Base64
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.local.entity.EpgMappingEntity
import com.iptv.player.data.local.entity.ProgramEntity
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.NowNext
import com.iptv.player.data.model.Program
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.parser.XmltvParser
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.remote.XtreamUrlBuilder
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.AppError
import com.iptv.player.util.HttpAppErrorPolicy
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/** Lower-cased, trimmed epg id for case/space-insensitive matching. */
internal fun normalizeEpgId(raw: String?): String? =
    raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }

/** Normalized channel display name (trim + lowercase + collapse whitespace). */
internal fun normalizeName(raw: String?): String =
    raw?.trim()?.lowercase(Locale.US)?.replace(Regex("\\s+"), " ").orEmpty()

internal class EpgRepository(
    private val db: AppDatabase,
    private val httpClient: OkHttpClient,
    private val settings: SettingsStore,
    override val secureValues: SecureValueCodec,
    private val support: CatalogSyncSupport,
) : EntityMappers {

    private val epgDao = db.epgDao()
    private val epgMappingDao = db.epgMappingDao()
    private val epgSyncMutex = Mutex()

    // ---- EPG ------------------------------------------------------------

    /**
     * Display-name → normalized epg id map, built from the XMLTV <channel>
     * entries on each [refreshEpg]. Used as a fallback when a channel's tvg-id
     * is missing or doesn't match any program id. In-memory only (rebuilt on
     * every guide refresh) to avoid a destructive Room schema migration.
     */
    @Volatile
    private var epgNameIndex: Map<String, String> = emptyMap()

    /** Downloads and caches the full XMLTV guide (Xtream xmltv.php). */
    suspend fun refreshEpg(config: SourceConfig): Outcome<Int> = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext Outcome.Success(0)
        epgSyncMutex.withLock {
            val generation = support.refreshGenerations.begin("epg", config)
            try {
                val url = XtreamUrlBuilder.xmltvUrl(config.serverUrl, config.username, config.password)
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withLock Outcome.Failure(
                            HttpAppErrorPolicy.fromStatus(resp.code),
                            httpStatus = resp.code.takeIf { it in 400..599 },
                        )
                    }
                    val body = resp.body ?: return@withLock Outcome.Failure(AppError.CANNOT_CONNECT)
                    prepareEpgStaging()
                    val batch = ArrayList<ProgramEntity>(500)
                    var total = 0
                    val nameIndex = HashMap<String, String>()
                    // The parser callback is synchronous; keep a handle on the job
                    // so a cancelled sync stops between batches instead of
                    // grinding through the whole guide.
                    val job = currentCoroutineContext()[Job]
                    body.byteStream().use { stream ->
                        XmltvParser.parse(
                            stream,
                            onChannel = { id, displayName ->
                                val nId = normalizeEpgId(id)
                                val nName = normalizeName(displayName)
                                // First display-name wins; don't let aliases overwrite it.
                                if (nId != null && nName.isNotEmpty() && !nameIndex.containsKey(nName)) {
                                    nameIndex[nName] = nId
                                }
                            },
                        ) { p ->
                            batch += ProgramEntity(
                                epgChannelId = normalizeEpgId(p.epgChannelId)
                                    ?: p.epgChannelId.trim(),
                                title = p.title,
                                description = p.description,
                                startMs = p.startMs,
                                stopMs = p.stopMs,
                            )
                            if (batch.size >= 500) {
                                // Stage each bounded batch on this IO thread with a plain
                                // SQLite transaction (no runBlocking) without touching live EPG.
                                job?.ensureActive()
                                insertEpgStaging(batch)
                                total += batch.size
                                batch.clear()
                            }
                        }
                    }
                    if (batch.isNotEmpty()) {
                        insertEpgStaging(batch)
                        total += batch.size
                    }

                    val existingCount = epgDao.count()
                    when (
                        val decision = support.evaluateRefresh(
                            CatalogDataset.EPG,
                            generation,
                            DatasetSnapshot(
                                generation.policyExistingCount(existingCount),
                                total,
                                total,
                            ),
                        )
                    ) {
                        DatasetRefreshDecision.Apply -> Unit
                        is DatasetRefreshDecision.PreserveCache ->
                            return@withLock preservedDataset(CatalogDataset.EPG, decision.reason)
                    }
                    // The only destructive step is this short atomic swap. A malformed,
                    // interrupted or suspicious download leaves the previous guide intact.
                    val committed = support.commitSnapshot(config, generation) {
                        val sql = db.openHelper.writableDatabase
                        sql.execSQL("DELETE FROM programs")
                        sql.execSQL(
                            "INSERT INTO programs " +
                                "(epgChannelId, title, description, startMs, stopMs) " +
                                "SELECT epgChannelId, title, description, startMs, stopMs " +
                                "FROM $EPG_STAGING_TABLE",
                        )
                        sql.execSQL("DROP TABLE IF EXISTS $EPG_STAGING_TABLE")
                    }
                    if (!committed) return@withLock staleDataset(CatalogDataset.EPG)
                    epgNameIndex = nameIndex
                    settings.setEpgUpdatedAt(System.currentTimeMillis())
                    Outcome.Success(total)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                e.toOutcomeFailure()
            } finally {
                dropEpgStaging()
            }
        }
    }

    suspend fun getNowNext(channel: Channel): NowNext = withContext(Dispatchers.IO) {
        val epgId = resolveEpgId(channel) ?: return@withContext NowNext(null, null)
        val now = System.currentTimeMillis()
        val upcoming = epgDao.upcoming(epgId, now, limit = 2).map { it.toModel() }
        val current = upcoming.firstOrNull { it.isLiveAt(now) }
        val next = upcoming.firstOrNull { it.startMs > now }
        NowNext(current, next)
    }

    suspend fun getProgramsWindow(channel: Channel, fromMs: Long, toMs: Long): List<Program> =
        withContext(Dispatchers.IO) {
            val epgId = resolveEpgId(channel) ?: return@withContext emptyList()
            epgDao.inWindow(epgId, fromMs, toMs).map { it.toModel() }
        }

    suspend fun setEpgMapping(channelId: String, epgChannelId: String) =
        withContext(Dispatchers.IO) {
            epgMappingDao.set(EpgMappingEntity(channelId, epgChannelId))
        }

    private suspend fun resolveEpgId(channel: Channel): String? {
        // 1) Manual user override (EPG correction screen) always wins.
        val override = normalizeEpgId(epgMappingDao.get(channel.id))
        if (override != null) return override
        // 2) The channel's own tvg-id / epg_channel_id, matched case/space-insensitively.
        val direct = normalizeEpgId(channel.epgChannelId)
        if (direct != null && epgDao.countFor(direct) > 0) return direct
        // 3) Fallback: match the channel's display name against the XMLTV <channel>
        //    display-names — covers a missing or mismatched tvg-id.
        val byName = epgNameIndex[normalizeName(channel.name)]
        if (byName != null && epgDao.countFor(byName) > 0) return byName
        // 4) Last resort: the normalized direct id even if no programs are present
        //    yet (guide may still be downloading); null when nothing to match on.
        return direct
    }

    /** Decodes Xtream's base64 EPG fields (used by short-EPG callers). */
    fun decodeEpgText(b64: String?): String =
        runCatching { String(Base64.decode(b64 ?: "", Base64.DEFAULT)) }.getOrDefault("")

    private fun prepareEpgStaging() {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP TABLE IF EXISTS $EPG_STAGING_TABLE")
        sql.execSQL(
            "CREATE TABLE $EPG_STAGING_TABLE (" +
                "epgChannelId TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "description TEXT, " +
                "startMs INTEGER NOT NULL, " +
                "stopMs INTEGER NOT NULL)",
        )
    }

    /**
     * Synchronous on purpose: it runs inside the XMLTV parser callback. The
     * staging table is not a Room entity, so a raw SQLite transaction is enough
     * and no invalidation tracking is needed.
     */
    private fun insertEpgStaging(batch: List<ProgramEntity>) {
        if (batch.isEmpty()) return
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            val statement = sql.compileStatement(
                "INSERT INTO $EPG_STAGING_TABLE " +
                    "(epgChannelId, title, description, startMs, stopMs) VALUES (?, ?, ?, ?, ?)",
            )
            try {
                batch.forEach { program ->
                    statement.clearBindings()
                    statement.bindString(1, program.epgChannelId)
                    statement.bindString(2, program.title)
                    program.description?.let { statement.bindString(3, it) }
                        ?: statement.bindNull(3)
                    statement.bindLong(4, program.startMs)
                    statement.bindLong(5, program.stopMs)
                    statement.executeInsert()
                }
            } finally {
                statement.close()
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    private fun dropEpgStaging() {
        runCatching {
            db.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS $EPG_STAGING_TABLE")
        }
    }

    private companion object {
        const val EPG_STAGING_TABLE = "epg_sync_staging"
    }
}
