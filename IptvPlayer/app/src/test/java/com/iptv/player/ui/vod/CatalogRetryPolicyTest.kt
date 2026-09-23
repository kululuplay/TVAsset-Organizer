package com.iptv.player.ui.vod

import com.iptv.player.data.repository.CatalogSweepReport
import com.iptv.player.data.repository.SweepCategory
import com.iptv.player.data.repository.SweepCategoryFailure
import com.iptv.player.ui.common.CatalogLoadState
import com.iptv.player.ui.common.CatalogRetry
import com.iptv.player.ui.common.CatalogRetryPolicy
import com.iptv.player.util.AppError
import com.iptv.player.util.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRetryPolicyTest {
    private val virtual = setOf(VodViewModel.CAT_ALL, VodViewModel.CAT_POPULAR)
    private val failure = Outcome.Failure(AppError.SERVICE_NOT_FOUND, 404)
    private val categories = listOf(SweepCategory("a", "A"), SweepCategory("b", "B"), SweepCategory("c", "C"))

    @Test
    fun `a failed sweep retries only its unfinished categories with the same force`() {
        val report = CatalogSweepReport(
            total = 3,
            completed = 2,
            failures = listOf(SweepCategoryFailure(categories[1], failure)),
            skipped = listOf(categories[2]),
            forced = true,
            categories = categories,
        )
        val state = CatalogLoadState(errorRes = 1, sweepReport = report)
        assertEquals(CatalogRetry.Sweep(forced = true, only = setOf("b", "c")), CatalogRetryPolicy.decide(state, "", VodViewModel.CAT_ALL, virtual))
        // The same decision stands whichever view showed the sweep error.
        assertEquals(CatalogRetry.Sweep(forced = true, only = setOf("b", "c")), CatalogRetryPolicy.decide(state, "harry", null, virtual))
        assertEquals(CatalogRetry.Sweep(forced = true, only = setOf("b", "c")), CatalogRetryPolicy.decide(state, "", "7", virtual))
    }

    @Test
    fun `an index-only failure retries the whole eligible set without adding force`() {
        val report = CatalogSweepReport(3, 3, emptyList(), indexFailure = Outcome.Failure(AppError.CANNOT_CONNECT), categories = categories)
        val state = CatalogLoadState(errorRes = 1, sweepReport = report)
        assertEquals(CatalogRetry.Sweep(forced = false, only = null), CatalogRetryPolicy.decide(state, "", VodViewModel.CAT_ALL, virtual))
    }

    @Test
    fun `a successful sweep report never triggers a sweep retry`() {
        val state = CatalogLoadState(sweepReport = CatalogSweepReport(3, 3, emptyList(), categories = categories))
        assertEquals(CatalogRetry.Category("7"), CatalogRetryPolicy.decide(state, "", "7", virtual))
        assertEquals(CatalogRetry.FullCatalog, CatalogRetryPolicy.decide(state, "", VodViewModel.CAT_POPULAR, virtual))
    }

    @Test
    fun `search and virtual categories complete the catalog without forcing`() {
        val state = CatalogLoadState(errorRes = 1)
        assertEquals(CatalogRetry.FullCatalog, CatalogRetryPolicy.decide(state, "harry", "7", virtual))
        assertEquals(CatalogRetry.FullCatalog, CatalogRetryPolicy.decide(state, "", VodViewModel.CAT_ALL, virtual))
        assertEquals(CatalogRetry.FullCatalog, CatalogRetryPolicy.decide(state, "", VodViewModel.CAT_POPULAR, virtual))
        assertEquals(CatalogRetry.FullCatalog, CatalogRetryPolicy.decide(state, "", null, virtual))
    }

    @Test
    fun `a real category retries itself forced`() {
        assertEquals(CatalogRetry.Category("7"), CatalogRetryPolicy.decide(CatalogLoadState(errorRes = 1), "", "7", virtual))
    }
}
