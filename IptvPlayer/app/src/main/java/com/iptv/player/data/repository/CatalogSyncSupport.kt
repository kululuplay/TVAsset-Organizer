/*
 * CatalogSyncSupport.kt
 * Process-wide state and helpers shared by every catalog domain repository:
 * the single commit mutex + generation gate that serialise snapshot swaps, the
 * shrink-guard ledger glue, the Xtream API factory, panel timezone memory, the
 * episode-date sweep backoff, the shared in-flight registries that keep two
 * screens from downloading the same category twice, the post-failure catalog
 * backoff, and the Throwable -> Outcome/AppError mapping.
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    /** Shared downloads outlive the screen that started them, so they run here, not in a caller's scope. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val catalogCommitMutex = Mutex()
    val refreshGenerations = DatasetGenerationGate()
    val panelTimezones = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Episode-date sweeps pause until this instant after a 429/5xx from the panel. */
    @Volatile
    var seriesDatesBackoffUntil = 0L

    /** Per-category downloads in flight, keyed like their generation ("vod_category:<id>"). */
    val categoryWork = SharedWork<String, CategorySync>(scope)
    /** Category-index downloads in flight ("vod_categories", "series_categories"). */
    val indexWork = SharedWork<String, Outcome<Int>>(scope)
    val movieSweep = SharedSweep(scope)
    val seriesSweep = SharedSweep(scope)
    val catalogBackoff = CatalogBackoff()

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
        stillListed: Boolean = false,
    ): DatasetRefreshDecision = evaluateRefreshWithLedger(
        shrinkLedger, dataset, generation, snapshot, force, System.currentTimeMillis(), stillListed,
    )

    fun buildXtreamApi(serverUrl: String): XtreamApi {
        val base = XtreamUrlBuilder.apiBaseUrl(serverUrl)
        return retrofitBuilder.baseUrl(base).build().create(XtreamApi::class.java)
    }
}

/**
 * De-duplicates concurrent catalog work: a second caller for the same key and
 * source awaits the in-flight result instead of starting a duplicate request
 * (the generation gate used to turn whichever request finished second into a
 * false "Cannot connect"). Work runs on [scope] so an abandoned caller (screen
 * closed, playback started) never cancels a result another caller still waits
 * for. A caller for a different source waits for the current work to finish,
 * then runs its own.
 */
internal class SharedWork<K : Any, V>(private val scope: CoroutineScope) {
    private inner class Entry(val config: SourceConfig, val deferred: Deferred<V>)

    private val lock = Any()
    private val entries = HashMap<K, Entry>()

    fun isRunning(key: K): Boolean = synchronized(lock) { entries[key]?.deferred?.isActive == true }

    suspend fun join(key: K, config: SourceConfig, block: suspend () -> V): V {
        while (true) {
            val claim: Pair<Entry?, Deferred<V>?> = synchronized(lock) {
                val running = entries[key]?.takeIf { it.deferred.isActive }
                when {
                    running == null -> start(key, config, block) to null
                    SourceIdentity.matches(running.config, config) -> running to null
                    else -> null to running.deferred
                }
            }
            val (entry, busy) = claim
            if (entry != null) return entry.deferred.await()
            busy?.join()
        }
    }

    private fun start(key: K, config: SourceConfig, block: suspend () -> V): Entry {
        val deferred = scope.async(start = CoroutineStart.LAZY) { block() }
        val entry = Entry(config, deferred)
        entries[key] = entry
        deferred.invokeOnCompletion {
            synchronized(lock) { if (entries[key] === entry) entries.remove(key) }
        }
        deferred.start()
        return entry
    }
}

/** What a caller asked a catalog sweep to cover. */
internal data class SweepRequest(
    val forceAll: Boolean,
    /** Restrict the sweep to these category ids (a retry of what failed); null = every eligible category. */
    val only: Set<String>? = null,
) {
    /** A running sweep serves a later request when it forces at least as much and visits every category asked for. */
    fun covers(other: SweepRequest): Boolean =
        (forceAll || !other.forceAll) &&
            (only == null || (other.only != null && only.containsAll(other.only)))
}

/**
 * One catalog sweep at a time. The Dashboard refresh, the Movies screen and the
 * launch re-sync used to race each other over the same categories; now a second
 * caller follows the running sweep's progress and receives its report. A request
 * the running sweep does not cover (forced after non-forced, or a wider set)
 * waits for it and then runs. Sweeps run on the shared scope, so a caller that
 * stops waiting (Dashboard hidden) abandons the progress, not the sweep.
 */
internal class SharedSweep(private val scope: CoroutineScope) {
    private class Handle(
        val config: SourceConfig,
        val request: SweepRequest,
        val progress: MutableStateFlow<Pair<Int, Int>>,
        val deferred: Deferred<CatalogSweepReport>,
    )

    private val lock = Any()
    private var active: Handle? = null

    val isRunning: Boolean get() = synchronized(lock) { active?.deferred?.isActive == true }

    suspend fun run(
        config: SourceConfig,
        request: SweepRequest,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
        sweep: suspend (onProgress: suspend (Int, Int) -> Unit) -> CatalogSweepReport,
    ): CatalogSweepReport {
        while (true) {
            val claim: Pair<Handle?, Deferred<CatalogSweepReport>?> = synchronized(lock) {
                val running = active?.takeIf { it.deferred.isActive }
                when {
                    running == null -> start(config, request, sweep) to null
                    SourceIdentity.matches(running.config, config) && running.request.covers(request) ->
                        running to null
                    else -> null to running.deferred
                }
            }
            val (handle, busy) = claim
            if (handle != null) return handle.follow(onProgress)
            busy?.join()
        }
    }

    private fun start(
        config: SourceConfig,
        request: SweepRequest,
        sweep: suspend (onProgress: suspend (Int, Int) -> Unit) -> CatalogSweepReport,
    ): Handle {
        val progress = MutableStateFlow(0 to 0)
        val deferred = scope.async { sweep { completed, total -> progress.value = completed to total } }
        return Handle(config, request, progress, deferred).also { active = it }
    }

    /** Relays progress to this caller until the report arrives; cancelling the caller cancels only the relay. */
    private suspend fun Handle.follow(onProgress: suspend (Int, Int) -> Unit): CatalogSweepReport =
        coroutineScope {
            val relay = launch { progress.collect { (completed, total) -> onProgress(completed, total) } }
            try {
                deferred.await()
            } finally {
                relay.cancel()
            }
        }
}

/**
 * Pauses non-forced catalog downloads after a systemic panel failure so a dead,
 * refusing or throttling panel is not asked category by category, 15 s at a
 * time. Forced (manual, launch) requests always go to the network; any
 * successful download clears the pause.
 */
internal class CatalogBackoff {
    @Volatile
    private var until = 0L

    @Volatile
    private var failure: Outcome.Failure? = null

    /** Records the sweep's systemic failure, if any; returns the pause length in ms (0 = none). */
    fun noteSweep(report: CatalogSweepReport, now: Long = System.currentTimeMillis()): Long {
        val systemic = report.systemicFailure ?: return 0L
        return pauseAfter(systemic, now)
    }

    fun pauseAfter(failure: Outcome.Failure, now: Long = System.currentTimeMillis()): Long {
        val ms = CatalogSweepPolicy.backoffMs(failure)
        if (ms <= 0L) return 0L
        this.failure = failure
        until = now + ms
        Logger.w("CatalogSync", "catalog downloads paused ${ms / 1000} s after ${failure.error}")
        return ms
    }

    /** The failure that still pauses downloads, or null when requests may proceed. */
    fun activeFailure(now: Long = System.currentTimeMillis()): Outcome.Failure? =
        if (now < until) failure else null

    fun clear() {
        until = 0L
        failure = null
    }
}

/** Ledger key for consecutive empty replies, kept apart from the shrink count of the same dataset. */
internal fun emptyResponseKey(generationKey: String): String = "$generationKey:empty"

/** Ledger-aware refresh gate, separated from the clock so it is unit-testable. */
internal fun evaluateRefreshWithLedger(
    shrinkLedger: ShrinkGuardLedger,
    dataset: CatalogDataset,
    generation: DatasetGenerationGate.Token,
    snapshot: DatasetSnapshot,
    force: Boolean,
    now: Long,
    stillListed: Boolean = false,
): DatasetRefreshDecision {
    val emptyKey = emptyResponseKey(generation.key)
    val decision = DatasetRefreshPolicy.evaluate(
        dataset, snapshot, shrinkLedger.get(generation.key), now, force,
        priorEmptyResponses = shrinkLedger.get(emptyKey),
        stillListed = stillListed,
    )
    when {
        decision is DatasetRefreshDecision.PreserveCache &&
            decision.reason == DatasetRefreshPolicy.REASON_SHRINK -> {
            val rejection = shrinkLedger.recordRejection(generation.key, now)
            Logger.w("CatalogSync", "$dataset shrink rejected ${rejection.count}x since ${rejection.firstRejectedAt}")
        }
        decision is DatasetRefreshDecision.PreserveCache &&
            decision.reason == DatasetRefreshPolicy.REASON_EMPTY -> {
            // Only a forced sweep of a category the panel still lists counts towards
            // accepting the empty reply (the Movies Refresh button, or the series
            // screen's first full catalog load); non-forced background checks and
            // the launch loaded-sweep stay neutral.
            if (force && stillListed) {
                val seen = shrinkLedger.recordRejection(emptyKey, now)
                Logger.w("CatalogSync", "$dataset empty reply kept cache ${seen.count}x")
            }
        }
        decision is DatasetRefreshDecision.Apply -> {
            shrinkLedger.clear(generation.key)
            shrinkLedger.clear(emptyKey)
        }
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
        errorCode = outcome.errorCode(),
        error = outcome.error,
    )
}

/**
 * The per-category sweep as a dataset row: item count is what the sweep really
 * added, and a sweep with failed or skipped categories is PARTIAL (some
 * refreshed) or FAILED (none did), never UPDATED.
 */
internal fun sweepSyncResult(name: String, report: CatalogSweepReport): DatasetSyncResult {
    val firstFailure = report.indexFailure ?: report.failures.firstOrNull()?.failure
    val status = when {
        report.successful -> if (report.total == 0) DatasetSyncStatus.EMPTY else DatasetSyncStatus.UPDATED
        report.completed > report.failures.size -> DatasetSyncStatus.PARTIAL
        else -> DatasetSyncStatus.FAILED
    }
    return DatasetSyncResult(
        dataset = name,
        status = status,
        itemCount = report.itemCount,
        errorCode = firstFailure?.errorCode(),
        error = firstFailure?.error,
    )
}

private fun Outcome.Failure.errorCode(): String = httpStatus?.let { "HTTP_$it" } ?: error.name

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

/**
 * SQLite binds every element of an `IN (:ids)` list as a separate variable and
 * older Android builds (SQLite < 3.32, i.e. Android 10 and below) cap that at
 * 999. Deleting a migrated catalog's stale rows passed thousands of ids in one
 * statement, so the category refresh threw "too many SQL variables" on every
 * launch and the stale category list survived forever. Chunk every id-list
 * statement well under the limit.
 */
internal const val SQL_ID_CHUNK = 500

internal suspend inline fun <T> List<T>.forEachSqlChunk(block: (List<T>) -> Unit) {
    if (isEmpty()) return
    chunked(SQL_ID_CHUNK).forEach { block(it) }
}
