/*
 * MovieCatalogRefresh.kt
 * The category sweep shared by the movie and series catalogs: a sequential,
 * bounded-memory loop that keeps going past a broken category, stops early once
 * the panel itself is the problem, and reports every category it did not
 * finish. Pure (no Room, no network) so every rule here is unit-tested.
 */
package com.iptv.player.data.repository

import com.iptv.player.util.AppError
import com.iptv.player.util.Outcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One category of a sweep; the name is only used for the error report. */
data class SweepCategory(val id: String, val name: String)
data class SweepCategoryFailure(val category: SweepCategory, val failure: Outcome.Failure)

/** Why a sweep gave up before visiting every category. */
enum class SweepStop {
    /** Unreachable, refusing the account or throttling: later categories would fail the same way. */
    SYSTEMIC_FAILURE,
    /** Several categories in a row failed for any reason. */
    CONSECUTIVE_FAILURES,
}

/**
 * Result of one category download. Richer than [Outcome] so a cache the refresh
 * gate kept, or a request a newer one superseded, is never counted as a failed
 * fetch (an emptied category used to "fail" on every refresh forever).
 */
sealed interface CategorySync {
    /** Fresh rows committed; [newCount] is how many were not cached before. */
    data class Applied(val itemCount: Int, val newCount: Int) : CategorySync

    /** The refresh gate kept the cached rows (empty or suspicious reply); nothing changed. */
    data class Preserved(val reason: String) : CategorySync

    /** A newer request for the same category, or a source switch, owns the commit. */
    data object Superseded : CategorySync

    data class Failed(val failure: Outcome.Failure) : CategorySync
}

/** Legacy one-number contract: a kept cache or a superseded request is simply "nothing new". */
fun CategorySync.toOutcome(force: Boolean): Outcome<Int> = when (this) {
    is CategorySync.Applied -> Outcome.Success(if (force) newCount else itemCount)
    is CategorySync.Preserved, CategorySync.Superseded -> Outcome.Success(0)
    is CategorySync.Failed -> failure
}

data class CatalogSweepReport(
    val total: Int,
    val completed: Int,
    val failures: List<SweepCategoryFailure>,
    val indexFailure: Outcome.Failure? = null,
    /** Categories never fetched because the sweep stopped early; not failures. */
    val skipped: List<SweepCategory> = emptyList(),
    /** Categories whose cached rows the refresh gate kept. */
    val preserved: Int = 0,
    /** Categories whose fetch a newer request committed instead. */
    val superseded: Int = 0,
    /** Titles counted by the successful fetches: new ones on a forced sweep, downloaded ones otherwise. */
    val itemCount: Int = 0,
    val forced: Boolean = false,
    val stopReason: SweepStop? = null,
    /** Every category the sweep set out to visit. */
    val categories: List<SweepCategory> = emptyList(),
    /** Categories the account has at all, hidden ones included. */
    val availableCategories: Int = total,
) {
    val successful: Boolean get() = completed == total && failures.isEmpty() && indexFailure == null

    /** What a retry should cover: everything that failed or was never reached. */
    val unfinished: List<SweepCategory> get() = failures.map { it.category } + skipped

    /** Categories the panel answered for: fresh rows, a kept cache or a newer request's commit. */
    val refreshed: List<SweepCategory>
        get() {
            val pending = unfinished.toSet()
            return categories.filterNot { it in pending }
        }

    /** The first failure that means the panel, not one category, is broken. */
    val systemicFailure: Outcome.Failure?
        get() = failures.firstOrNull { CatalogSweepPolicy.isSystemic(it.failure) }?.failure

    /** A live-only account has nothing to sweep; that is not a failed refresh. */
    val notApplicable: Boolean get() = availableCategories == 0
}

typealias MovieRefreshCategory = SweepCategory
typealias MovieCategoryFailure = SweepCategoryFailure
typealias MovieCatalogRefreshReport = CatalogSweepReport

/** Failure classification for the sweep, separated from the loop so it is unit-testable. */
internal object CatalogSweepPolicy {
    /** A run of this many failed categories stops the sweep whatever the errors were. */
    const val MAX_CONSECUTIVE_FAILURES = 3

    const val BACKOFF_CONNECTIVITY_MS = 60_000L
    const val BACKOFF_AUTH_MS = 5L * 60_000L
    const val BACKOFF_THROTTLED_MS = SeriesDateSweepPolicy.SERIES_DATES_BACKOFF_429_MS

    enum class Kind { CONNECTIVITY, AUTH, THROTTLED, SERVER_ERROR }

    /** Null when the failure is specific to one category (missing, malformed, unknown). */
    fun kind(failure: Outcome.Failure): Kind? {
        val status = failure.httpStatus
        if (status != null) {
            return when {
                status == 401 || status == 403 -> Kind.AUTH
                status == 408 -> Kind.CONNECTIVITY
                status == 429 -> Kind.THROTTLED
                SeriesDateSweepPolicy.isThrottleStatus(status) -> Kind.SERVER_ERROR
                else -> null
            }
        }
        return when (failure.error) {
            AppError.CANNOT_CONNECT,
            AppError.NO_INTERNET,
            AppError.REQUEST_TIMEOUT,
            AppError.SECURE_CONNECTION_FAILED -> Kind.CONNECTIVITY
            AppError.BAD_CREDENTIALS,
            AppError.ACCESS_DENIED,
            AppError.ACCOUNT_DISABLED,
            AppError.SUBSCRIPTION_EXPIRED -> Kind.AUTH
            AppError.TOO_MANY_REQUESTS -> Kind.THROTTLED
            AppError.SERVER_UNAVAILABLE -> Kind.SERVER_ERROR
            else -> null
        }
    }

    /** True when asking the next category would fail the same way. */
    fun isSystemic(failure: Outcome.Failure): Boolean = kind(failure) != null

    /**
     * How long non-forced downloads pause after a systemic failure. A 5xx gets
     * no pause: it stops the current sweep but may be one broken category.
     */
    fun backoffMs(failure: Outcome.Failure): Long = when (kind(failure)) {
        Kind.CONNECTIVITY -> BACKOFF_CONNECTIVITY_MS
        Kind.AUTH -> BACKOFF_AUTH_MS
        Kind.THROTTLED -> BACKOFF_THROTTLED_MS
        Kind.SERVER_ERROR, null -> 0L
    }
}

/**
 * Sequential, bounded-memory sweep. A failed category never skips the next one
 * unless the failure is systemic or three categories in a row failed: an
 * unreachable or throttling panel then costs one request instead of one per
 * category (150 categories used to mean 37 minutes of timeouts).
 */
internal object CatalogSweep {
    suspend fun run(
        categories: List<SweepCategory>,
        indexFailure: Outcome.Failure? = null,
        forced: Boolean = false,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
        fetch: suspend (String) -> CategorySync,
    ): CatalogSweepReport {
        val failures = mutableListOf<SweepCategoryFailure>()
        var completed = 0
        var preserved = 0
        var superseded = 0
        var items = 0
        var consecutiveFailures = 0
        var stop: SweepStop? = null
        onProgress(0, categories.size)
        for (category in categories) {
            currentCoroutineContext().ensureActive()
            when (val result = fetch(category.id)) {
                is CategorySync.Applied -> {
                    items += if (forced) result.newCount else result.itemCount
                    consecutiveFailures = 0
                }
                is CategorySync.Preserved -> {
                    preserved++
                    consecutiveFailures = 0
                }
                CategorySync.Superseded -> {
                    superseded++
                    consecutiveFailures = 0
                }
                is CategorySync.Failed -> {
                    failures += SweepCategoryFailure(category, result.failure)
                    consecutiveFailures++
                    stop = when {
                        CatalogSweepPolicy.isSystemic(result.failure) -> SweepStop.SYSTEMIC_FAILURE
                        consecutiveFailures >= CatalogSweepPolicy.MAX_CONSECUTIVE_FAILURES ->
                            SweepStop.CONSECUTIVE_FAILURES
                        else -> null
                    }
                }
            }
            completed++
            onProgress(completed, categories.size)
            if (stop != null) break
        }
        val skipped = categories.drop(completed)
        return CatalogSweepReport(
            total = categories.size,
            completed = completed,
            failures = failures.toList(),
            indexFailure = indexFailure,
            skipped = skipped,
            preserved = preserved,
            superseded = superseded,
            itemCount = items,
            forced = forced,
            stopReason = stop?.takeIf { skipped.isNotEmpty() },
            categories = categories,
        )
    }
}
