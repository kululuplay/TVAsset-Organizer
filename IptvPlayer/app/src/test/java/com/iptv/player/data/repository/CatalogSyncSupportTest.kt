package com.iptv.player.data.repository

import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.util.AppError
import com.iptv.player.util.FakeSharedPreferences
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

class CatalogSyncSupportTest {
    private val xtream = SourceConfig(type = SourceType.XTREAM, serverUrl = "http://a", username = "u", password = "p")
    private val other = xtream.copy(username = "other")
    private val noProgress: suspend (Int, Int) -> Unit = { _, _ -> }

    @Test
    fun `shrink rejection is recorded in the ledger and an accepted snapshot clears it`() {
        val ledger = ShrinkGuardLedger(FakeSharedPreferences())
        val gate = DatasetGenerationGate()
        val token = gate.begin("live", xtream)
        val shrink = DatasetSnapshot(existingCount = 100, receivedCount = 10, acceptedCount = 10)

        val first = evaluateRefreshWithLedger(ledger, CatalogDataset.LIVE, token, shrink, force = false, now = 1_000L)
        assertTrue(first is DatasetRefreshDecision.PreserveCache)
        assertEquals(ShrinkRejection(1, 1_000L), ledger.get("live"))

        val healthy = DatasetSnapshot(existingCount = 100, receivedCount = 100, acceptedCount = 100)
        assertTrue(evaluateRefreshWithLedger(ledger, CatalogDataset.LIVE, token, healthy, false, 2_000L) is DatasetRefreshDecision.Apply)
        assertNull(ledger.get("live"))
    }

    @Test
    fun `forced refresh applies a shrink without touching the ledger count`() {
        val ledger = ShrinkGuardLedger(FakeSharedPreferences())
        val token = DatasetGenerationGate().begin("live", xtream)
        val shrink = DatasetSnapshot(existingCount = 100, receivedCount = 10, acceptedCount = 10)
        assertTrue(evaluateRefreshWithLedger(ledger, CatalogDataset.LIVE, token, shrink, force = true, now = 5L) is DatasetRefreshDecision.Apply)
        assertNull(ledger.get("live"))
    }

    @Test
    fun `forced empty replies of a listed category are counted apart from shrinks and accepted on the third`() {
        val ledger = ShrinkGuardLedger(FakeSharedPreferences())
        val token = DatasetGenerationGate().begin("vod_category:7", xtream)
        val emptyKey = emptyResponseKey("vod_category:7")
        val empty = DatasetSnapshot(existingCount = 40, receivedCount = 0, acceptedCount = 0)

        // A background check or an unlisted category keeps the cache and counts nothing.
        assertTrue(evaluateRefreshWithLedger(ledger, CatalogDataset.VOD_CATEGORY, token, empty, force = false, now = 1L, stillListed = true) is DatasetRefreshDecision.PreserveCache)
        assertTrue(evaluateRefreshWithLedger(ledger, CatalogDataset.VOD_CATEGORY, token, empty, force = true, now = 2L) is DatasetRefreshDecision.PreserveCache)
        assertNull(ledger.get(emptyKey))

        assertTrue(evaluateRefreshWithLedger(ledger, CatalogDataset.VOD_CATEGORY, token, empty, force = true, now = 3L, stillListed = true) is DatasetRefreshDecision.PreserveCache)
        assertTrue(evaluateRefreshWithLedger(ledger, CatalogDataset.VOD_CATEGORY, token, empty, force = true, now = 4L, stillListed = true) is DatasetRefreshDecision.PreserveCache)
        assertEquals(ShrinkRejection(2, 3L), ledger.get(emptyKey))
        assertNull(ledger.get("vod_category:7"))

        // Third consecutive manual empty reply: accepted, and the count is cleared.
        assertTrue(evaluateRefreshWithLedger(ledger, CatalogDataset.VOD_CATEGORY, token, empty, force = true, now = 5L, stillListed = true) is DatasetRefreshDecision.Apply)
        assertNull(ledger.get(emptyKey))

        // A non-empty reply in between resets the count.
        evaluateRefreshWithLedger(ledger, CatalogDataset.VOD_CATEGORY, token, empty, force = true, now = 6L, stillListed = true)
        assertEquals(1, ledger.get(emptyKey)?.count)
        val healthy = DatasetSnapshot(existingCount = 40, receivedCount = 38, acceptedCount = 38)
        assertTrue(evaluateRefreshWithLedger(ledger, CatalogDataset.VOD_CATEGORY, token, healthy, force = true, now = 7L, stillListed = true) is DatasetRefreshDecision.Apply)
        assertNull(ledger.get(emptyKey))
    }

    @Test
    fun `source switch zeroes the existing count the policy sees`() {
        val gate = DatasetGenerationGate()
        val same = gate.begin("live", xtream)
        assertEquals(42, same.policyExistingCount(42))
        val switched = gate.begin("live", xtream.copy(username = "other"))
        assertEquals(0, switched.policyExistingCount(42))
        assertTrue(gate.isCurrent(switched))
        assertTrue(!gate.isCurrent(same))
    }

    @Test
    fun `dataset sync result distinguishes empty updated preserved and failed`() {
        assertEquals(DatasetSyncStatus.EMPTY, datasetSyncResult("live", 0, Outcome.Success(0)).status)
        assertEquals(DatasetSyncStatus.UPDATED, datasetSyncResult("live", 0, Outcome.Success(5)).status)
        assertEquals(DatasetSyncStatus.UPDATED, datasetSyncResult("live", 5, Outcome.Success(0)).status)
        val preserved = datasetSyncResult("epg", 12, Outcome.Failure(AppError.CANNOT_CONNECT, httpStatus = 503))
        assertEquals(DatasetSyncStatus.PRESERVED_CACHE, preserved.status)
        assertEquals(12, preserved.itemCount)
        assertEquals("HTTP_503", preserved.errorCode)
        assertEquals(AppError.CANNOT_CONNECT, preserved.error)
        val failed = datasetSyncResult("epg", 0, Outcome.Failure(AppError.EMPTY_PLAYLIST))
        assertEquals(DatasetSyncStatus.FAILED, failed.status)
        assertEquals("EMPTY_PLAYLIST", failed.errorCode)
        assertEquals(AppError.EMPTY_PLAYLIST, failed.error)
        assertNull(datasetSyncResult("live", 0, Outcome.Success(5)).error)
        assertEquals(listOf(preserved, failed), SyncReport(listOf(datasetSyncResult("live", 0, Outcome.Success(5)), preserved, failed)).failedDatasets)
    }

    // ---- shared in-flight work ------------------------------------------

    @Test
    fun `a second caller for the same key joins the running download instead of starting another`() = runBlocking {
        val work = SharedWork<String, Int>(CoroutineScope(Dispatchers.Default))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val first = async {
            work.join("vod_category:1", xtream) { runs++; started.complete(Unit); release.await(); 7 }
        }
        started.await()
        assertTrue(work.isRunning("vod_category:1"))
        val second = async { work.join("vod_category:1", xtream) { runs++; 99 } }
        yield() // let the second caller register against the running download
        release.complete(Unit)
        assertEquals(7, first.await())
        assertEquals(7, second.await())
        assertEquals(1, runs)
        assertFalse(work.isRunning("vod_category:1"))
        // Once finished, the next caller downloads afresh.
        assertEquals(99, work.join("vod_category:1", xtream) { runs++; 99 })
        assertEquals(2, runs)
    }

    @Test
    fun `an abandoned caller does not cancel the download another caller waits for`() = runBlocking {
        val work = SharedWork<String, Int>(CoroutineScope(Dispatchers.Default))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { work.join("k", xtream) { started.complete(Unit); release.await(); 7 } }
        started.await()
        val second = async { work.join("k", xtream) { 99 } }
        yield()
        first.cancel()
        release.complete(Unit)
        assertEquals(7, second.await())
    }

    @Test
    fun `a caller for a different source waits for the running download and then runs its own`() = runBlocking {
        val work = SharedWork<String, Int>(CoroutineScope(Dispatchers.Default))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { work.join("k", xtream) { started.complete(Unit); release.await(); 7 } }
        started.await()
        val second = async { work.join("k", other) { 99 } }
        yield()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals(7, first.await())
        assertEquals(99, second.await())
    }

    @Test
    fun `different keys download independently`() = runBlocking {
        val work = SharedWork<String, Int>(CoroutineScope(Dispatchers.Default))
        val a = async { work.join("a", xtream) { 1 } }
        val b = async { work.join("b", xtream) { 2 } }
        assertEquals(1, a.await())
        assertEquals(2, b.await())
    }

    // ---- shared sweep ---------------------------------------------------

    @Test
    fun `a concurrent caller follows the running sweep and receives its report`() = runBlocking {
        val sweeps = SharedSweep(CoroutineScope(Dispatchers.Default))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val report = CatalogSweepReport(2, 2, emptyList(), forced = true)
        var runs = 0
        val first = async {
            sweeps.run(xtream, SweepRequest(forceAll = true), noProgress) { progress ->
                runs++
                progress(1, 2)
                started.complete(Unit)
                release.await()
                progress(2, 2)
                report
            }
        }
        started.await()
        assertTrue(sweeps.isRunning)
        val seen = mutableListOf<Pair<Int, Int>>()
        val second = async {
            sweeps.run(xtream, SweepRequest(forceAll = false), { completed, total -> seen += completed to total }) {
                error("must follow the running sweep")
            }
        }
        yield()
        release.complete(Unit)
        assertEquals(report, first.await())
        assertEquals(report, second.await())
        assertEquals(1, runs)
        // Progress is a state (latest value), so a follower that subscribes late may
        // first see the final step; it must always end on the sweep's last progress.
        assertTrue(seen.isNotEmpty())
        assertEquals(2 to 2, seen.last())
        assertFalse(sweeps.isRunning)
    }

    @Test
    fun `a forced request after a non-forced sweep waits for it and then runs its own`() = runBlocking {
        val sweeps = SharedSweep(CoroutineScope(Dispatchers.Default))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lazy = CatalogSweepReport(1, 1, emptyList())
        val forced = CatalogSweepReport(3, 3, emptyList(), forced = true)
        val first = async {
            sweeps.run(xtream, SweepRequest(forceAll = false), noProgress) { started.complete(Unit); release.await(); lazy }
        }
        started.await()
        val second = async { sweeps.run(xtream, SweepRequest(forceAll = true), noProgress) { forced } }
        yield()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals(lazy, first.await())
        assertEquals(forced, second.await())
    }

    @Test
    fun `a running sweep serves requests it covers`() {
        val full = SweepRequest(forceAll = true)
        val lazyFull = SweepRequest(forceAll = false)
        val retry = SweepRequest(forceAll = true, only = setOf("b", "c"))
        assertTrue(full.covers(lazyFull))
        assertTrue(full.covers(retry))
        assertTrue(full.covers(full))
        assertFalse(lazyFull.covers(full))
        assertTrue(lazyFull.covers(SweepRequest(forceAll = false, only = setOf("b"))))
        assertFalse(retry.covers(full))
        assertTrue(retry.covers(SweepRequest(forceAll = false, only = setOf("c"))))
        assertFalse(retry.covers(SweepRequest(forceAll = true, only = setOf("a"))))
    }

    // ---- post-failure backoff ---------------------------------------------

    @Test
    fun `non-forced downloads pause after a systemic failure until the window passes or a download succeeds`() {
        val backoff = CatalogBackoff()
        assertEquals(0L, backoff.pauseAfter(Outcome.Failure(AppError.SERVER_UNAVAILABLE, 503), now = 1_000L))
        assertNull(backoff.activeFailure(1_000L))

        val timeout = Outcome.Failure(AppError.REQUEST_TIMEOUT)
        assertEquals(60_000L, backoff.pauseAfter(timeout, now = 1_000L))
        assertEquals(timeout, backoff.activeFailure(60_999L))
        assertNull(backoff.activeFailure(61_000L))

        val throttled = Outcome.Failure(AppError.TOO_MANY_REQUESTS, 429)
        assertEquals(15L * 60_000L, backoff.pauseAfter(throttled, now = 0L))
        assertEquals(throttled, backoff.activeFailure(14L * 60_000L))
        backoff.clear()
        assertNull(backoff.activeFailure(1L))

        val dead = CatalogSweepReport(
            total = 3, completed = 1,
            failures = listOf(SweepCategoryFailure(SweepCategory("a", "A"), timeout)),
            skipped = listOf(SweepCategory("b", "B"), SweepCategory("c", "C")),
        )
        assertEquals(60_000L, backoff.noteSweep(dead, now = 5L))
        assertEquals(timeout, backoff.activeFailure(6L))
        backoff.clear()
        val oneBroken = CatalogSweepReport(
            total = 3, completed = 3,
            failures = listOf(SweepCategoryFailure(SweepCategory("a", "A"), Outcome.Failure(AppError.SERVICE_NOT_FOUND, 404))),
        )
        assertEquals(0L, backoff.noteSweep(oneBroken, now = 5L))
        assertNull(backoff.activeFailure(6L))
    }

    @Test
    fun `fts query tokenizes and prefixes each term`() {
        assertEquals("name:harry* name:pot*", toFtsQuery("  Harry pot "))
        assertEquals("name:xt* name:live* name:12*", toFtsQuery("xt_live_12"))
        assertEquals("", toFtsQuery("  --- "))
    }

    // toAppError()/toOutcomeFailure() log the throwable through android.util.Log,
    // whose unit-test stub returns null for getStackTraceString, so only the pure
    // cause traversal they build on is asserted here.
    @Test
    fun `cause chain is bounded and stops at a self referencing cause`() {
        val http = HttpException(Response.error<Any>(429, "".toResponseBody()))
        val wrapped = RuntimeException("wrapped", SocketTimeoutException().initCause(http))
        assertEquals(listOf(wrapped, wrapped.cause, http), wrapped.causeChain().toList())
        assertEquals(http, wrapped.causeChain().filterIsInstance<HttpException>().first())

        var deep: Throwable = IOException("leaf")
        repeat(20) { deep = RuntimeException("layer $it", deep) }
        assertEquals(8, deep.causeChain().count())

        val self = RuntimeException("self")
        assertEquals(listOf<Throwable>(self), self.causeChain().toList())
    }

    @Test
    fun `youtube helper accepts ids and full urls`() {
        assertNull(youtube(null))
        assertNull(youtube("  "))
        assertEquals("https://www.youtube.com/watch?v=abc", youtube(" abc "))
        assertEquals("https://youtu.be/abc", youtube("https://youtu.be/abc"))
    }
}
