package com.iptv.player.ui.common

import com.iptv.player.R
import com.iptv.player.data.repository.CatalogSweepReport
import com.iptv.player.data.repository.DatasetSyncResult
import com.iptv.player.data.repository.DatasetSyncStatus
import com.iptv.player.data.repository.SweepCategory
import com.iptv.player.data.repository.SweepCategoryFailure
import com.iptv.player.data.repository.SyncReport
import com.iptv.player.util.AppError
import com.iptv.player.util.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieRefreshFeedbackTest {
    /** Renders "s<resId>" plus "[args]" so the row layout is asserted without Android resources. */
    private val text = MovieRefreshFeedback.Text { res, args ->
        "s$res" + if (args.isEmpty()) "" else args.joinToString(",", "[", "]")
    }
    private fun s(res: Int) = "s$res"

    @Test
    fun `sweep rows list the index failure then each failed category then the skipped count`() {
        val report = CatalogSweepReport(
            total = 4,
            completed = 2,
            failures = listOf(SweepCategoryFailure(SweepCategory("b", "Action"), Outcome.Failure(AppError.SERVICE_NOT_FOUND, 404))),
            indexFailure = Outcome.Failure(AppError.CANNOT_CONNECT),
            skipped = listOf(SweepCategory("c", "Drama"), SweepCategory("d", "Kids")),
        )
        val rows = MovieRefreshFeedback.sweepRows(report, text)
        assertEquals(
            listOf(
                s(R.string.catalog_movie_index_failed) + "\n" + s(AppError.CANNOT_CONNECT.messageRes),
                "Action\n" + s(AppError.SERVICE_NOT_FOUND.messageRes),
                s(R.string.catalog_refresh_skipped) + "[2]",
            ),
            rows,
        )
        assertTrue(MovieRefreshFeedback.sweepRows(CatalogSweepReport(3, 3, emptyList()), text).isEmpty())
    }

    @Test
    fun `summary names the first dataset that did not refresh with its own error`() {
        val report = SyncReport(
            listOf(
                DatasetSyncResult("live", DatasetSyncStatus.UPDATED),
                DatasetSyncResult("epg", DatasetSyncStatus.PRESERVED_CACHE, error = AppError.REQUEST_TIMEOUT),
                DatasetSyncResult("movies", DatasetSyncStatus.FAILED, error = AppError.CANNOT_CONNECT),
            ),
        )
        assertEquals(
            s(R.string.catalog_dataset_failed) + "[" + s(R.string.nav_guide) + "," + s(AppError.REQUEST_TIMEOUT.messageRes) + "]",
            MovieRefreshFeedback.summary(report, text),
        )
        assertNull(MovieRefreshFeedback.summary(SyncReport(listOf(DatasetSyncResult("live", DatasetSyncStatus.UPDATED))), text))
    }

    @Test
    fun `dataset rows leave the movie sweep to its own rows and never crash on an unknown error`() {
        val report = SyncReport(
            listOf(
                DatasetSyncResult("live", DatasetSyncStatus.FAILED),
                DatasetSyncResult("vod_categories", DatasetSyncStatus.PRESERVED_CACHE, error = AppError.EMPTY_PLAYLIST),
                DatasetSyncResult("movies", DatasetSyncStatus.PARTIAL, error = AppError.SERVICE_NOT_FOUND),
            ),
        )
        assertEquals(
            listOf(
                s(R.string.catalog_dataset_failed) + "[" + s(R.string.nav_live) + "," + s(R.string.error_unknown) + "]",
                s(R.string.catalog_dataset_failed) + "[" + s(R.string.catalog_dataset_movie_categories) + "," + s(AppError.EMPTY_PLAYLIST.messageRes) + "]",
            ),
            MovieRefreshFeedback.datasetRows(report, text),
        )
    }

    @Test
    fun `every sync dataset has a label and unknown names fall back to themselves`() {
        assertEquals(s(R.string.nav_live), MovieRefreshFeedback.datasetLabel("live", text))
        assertEquals(s(R.string.nav_guide), MovieRefreshFeedback.datasetLabel("epg", text))
        assertEquals(s(R.string.catalog_dataset_movie_categories), MovieRefreshFeedback.datasetLabel("vod_categories", text))
        assertEquals(s(R.string.catalog_dataset_series_categories), MovieRefreshFeedback.datasetLabel("series_categories", text))
        assertEquals(s(R.string.nav_movies), MovieRefreshFeedback.datasetLabel("movies", text))
        assertEquals("weather", MovieRefreshFeedback.datasetLabel("weather", text))
    }
}
