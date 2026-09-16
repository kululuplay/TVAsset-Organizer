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
        MovieRefreshCategory("a", "First category"),
        MovieRefreshCategory("b", "Middle category"),
        MovieRefreshCategory("c", "New movies"),
    )
    private val failure = Outcome.Failure(AppError.SERVER_UNAVAILABLE, 503)
    private val healthy = listOf(DatasetSyncResult("live", DatasetSyncStatus.UPDATED))

    @Test fun `failed middle category preserves later downloads and its display name`() = runBlocking {
        val visited = mutableListOf<String>()
        val written = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val report = MovieCatalogRefresh.run(categories, onProgress = { n, _ -> progress += n }) { id ->
            visited += id
            if (id == "b") failure else { written += id; Outcome.Success(1) }
        }
        assertEquals(listOf("a", "b", "c"), visited)
        assertEquals(listOf("a", "c"), written)
        assertEquals(listOf(0, 1, 2, 3), progress)
        assertEquals("Middle category", report.failures.single().category.name)
        assertEquals(503, report.failures.single().failure.httpStatus)
        assertFalse(report.successful)
        assertFalse(SyncReport(healthy, report).manualRefreshSucceeded)
    }

    @Test fun `all failures are listed without skipping any category`() = runBlocking {
        val report = MovieCatalogRefresh.run(categories) { failure }
        assertEquals(categories, report.failures.map { it.category })
        assertEquals(3, report.completed)
        assertFalse(report.successful)
    }

    @Test fun `failed index still sweeps known categories and cannot report success`() = runBlocking {
        val visited = mutableListOf<String>()
        val report = MovieCatalogRefresh.run(categories, failure) { id -> visited += id; Outcome.Success(2) }
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
            MovieCatalogRefresh.run(categories) { id ->
                if (id == "c") { started.complete(Unit); release.await() }
                Outcome.Success(1)
            }
        }
        started.await()
        assertFalse(task.isCompleted)
        release.complete(Unit)
        assertTrue(SyncReport(healthy, task.await()).manualRefreshSucceeded)
    }

    @Test fun `second forced sweep recovers failed categories without old errors`() = runBlocking {
        val first = MovieCatalogRefresh.run(categories) { if (it == "b") failure else Outcome.Success(1) }
        assertFalse(first.successful)
        val retried = mutableListOf<String>()
        val second = MovieCatalogRefresh.run(categories) { retried += it; Outcome.Success(1) }
        assertEquals(listOf("a", "b", "c"), retried)
        assertTrue(second.successful)
        assertTrue(second.failures.isEmpty())
    }

    @Test fun `empty accepted category responses are valid refreshes`() = runBlocking {
        val report = MovieCatalogRefresh.run(categories) { Outcome.Success(0) }
        assertTrue(report.successful)
        assertEquals(3, report.completed)
    }

    @Test fun `empty cache with failed index must not claim success`() = runBlocking {
        val report = MovieCatalogRefresh.run(emptyList(), failure) { error("must not fetch") }
        assertFalse(report.successful)
    }

    @Test fun `cancellation stops work and does not become a success or partial report`() = runBlocking {
        val visited = mutableListOf<String>()
        try {
            MovieCatalogRefresh.run(categories) { id ->
                visited += id
                if (id == "b") throw CancellationException("cancelled")
                Outcome.Success(1)
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("a", "b"), visited)
        }
    }

    @Test fun `movie success cannot hide a different dataset failure`() = runBlocking {
        val movies = MovieCatalogRefresh.run(categories) { Outcome.Success(1) }
        val other = DatasetSyncResult("epg", DatasetSyncStatus.PRESERVED_CACHE)
        assertFalse(SyncReport(healthy + other, movies).manualRefreshSucceeded)
    }
    @Test fun `no eligible movie categories cannot claim that contents were fetched`() = runBlocking {
        val report = MovieCatalogRefresh.run(emptyList()) { error("must not fetch") }
        assertTrue(report.successful)
        assertFalse(SyncReport(healthy, report).manualRefreshSucceeded)
    }
}
