package com.iptv.player.data.repository

import android.content.SharedPreferences
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Catalog partitions whose snapshots can be replaced independently. */
internal enum class CatalogDataset {
    LIVE,
    VOD_CATEGORIES,
    VOD_CATEGORY,
    SERIES_CATEGORIES,
    SERIES_CATEGORY,
    SERIES_EPISODES,
    EPG,
}

internal data class DatasetSnapshot(
    val existingCount: Int,
    val receivedCount: Int,
    val acceptedCount: Int,
    /**
     * How many accepted ids already exist in the cache; null when the caller
     * did not compare ids. Zero overlap with a non-empty response means the
     * server now serves a different catalog (panel migration, re-imported
     * ids), which the shrink guard must not mistake for a truncated reply.
     */
    val overlapCount: Int? = null,
)

internal data class StagedDataset<T>(
    val receivedCount: Int,
    val items: List<T>,
)

internal sealed interface DatasetRefreshDecision {
    data object Apply : DatasetRefreshDecision
    data class PreserveCache(val reason: String) : DatasetRefreshDecision
}

/** Consecutive shrink rejections recorded for one dataset key. */
data class ShrinkRejection(
    val count: Int,
    val firstRejectedAt: Long,
)

/**
 * Rejects response shapes that commonly mean a truncated/error payload was
 * deserialized as a valid (but incomplete) Xtream snapshot.  Deleting stale rows
 * is safe only after this gate accepts the complete staged response.
 */
internal object DatasetRefreshPolicy {
    private const val MIN_VALID_PERCENT = 80
    private const val MAX_REMAINING_PERCENT = 25

    const val REASON_SHRINK = "suspicious_snapshot_shrink"

    /**
     * A provider that genuinely dropped most of its catalog keeps answering with
     * the same small snapshot. After this many consecutive rejections, or once
     * the first rejection is a day old, the shrink is accepted as real.
     */
    const val SHRINK_OVERRIDE_AFTER_REJECTIONS = 3
    const val SHRINK_OVERRIDE_AFTER_MS = 24L * 60L * 60L * 1000L

    fun evaluate(
        dataset: CatalogDataset,
        snapshot: DatasetSnapshot,
        priorRejections: ShrinkRejection? = null,
        nowMs: Long = 0L,
        force: Boolean = false,
    ): DatasetRefreshDecision {
        val existing = snapshot.existingCount.coerceAtLeast(0)
        val received = snapshot.receivedCount.coerceAtLeast(0)
        val accepted = snapshot.acceptedCount.coerceAtLeast(0)

        if (accepted > received) {
            return DatasetRefreshDecision.PreserveCache("accepted_count_exceeds_received")
        }
        if (existing > 0 && accepted == 0) {
            return DatasetRefreshDecision.PreserveCache("empty_response_with_cached_rows")
        }
        if (received >= 10 && accepted * 100 < received * MIN_VALID_PERCENT) {
            return DatasetRefreshDecision.PreserveCache("too_many_invalid_rows")
        }

        val shrinkGuardAt = when (dataset) {
            CatalogDataset.LIVE -> 20
            CatalogDataset.VOD_CATEGORIES, CatalogDataset.SERIES_CATEGORIES -> 10
            CatalogDataset.VOD_CATEGORY, CatalogDataset.SERIES_CATEGORY -> 12
            CatalogDataset.SERIES_EPISODES -> 4
            CatalogDataset.EPG -> 100
        }
        if (
            existing >= shrinkGuardAt &&
            accepted * 100 < existing * MAX_REMAINING_PERCENT
        ) {
            // A manual refresh is the user saying "trust the server"; a repeated
            // or day-old rejection means the shrink is the new truth.
            if (force) return DatasetRefreshDecision.Apply
            // Field case: devices migrated from another panel kept 150 old
            // category ids and rejected the new 28-category list forever, so
            // every per-category fetch asked the new panel for ids it never had.
            if (snapshot.overlapCount == 0 && accepted > 0) return DatasetRefreshDecision.Apply
            if (priorRejections != null && (
                    priorRejections.count >= SHRINK_OVERRIDE_AFTER_REJECTIONS ||
                        nowMs - priorRejections.firstRejectedAt >= SHRINK_OVERRIDE_AFTER_MS
                    )
            ) {
                return DatasetRefreshDecision.Apply
            }
            return DatasetRefreshDecision.PreserveCache(REASON_SHRINK)
        }
        return DatasetRefreshDecision.Apply
    }
}

/**
 * Remembers shrink rejections per dataset key across process restarts (when
 * backed by [SharedPreferences]) so the guard cannot pin a source to a stale
 * snapshot forever. Without preferences it degrades to process memory.
 */
class ShrinkGuardLedger(private val prefs: SharedPreferences? = null) {
    private val memory = HashMap<String, ShrinkRejection>()

    @Synchronized
    fun get(key: String): ShrinkRejection? {
        memory[key]?.let { return it }
        val count = prefs?.getInt(countKey(key), 0) ?: 0
        if (count <= 0) return null
        return ShrinkRejection(count, prefs?.getLong(sinceKey(key), 0L) ?: 0L).also { memory[key] = it }
    }

    @Synchronized
    fun recordRejection(key: String, nowMs: Long): ShrinkRejection {
        val previous = get(key)
        val next = ShrinkRejection(
            count = (previous?.count ?: 0) + 1,
            firstRejectedAt = previous?.firstRejectedAt?.takeIf { it > 0L } ?: nowMs,
        )
        memory[key] = next
        prefs?.edit()?.putInt(countKey(key), next.count)?.putLong(sinceKey(key), next.firstRejectedAt)?.apply()
        return next
    }

    @Synchronized
    fun clear(key: String) {
        if (memory.remove(key) == null && prefs?.contains(countKey(key)) != true) return
        prefs?.edit()?.remove(countKey(key))?.remove(sinceKey(key))?.apply()
    }

    private fun countKey(key: String) = "shrink_count:$key"
    private fun sinceKey(key: String) = "shrink_since:$key"
}

/**
 * Process-local generation gate: if two refreshes overlap, a late response from
 * the older request can no longer overwrite the newer source snapshot.
 */
internal class DatasetGenerationGate {
    data class Token internal constructor(
        val key: String,
        val generation: Long,
        /** A known in-process source switch must not compare against the old cache size. */
        val sourceChanged: Boolean,
    )

    private val generations = mutableMapOf<String, Long>()
    private val sources = mutableMapOf<String, SourceConfig>()

    @Synchronized
    fun begin(key: String, source: SourceConfig? = null): Token {
        val next = (generations[key] ?: 0L) + 1L
        val previousSource = sources[key]
        val sourceChanged = source != null && previousSource != null &&
            !SourceIdentity.matches(previousSource, source)
        generations[key] = next
        if (source != null) sources[key] = source
        return Token(key, next, sourceChanged)
    }

    @Synchronized
    fun isCurrent(token: Token): Boolean = generations[token.key] == token.generation
}

/** No credential is logged or persisted; this is comparison-only. */
internal object SourceIdentity {
    fun matches(first: SourceConfig?, second: SourceConfig): Boolean {
        if (first == null || first.type != second.type) return false
        return when (second.type) {
            SourceType.XTREAM ->
                canonicalServer(first.serverUrl) == canonicalServer(second.serverUrl) &&
                    first.username == second.username && first.password == second.password
            SourceType.M3U_URL -> canonicalUrl(first.m3uUrl) == canonicalUrl(second.m3uUrl)
        }
    }

    private fun canonicalServer(value: String): String =
        value.trim().toHttpUrlOrNull()?.newBuilder()
            ?.query(null)
            ?.fragment(null)
            ?.build()
            ?.toString()
            ?.trimEnd('/')
            ?: value.trim().trimEnd('/')

    private fun canonicalUrl(value: String): String =
        value.trim().toHttpUrlOrNull()?.newBuilder()
            ?.fragment(null)
            ?.build()
            ?.toString()
            ?: value.trim()
}

enum class DatasetSyncStatus {
    UPDATED,
    EMPTY,
    PRESERVED_CACHE,
    FAILED,
}

data class DatasetSyncResult(
    val dataset: String,
    val status: DatasetSyncStatus,
    val itemCount: Int = 0,
    val errorCode: String? = null,
) {
    val refreshed: Boolean get() = status == DatasetSyncStatus.UPDATED || status == DatasetSyncStatus.EMPTY
}

/** Detailed replacement for the legacy one-bit background-sync result. */
data class SyncReport(
    val datasets: List<DatasetSyncResult>,
    val movieContents: MovieCatalogRefreshReport? = null,
) {
    val live: DatasetSyncResult? get() = datasets.firstOrNull { it.dataset == "live" }
    val liveRefreshSucceeded: Boolean get() = live?.refreshed == true
    val allRefreshSucceeded: Boolean
        get() = datasets.isNotEmpty() && datasets.all { it.refreshed } && movieContents?.successful != false

    /** A category-index-only sync must never claim that movie contents refreshed. */
    val manualRefreshSucceeded: Boolean
        get() = allRefreshSucceeded && movieContents?.let { it.successful && it.total > 0 } == true
}
