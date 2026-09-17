package com.iptv.player.data.repository

import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.util.AppError
import com.iptv.player.util.FakeSharedPreferences
import com.iptv.player.util.Outcome
import org.junit.Assert.assertEquals
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
        val failed = datasetSyncResult("epg", 0, Outcome.Failure(AppError.EMPTY_PLAYLIST))
        assertEquals(DatasetSyncStatus.FAILED, failed.status)
        assertEquals("EMPTY_PLAYLIST", failed.errorCode)
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
