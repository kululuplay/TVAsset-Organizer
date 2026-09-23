package com.iptv.player.data.repository

import com.iptv.player.util.AppError
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MovieCatalogRefreshTest {
    private val categories = listOf(
        SweepCategory("a", "First category"),
        SweepCategory("b", "Middle category"),
        SweepCategory("c", "New movies"),
    )
    private val five = ('a'..'e').map { SweepCategory("$it", "Category $it") }

    /** A category-level failure: the panel answered, this one category is simply gone. */
    private val failure = Outcome.Failure(AppError.SERVICE_NOT_FOUND, 404)
    private val failed = CategorySync.Failed(failure)
    /** A systemic failure: the panel itself is unreachable. */
    private val unreachable = Outcome.Failure(AppError.CANNOT_CONNECT)
    private val healthy = listOf(DatasetSyncResult("live", DatasetSyncStatus.UPDATED))

    private fun applied(count: Int = 1) = CategorySync.Applied(itemCount = count, newCount = count)

    @Test fun `failed middle category preserves later downloads and its display name`() = runBlocking {
        val visited = mutableListOf<String>()
        val written = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val report = CatalogSweep.run(categories, onProgress = { n, _ -> progress += n }) { id ->
            visited += id
            if (id == "b") failed else { written += id; applied() }
        }
        assertEquals(listOf("a", "b", "c"), visited)
        assertEquals(listOf("a", "c"), written)
        assertEquals(listOf(0, 1, 2, 3), progress)
        assertEquals("Middle category", report.failures.single().category.name)
        assertEquals(404, report.failures.single().failure.httpStatus)
        assertFalse(report.successful)
        assertTrue(report.skipped.isEmpty())
        assertEquals(listOf(categories[0], categories[2]), report.refreshed)
        assertEquals(listOf(categories[1]), report.unfinished)
        assertFalse(SyncReport(healthy, report).manualRefreshSucceeded)
    }

    @Test fun `all category-level failures are listed without skipping any category`() = runBlocking {
        val report = CatalogSweep.run(categories) { failed }
        assertEquals(categories, report.failures.map { it.category })
        assertEquals(3, report.completed)
        assertTrue(report.skipped.isEmpty())
        assertNull(report.stopReason)
        assertNull(report.systemicFailure)
        assertFalse(report.successful)
    }

    @Test fun `three consecutive failures stop the sweep and report the rest as skipped not failed`() = runBlocking {
        val visited = mutableListOf<String>()
        val report = CatalogSweep.run(five) { id -> visited += id; failed }
        assertEquals(listOf("a", "b", "c"), visited)
        assertEquals(3, report.failures.size)
        assertEquals(five.drop(3), report.skipped)
        assertEquals(SweepStop.CONSECUTIVE_FAILURES, report.stopReason)
        assertEquals(3, report.completed)
        assertEquals(5, report.total)
        assertFalse(report.successful)
        assertEquals(five, report.unfinished)
    }

    @Test fun `a success in between resets the consecutive failure count`() = runBlocking {
        val report = CatalogSweep.run(five) { id -> if (id == "c") applied() else failed }
        assertEquals(5, report.completed)
        assertEquals(4, report.failures.size)
        assertTrue(report.skipped.isEmpty())
        assertNull(report.stopReason)
    }

    @Test fun `a systemic failure stops the sweep after a single request`() = runBlocking {
        val visited = mutableListOf<String>()
        val report = CatalogSweep.run(categories) { id -> visited += id; CategorySync.Failed(unreachable) }
        assertEquals(listOf("a"), visited)
        assertEquals("First category", report.failures.single().category.name)
        assertEquals(categories.drop(1), report.skipped)
        assertEquals(SweepStop.SYSTEMIC_FAILURE, report.stopReason)
        assertEquals(unreachable, report.systemicFailure)
        assertEquals(1, report.completed)
        assertFalse(report.successful)
        assertEquals(categories, report.unfinished)
        assertTrue(report.refreshed.isEmpty())
    }

    @Test fun `a systemic failure on the last category leaves nothing skipped but is still reported`() = runBlocking {
        val report = CatalogSweep.run(categories) { id -> if (id == "c") CategorySync.Failed(unreachable) else applied() }
        assertEquals(3, report.completed)
        assertTrue(report.skipped.isEmpty())
        assertNull(report.stopReason)
        assertEquals(unreachable, report.systemicFailure)
    }

    @Test fun `unreachable refused and throttling panels are systemic while a missing category is not`() {
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.CANNOT_CONNECT)))
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.REQUEST_TIMEOUT)))
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.NO_INTERNET)))
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.SECURE_CONNECTION_FAILED)))
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.BAD_CREDENTIALS, 401)))
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.ACCESS_DENIED, 403)))
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.REQUEST_TIMEOUT, 408)))
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.TOO_MANY_REQUESTS, 429)))
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.SERVER_UNAVAILABLE, 503)))
        assertTrue(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.SUBSCRIPTION_EXPIRED)))
        assertFalse(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.SERVICE_NOT_FOUND, 404)))
        // HTTP 400 is one rejected request, not a dead panel, whatever it maps to.
        assertFalse(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.CANNOT_CONNECT, 400)))
        assertFalse(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.EMPTY_PLAYLIST)))
        assertFalse(CatalogSweepPolicy.isSystemic(Outcome.Failure(AppError.UNKNOWN)))
    }

    @Test fun `non-forced downloads pause after connectivity auth or throttling but never after a 5xx`() {
        assertEquals(60_000L, CatalogSweepPolicy.backoffMs(Outcome.Failure(AppError.CANNOT_CONNECT)))
        assertEquals(5L * 60_000L, CatalogSweepPolicy.backoffMs(Outcome.Failure(AppError.BAD_CREDENTIALS, 401)))
        assertEquals(15L * 60_000L, CatalogSweepPolicy.backoffMs(Outcome.Failure(AppError.TOO_MANY_REQUESTS, 429)))
        assertEquals(0L, CatalogSweepPolicy.backoffMs(Outcome.Failure(AppError.SERVER_UNAVAILABLE, 503)))
        assertEquals(0L, CatalogSweepPolicy.backoffMs(Outcome.Failure(AppError.SERVICE_NOT_FOUND, 404)))
    }

    @Test fun `a cache the refresh gate kept is unchanged not failed`() = runBlocking {
        val report = CatalogSweep.run(categories) { id ->
            if (id == "b") CategorySync.Preserved(DatasetRefreshPolicy.REASON_EMPTY) else applied()
        }
        assertTrue(report.successful)
        assertEquals(1, report.preserved)
        assertTrue(report.failures.isEmpty())
        assertEquals(categories, report.refreshed)
        assertTrue(SyncReport(healthy, report).manualRefreshSucceeded)
    }

    @Test fun `a superseded request is unchanged and resets the consecutive failure count`() = runBlocking {
        val report = CatalogSweep.run(five) { id -> if (id == "c") CategorySync.Superseded else failed }
        assertEquals(5, report.completed)
        assertEquals(1, report.superseded)
        assertEquals(4, report.failures.size)
        assertTrue(report.skipped.isEmpty())
    }

    @Test fun `item count counts new titles on a forced sweep and downloaded titles otherwise`() = runBlocking {
        val sync = CategorySync.Applied(itemCount = 10, newCount = 2)
        val forced = CatalogSweep.run(categories, forced = true) { sync }
        assertEquals(6, forced.itemCount)
        assertTrue(forced.forced)
        assertEquals(30, CatalogSweep.run(categories) { sync }.itemCount)
    }

    @Test fun `legacy outcome contract treats a kept cache and a superseded request as nothing new`() {
        assertEquals(Outcome.Success(2), CategorySync.Applied(10, 2).toOutcome(force = true))
        assertEquals(Outcome.Success(10), CategorySync.Applied(10, 2).toOutcome(force = false))
        assertEquals(Outcome.Success(0), CategorySync.Preserved("x").toOutcome(force = true))
        assertEquals(Outcome.Success(0), CategorySync.Superseded.toOutcome(force = false))
        assertEquals(failure, failed.toOutcome(force = true))
    }

    @Test fun `failed index still sweeps known categories and cannot report success`() = runBlocking {
        val visited = mutableListOf<String>()
        val report = CatalogSweep.run(categories, failure) { id -> visited += id; applied(2) }
        assertEquals(3, visited.size)
        assertEquals(failure, report.indexFailure)
        assertFalse(report.successful)
        assertFalse(SyncReport(healthy, report).manualRefreshSucceeded)
    }

    @Test fun `live and category index success alone do not mean movies refreshed`() {
        assertFalse(SyncReport(healthy).manualRefreshSucceeded)
        assertFalse(SyncReport(healthy, MovieCatalogRefreshReport(3, 2, emptyList())).manualRefreshSucceeded)
    }

    @Test fun `success is returned only after last movie response completes`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val task = async {
            CatalogSweep.run(categories) { id ->
                if (id == "c") { started.complete(Unit); release.await() }
                applied()
            }
        }
        started.await()
        assertFalse(task.isCompleted)
        release.complete(Unit)
        assertTrue(SyncReport(healthy, task.await()).manualRefreshSucceeded)
    }

    @Test fun `second forced sweep recovers failed categories without old errors`() = runBlocking {
        val first = CatalogSweep.run(categories) { if (it == "b") failed else applied() }
        assertFalse(first.successful)
        val retried = mutableListOf<String>()
        val second = CatalogSweep.run(categories) { retried += it; applied() }
        assertEquals(listOf("a", "b", "c"), retried)
        assertTrue(second.successful)
        assertTrue(second.failures.isEmpty())
    }

    @Test fun `empty accepted category responses are valid refreshes`() = runBlocking {
        val report = CatalogSweep.run(categories) { applied(0) }
        assertTrue(report.successful)
        assertEquals(3, report.completed)
    }

    @Test fun `empty cache with failed index must not claim success`() = runBlocking {
        val report = CatalogSweep.run(emptyList(), failure) { error("must not fetch") }
        assertFalse(report.successful)
    }

    @Test fun `cancellation stops work and does not become a success or partial report`() = runBlocking {
        val visited = mutableListOf<String>()
        try {
            CatalogSweep.run(categories) { id ->
                visited += id
                if (id == "b") throw CancellationException("cancelled")
                applied()
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("a", "b"), visited)
        }
    }

    @Test fun `movie success cannot hide a different dataset failure`() = runBlocking {
        val movies = CatalogSweep.run(categories) { applied() }
        val other = DatasetSyncResult("epg", DatasetSyncStatus.PRESERVED_CACHE)
        assertFalse(SyncReport(healthy + other, movies).manualRefreshSucceeded)
    }

    @Test fun `no eligible movie categories cannot claim that contents were fetched`() = runBlocking {
        val report = CatalogSweep.run(emptyList()) { error("must not fetch") }
        assertTrue(report.successful)
        assertFalse(SyncReport(healthy, report).manualRefreshSucceeded)
    }

    @Test fun `a live-only account is not applicable while an all-hidden account still is`() {
        val none = CatalogSweepReport(0, 0, emptyList(), availableCategories = 0)
        assertTrue(none.notApplicable)
        assertTrue(SyncReport(healthy, none).movieSweepNotApplicable)
        assertTrue(SyncReport(healthy, none).allRefreshSucceeded)
        assertFalse(SyncReport(healthy, none).manualRefreshSucceeded)
        val allHidden = CatalogSweepReport(0, 0, emptyList(), availableCategories = 12)
        assertFalse(allHidden.notApplicable)
        assertFalse(SyncReport(healthy, allHidden).movieSweepNotApplicable)
        assertFalse(SyncReport(healthy).movieSweepNotApplicable)
    }

    @Test fun `sweep dataset row reports titles added and partial or failed status`() = runBlocking {
        val partial = CatalogSweep.run(categories, forced = true) { id ->
            if (id == "b") failed else CategorySync.Applied(itemCount = 5, newCount = 2)
        }
        val row = sweepSyncResult("movies", partial)
        assertEquals(DatasetSyncStatus.PARTIAL, row.status)
        assertEquals(4, row.itemCount)
        assertEquals("HTTP_404", row.errorCode)
        assertEquals(AppError.SERVICE_NOT_FOUND, row.error)
        assertFalse(row.refreshed)
        assertFalse(SyncReport(healthy + row, partial).allRefreshSucceeded)

        val dead = sweepSyncResult("movies", CatalogSweep.run(categories) { CategorySync.Failed(unreachable) })
        assertEquals(DatasetSyncStatus.FAILED, dead.status)
        assertEquals(AppError.CANNOT_CONNECT, dead.error)

        val indexOnly = sweepSyncResult("movies", CatalogSweep.run(categories, unreachable) { applied() })
        assertEquals(DatasetSyncStatus.PARTIAL, indexOnly.status)
        assertEquals(AppError.CANNOT_CONNECT, indexOnly.error)

        val ok = sweepSyncResult("movies", CatalogSweep.run(categories) { applied(3) })
        assertEquals(DatasetSyncStatus.UPDATED, ok.status)
        assertEquals(9, ok.itemCount)
        assertNull(ok.error)
        assertEquals(DatasetSyncStatus.EMPTY, sweepSyncResult("movies", CatalogSweep.run(emptyList()) { error("no") }).status)
    }
}
