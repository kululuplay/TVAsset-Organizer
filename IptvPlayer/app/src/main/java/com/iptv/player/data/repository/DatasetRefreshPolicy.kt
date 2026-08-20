package com.iptv.player.data.repository

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
)

internal data class StagedDataset<T>(
    val receivedCount: Int,
    val items: List<T>,
)

internal sealed interface DatasetRefreshDecision {
    data object Apply : DatasetRefreshDecision
    data class PreserveCache(val reason: String) : DatasetRefreshDecision
}

/**
 * Rejects response shapes that commonly mean a truncated/error payload was
 * deserialized as a valid (but incomplete) Xtream snapshot.  Deleting stale rows
 * is safe only after this gate accepts the complete staged response.
 */
internal object DatasetRefreshPolicy {
    private const val MIN_VALID_PERCENT = 80
    private const val MAX_REMAINING_PERCENT = 25

    fun evaluate(
        dataset: CatalogDataset,
        snapshot: DatasetSnapshot,
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
            return DatasetRefreshDecision.PreserveCache("suspicious_snapshot_shrink")
        }
        return DatasetRefreshDecision.Apply
    }
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
data class SyncReport(val datasets: List<DatasetSyncResult>) {
    val live: DatasetSyncResult? get() = datasets.firstOrNull { it.dataset == "live" }
    val liveRefreshSucceeded: Boolean get() = live?.refreshed == true
    val allRefreshSucceeded: Boolean
        get() = datasets.isNotEmpty() && datasets.all { it.refreshed }
}
