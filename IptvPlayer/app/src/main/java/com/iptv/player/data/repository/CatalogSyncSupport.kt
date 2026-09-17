/*
 * CatalogSyncSupport.kt
 * Process-wide state and helpers shared by every catalog domain repository:
 * the single commit mutex + generation gate that serialise snapshot swaps, the
 * shrink-guard ledger glue, the Xtream API factory, panel timezone memory, the
 * episode-date sweep backoff, and the Throwable -> Outcome/AppError mapping.
 * Exactly one instance exists per IptvRepository so the gates stay global.
 */
package com.iptv.player.data.repository

import androidx.room.withTransaction
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.remote.XtreamApi
import com.iptv.player.data.remote.XtreamUrlBuilder
import com.iptv.player.util.AppError
import com.iptv.player.util.HttpAppErrorPolicy
import com.iptv.player.util.Logger
import com.iptv.player.util.Outcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

internal class CatalogSyncSupport(
    private val db: AppDatabase,
    private val retrofitBuilder: Retrofit.Builder,
    private val settings: SettingsStore,
    private val shrinkLedger: ShrinkGuardLedger,
) {
    private val catalogCommitMutex = Mutex()
    val refreshGenerations = DatasetGenerationGate()
    val panelTimezones = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Episode-date sweeps pause until this instant after a 429/5xx from the panel. */
    @Volatile
    var seriesDatesBackoffUntil = 0L

    private suspend fun mayCommit(
        config: SourceConfig,
        generation: DatasetGenerationGate.Token,
    ): Boolean = refreshGenerations.isCurrent(generation) &&
        SourceIdentity.matches(settings.getSourceConfig(), config)

    /** Re-check generation/source while holding the same lock as the Room swap. */
    suspend fun commitSnapshot(
        config: SourceConfig,
        token: DatasetGenerationGate.Token,
        block: suspend () -> Unit,
    ): Boolean = catalogCommitMutex.withLock {
        if (!mayCommit(config, token)) return@withLock false
        db.withTransaction { block() }
        true
    }

    /**
     * Runs the refresh gate and keeps the persisted shrink ledger in step: a
     * shrink rejection is counted, any accepted snapshot resets the count, so a
     * provider that really shrank stops being refused after a few syncs.
     */
    fun evaluateRefresh(
        dataset: CatalogDataset,
        generation: DatasetGenerationGate.Token,
        snapshot: DatasetSnapshot,
        force: Boolean = false,
    ): DatasetRefreshDecision = evaluateRefreshWithLedger(
        shrinkLedger, dataset, generation, snapshot, force, System.currentTimeMillis(),
    )

    fun buildXtreamApi(serverUrl: String): XtreamApi {
        val base = XtreamUrlBuilder.apiBaseUrl(serverUrl)
        return retrofitBuilder.baseUrl(base).build().create(XtreamApi::class.java)
    }
}

/** Ledger-aware refresh gate, separated from the clock so it is unit-testable. */
internal fun evaluateRefreshWithLedger(
    shrinkLedger: ShrinkGuardLedger,
    dataset: CatalogDataset,
    generation: DatasetGenerationGate.Token,
    snapshot: DatasetSnapshot,
    force: Boolean,
    now: Long,
): DatasetRefreshDecision {
    val decision = DatasetRefreshPolicy.evaluate(
        dataset, snapshot, shrinkLedger.get(generation.key), now, force,
    )
    when {
        decision is DatasetRefreshDecision.PreserveCache &&
            decision.reason == DatasetRefreshPolicy.REASON_SHRINK -> {
            val rejection = shrinkLedger.recordRejection(generation.key, now)
            Logger.w("CatalogSync", "$dataset shrink rejected ${rejection.count}x since ${rejection.firstRejectedAt}")
        }
        decision is DatasetRefreshDecision.Apply -> shrinkLedger.clear(generation.key)
    }
    return decision
}

internal fun DatasetGenerationGate.Token.policyExistingCount(physicalCount: Int): Int =
    if (sourceChanged) 0 else physicalCount

internal fun preservedDataset(
    dataset: CatalogDataset,
    reason: String,
): Outcome.Failure {
    Logger.w("CatalogSync", "preserved $dataset cache: $reason")
    return Outcome.Failure(AppError.EMPTY_PLAYLIST)
}

internal fun staleDataset(dataset: CatalogDataset): Outcome.Failure {
    Logger.w("CatalogSync", "ignored stale $dataset generation")
    return Outcome.Failure(AppError.CANNOT_CONNECT)
}

internal fun datasetSyncResult(
    name: String,
    cachedBefore: Int,
    outcome: Outcome<Int>,
): DatasetSyncResult = when (outcome) {
    is Outcome.Success -> DatasetSyncResult(
        dataset = name,
        status = if (outcome.data == 0 && cachedBefore == 0) {
            DatasetSyncStatus.EMPTY
        } else {
            DatasetSyncStatus.UPDATED
        },
        itemCount = outcome.data,
    )
    is Outcome.Failure -> DatasetSyncResult(
        dataset = name,
        status = if (cachedBefore > 0) {
            DatasetSyncStatus.PRESERVED_CACHE
        } else {
            DatasetSyncStatus.FAILED
        },
        itemCount = cachedBefore,
        errorCode = outcome.httpStatus?.let { "HTTP_$it" } ?: outcome.error.name,
    )
}

/**
 * Turns raw user input into a safe FTS4 MATCH expression: splits on
 * non-alphanumerics and appends a prefix wildcard to each token, AND-ed
 * together (e.g. "harry pot" -> "name:harry* name:pot*"). The column filter
 * keeps id fragments ("xt_live_12") from matching. Returns "" when there's
 * nothing to match so callers can short-circuit to an empty result instead
 * of issuing an invalid empty MATCH (which SQLite rejects).
 */
internal fun toFtsQuery(raw: String): String =
    raw.trim().lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { "name:$it*" }

internal fun youtube(idOrUrl: String?): String? {
    val v = idOrUrl?.trim().orEmpty()
    if (v.isBlank()) return null
    return if (v.startsWith("http")) v else "https://www.youtube.com/watch?v=$v"
}

private const val MAX_CAUSE_DEPTH = 8

internal fun Throwable.toOutcomeFailure(): Outcome.Failure {
    val httpStatus = causeChain()
        .filterIsInstance<HttpException>()
        .firstOrNull()
        ?.code()
        ?.takeIf { it in 400..599 }
    return Outcome.Failure(
        error = toAppError(),
        httpStatus = httpStatus,
    )
}

internal fun Throwable.toAppError(): AppError {
    val causes = causeChain().toList()
    val mapped = when {
        causes.any { it is HttpException } -> HttpAppErrorPolicy.fromStatus(
            causes.filterIsInstance<HttpException>().first().code(),
        )
        causes.any { it is SocketTimeoutException } -> AppError.REQUEST_TIMEOUT
        causes.any { it is SSLException } -> AppError.SECURE_CONNECTION_FAILED
        causes.any { it is IOException } -> AppError.CANNOT_CONNECT
        // OutOfMemoryError (huge playlists/accounts on low-RAM TV boxes) and other
        // non-Exception Throwables must surface as a friendly error, not crash the app.
        causes.any { it is OutOfMemoryError } -> AppError.EMPTY_PLAYLIST
        else -> AppError.UNKNOWN
    }
    // Record the underlying cause so field logs explain provider failures that
    // the user only sees as a friendly message.
    Logger.w("IptvRepository", "Operation failed -> $mapped", this)
    return mapped
}

/** Bounded cause traversal keeps wrapped Retrofit/OkHttp transport failures useful. */
internal fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeChain
    repeat(MAX_CAUSE_DEPTH) {
        val value = current ?: return@sequence
        yield(value)
        val next = value.cause
        if (next === value) return@sequence
        current = next
    }
}
