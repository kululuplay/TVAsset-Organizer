package com.iptv.player.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.iptv.player.R
import com.iptv.player.data.repository.CatalogSweepReport
import com.iptv.player.data.repository.SyncDatasets
import com.iptv.player.data.repository.SyncReport

/**
 * Scrollable and remote-navigable; category names and safe localized errors only.
 * The rows are built through [Text] so their layout is unit-tested without a Context.
 */
object MovieRefreshFeedback {
    /** Resolves a string resource with optional format arguments. */
    fun interface Text {
        fun get(@StringRes res: Int, vararg args: Any): String
    }

    fun show(
        context: Context,
        report: CatalogSweepReport,
        @StringRes titleRes: Int = R.string.catalog_refresh_incomplete,
    ) {
        val rows = sweepRows(report, text(context))
        if (rows.isEmpty()) return
        dialog(context, titleRes, rows)
    }

    /** Dashboard variant: names every dataset that did not refresh, then the movie sweep details. */
    fun show(context: Context, report: SyncReport) {
        val text = text(context)
        val rows = datasetRows(report, text) + (report.movieContents?.let { sweepRows(it, text) } ?: emptyList())
        if (rows.isEmpty()) return
        dialog(context, R.string.catalog_sync_incomplete, rows)
    }

    /** One line naming the first dataset that did not refresh, or null when every dataset did. */
    fun summary(report: SyncReport, text: Text): String? {
        val failed = report.failedDatasets.firstOrNull() ?: return null
        return text.get(
            R.string.catalog_dataset_failed,
            datasetLabel(failed.dataset, text),
            text.get(failed.error?.messageRes ?: R.string.error_unknown),
        )
    }

    fun summary(context: Context, report: SyncReport): String? = summary(report, text(context))

    /** Non-movie datasets that did not refresh; the movie sweep has its own rows. */
    fun datasetRows(report: SyncReport, text: Text): List<String> =
        report.failedDatasets
            .filterNot { it.dataset == SyncDatasets.MOVIES }
            .map { failed ->
                text.get(
                    R.string.catalog_dataset_failed,
                    datasetLabel(failed.dataset, text),
                    text.get(failed.error?.messageRes ?: R.string.error_unknown),
                )
            }

    /** Index failure, then every failed category, then how many were never reached. */
    fun sweepRows(
        report: CatalogSweepReport,
        text: Text,
        @StringRes indexFailedRes: Int = R.string.catalog_movie_index_failed,
    ): List<String> = buildList {
        report.indexFailure?.let {
            add(text.get(indexFailedRes) + "\n" + text.get(it.error.messageRes))
        }
        for (item in report.failures) {
            add(item.category.name + "\n" + text.get(item.failure.error.messageRes))
        }
        if (report.skipped.isNotEmpty()) {
            add(text.get(R.string.catalog_refresh_skipped, report.skipped.size))
        }
    }

    fun datasetLabel(dataset: String, text: Text): String = when (dataset) {
        SyncDatasets.LIVE -> text.get(R.string.nav_live)
        SyncDatasets.EPG -> text.get(R.string.nav_guide)
        SyncDatasets.VOD_CATEGORIES -> text.get(R.string.catalog_dataset_movie_categories)
        SyncDatasets.SERIES_CATEGORIES -> text.get(R.string.catalog_dataset_series_categories)
        SyncDatasets.MOVIES -> text.get(R.string.nav_movies)
        else -> dataset
    }

    private fun text(context: Context) = Text { res, args ->
        if (args.isEmpty()) context.getString(res) else context.getString(res, *args)
    }

    private fun dialog(context: Context, @StringRes titleRes: Int, rows: List<String>) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setItems(rows.toTypedArray()) { _, _ -> }
            .setPositiveButton(R.string.action_close, null)
            .show()
    }
}
