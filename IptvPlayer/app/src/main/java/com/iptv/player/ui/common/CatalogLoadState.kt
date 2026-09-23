package com.iptv.player.ui.common

import androidx.annotation.StringRes
import com.iptv.player.data.repository.CatalogSweepReport

/**
 * Network state for lazy movie/series catalog hydration. [total] is greater than
 * one when an All/Recommended/search view is completing the whole visible
 * catalog; a regular category load uses a one-item progress state.
 */
data class CatalogLoadState(
    val loading: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    @StringRes val errorRes: Int? = null,
    /** The last whole-catalog sweep, kept so Retry can redo only what it did not finish. */
    val sweepReport: CatalogSweepReport? = null,
)

/** What the Retry button should redo on a catalog screen. */
sealed interface CatalogRetry {
    /** Redo the unfinished part of the last sweep with the same force; null ids = every eligible category. */
    data class Sweep(val forced: Boolean, val only: Set<String>?) : CatalogRetry

    /** Re-download one category, forced. */
    data class Category(val id: String) : CatalogRetry

    /** Complete the visible catalog without forcing (never-loaded categories only). */
    data object FullCatalog : CatalogRetry
}

/**
 * Retry redoes only what failed: the unfinished categories of a failed sweep,
 * the visible category, or the non-forced catalog completion. The full forced
 * sweep stays reserved for the explicit Refresh button.
 */
object CatalogRetryPolicy {
    fun decide(
        state: CatalogLoadState,
        query: String,
        selectedCategoryId: String?,
        virtualCategories: Set<String>,
    ): CatalogRetry {
        val report = state.sweepReport?.takeIf { !it.successful }
        if (report != null) {
            val unfinished = report.unfinished.mapTo(LinkedHashSet()) { it.id }
            return CatalogRetry.Sweep(report.forced, unfinished.takeIf { it.isNotEmpty() })
        }
        if (query.isNotEmpty() || selectedCategoryId == null || selectedCategoryId in virtualCategories) {
            return CatalogRetry.FullCatalog
        }
        return CatalogRetry.Category(selectedCategoryId)
    }
}
