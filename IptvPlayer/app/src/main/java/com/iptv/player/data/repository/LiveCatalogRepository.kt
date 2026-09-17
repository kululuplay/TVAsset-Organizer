/*
 * LiveCatalogRepository.kt
 * Live channels + categories: reactive reads from Room and the full-snapshot
 * refresh from Xtream or M3U (shrink guard, new-channel counter, FTS index).
 */
package com.iptv.player.data.repository

import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.local.entity.ChannelFtsEntity
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.parser.M3uParser
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.remote.XtreamUrlBuilder
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.AppError
import com.iptv.player.util.NewContentNotifier
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal class LiveCatalogRepository(
    db: AppDatabase,
    private val httpClient: OkHttpClient,
    private val settings: SettingsStore,
    override val secureValues: SecureValueCodec,
    private val support: CatalogSyncSupport,
) : EntityMappers {

    private val channelDao = db.channelDao()
    private val channelFtsDao = db.channelFtsDao()

    // ---- Reactive reads (UI layer) --------------------------------------

    fun observeCategories(type: ContentType, radio: Int = -1): Flow<List<Category>> =
        channelDao.observeCategories(type.name, radio).map { rows ->
            rows.map { Category(it.categoryId, it.categoryName ?: "Uncategorized", type) }
        }

    fun observeChannels(type: ContentType, radio: Boolean = false): Flow<List<Channel>> =
        channelDao.observeByType(type.name, if (radio) 1 else 0).map { list -> list.map { it.toModel() } }

    fun observeChannelsByCategory(type: ContentType, categoryId: String, radio: Boolean = false): Flow<List<Channel>> =
        channelDao.observeByCategory(type.name, categoryId, if (radio) 1 else 0).map { list -> list.map { it.toModel() } }

    /** Channel counts per category id, used for the category row badges. */
    fun observeCategoryCounts(type: ContentType, radio: Int = -1): Flow<Map<String, Int>> =
        channelDao.observeCategoryCounts(type.name, radio).map { rows ->
            rows.associate { it.categoryId to it.count }
        }

    suspend fun getChannel(id: String): Channel? = channelDao.getById(id)?.toModel()

    suspend fun liveChannelCount(): Int = withContext(Dispatchers.IO) {
        channelDao.countByType(ContentType.LIVE.name)
    }

    /**
     * Live channels that advertise a catch-up / timeshift archive. Hidden
     * categories are excluded here so the catch-up screen matches Live TV.
     */
    fun observeCatchupChannels(): Flow<List<Channel>> =
        combine(
            channelDao.observeByType(ContentType.LIVE.name, 0),
            settings.hiddenCategories(ContentType.LIVE),
        ) { list, hidden ->
            list.filter { it.catchupDays > 0 && (it.categoryId == null || it.categoryId !in hidden) }
                .map { it.toModel() }
        }

    // ---- Source validation / live ---------------------------------------

    /**
     * Replaces the live channel snapshot. [force] (a manual user refresh) trusts
     * a much smaller server list that the shrink guard would otherwise reject.
     */
    suspend fun refreshLive(config: SourceConfig, force: Boolean = false): Outcome<Int> = withContext(Dispatchers.IO) {
        val generation = support.refreshGenerations.begin("live", config)
        try {
            val staged = when (config.type) {
                SourceType.XTREAM -> loadXtreamLive(config)
                SourceType.M3U_URL -> loadM3u(config)
            }
            val channels = staged.items
            if (channels.isEmpty()) return@withContext Outcome.Failure(AppError.EMPTY_PLAYLIST)
            // Stamp each channel with its index in the source list so the visible
            // order matches what the server delivered.
            val ordered = channels.mapIndexed { index, ch -> ch.copy(position = index) }
            // Count genuinely-new live channels (Xtream ids and M3U tvg-id/URL-hash
            // ids are refresh-stable) so the Live screen can flash a "N new
            // channels" notice. Captured before the wipe below; skipped on the very
            // first load (no prior data) and when nothing overlaps at all (an id
            // scheme or source change, not new content) to avoid a false popup.
            val existing = channelDao.idsForType(ContentType.LIVE.name).toSet()
            val newLiveCount = if (existing.isEmpty()) 0 else {
                ordered.count { it.id !in existing }.let { if (it == ordered.size) 0 else it }
            }
            when (
                val decision = support.evaluateRefresh(
                    CatalogDataset.LIVE,
                    generation,
                    DatasetSnapshot(
                        generation.policyExistingCount(existing.size),
                        staged.receivedCount,
                        ordered.size,
                        overlapCount = ordered.count { it.id in existing },
                    ),
                    force = force,
                )
            ) {
                DatasetRefreshDecision.Apply -> Unit
                is DatasetRefreshDecision.PreserveCache ->
                    return@withContext preservedDataset(CatalogDataset.LIVE, decision.reason)
            }
            // Replace channels + their search index atomically so live search never
            // sees a half-built index (or an empty one) if this is interrupted.
            // Insert in chunks: large Xtream accounts / M3U lists can hold tens of
            // thousands of channels. Mapping + inserting all at once spikes memory and
            // can OOM on low-RAM TV boxes, so bound the peak by batching.
            val committed = support.commitSnapshot(config, generation) {
                channelDao.clearType(ContentType.LIVE.name)
                ordered.chunked(1000).forEach { batch ->
                    channelDao.upsertAll(batch.map { it.toEntity() })
                }
                channelFtsDao.clearAll()
                ordered.chunked(1000).forEach { batch ->
                    channelFtsDao.insertAll(batch.map { ChannelFtsEntity(it.id, it.name) })
                }
            }
            if (!committed) return@withContext staleDataset(CatalogDataset.LIVE)
            if (newLiveCount > 0) NewContentNotifier.addLive(newLiveCount)
            Outcome.Success(ordered.size)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            e.toOutcomeFailure()
        }
    }

    private suspend fun loadXtreamLive(config: SourceConfig): StagedDataset<Channel> {
        val api = support.buildXtreamApi(config.serverUrl)
        val categoryList = api.getLiveCategories(config.username, config.password)
        val categories = categoryList
            .associate { (it.categoryId ?: "") to (it.categoryName ?: "Uncategorized") }
        // Index each category by its position in the server's category list so the
        // UI can present categories in the same order the provider returned them.
        val categoryOrder = categoryList
            .mapIndexedNotNull { index, c -> c.categoryId?.let { it to index } }
            .toMap()
        val streams = api.getLiveStreams(config.username, config.password)
        val channels = streams.mapNotNull { s ->
            val id = s.streamId ?: return@mapNotNull null
            Channel(
                id = "xt_live_$id",
                name = s.name ?: "Unknown",
                streamUrl = XtreamUrlBuilder.liveUrl(
                    config.serverUrl,
                    config.username,
                    config.password,
                    id,
                    directSource = s.directSource,
                ),
                logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                categoryId = s.categoryId,
                categoryName = categories[s.categoryId] ?: "Uncategorized",
                epgChannelId = s.epgChannelId?.takeIf { it.isNotBlank() },
                number = s.num,
                type = ContentType.LIVE,
                catchupDays = if (s.tvArchive == 1) (s.tvArchiveDuration ?: 7).coerceAtLeast(1) else 0,
                categoryPosition = categoryOrder[s.categoryId] ?: Int.MAX_VALUE
            )
        }
        return StagedDataset(streams.size, channels)
    }

    private fun loadM3u(config: SourceConfig): StagedDataset<Channel> {
        val request = Request.Builder().url(config.m3uUrl).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty body")
            val parsed = body.charStream().buffered().use { M3uParser.parse(it) }
            // M3U has no separate category list, so category order is the order in
            // which each group first appears in the playlist.
            val categoryOrder = LinkedHashMap<String?, Int>()
            parsed.forEach { c -> categoryOrder.getOrPut(c.categoryId) { categoryOrder.size } }
            return StagedDataset(parsed.size, parsed.map { c ->
                c.copy(categoryPosition = categoryOrder[c.categoryId] ?: Int.MAX_VALUE)
            })
        }
    }
}
