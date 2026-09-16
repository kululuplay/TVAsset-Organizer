package com.iptv.player.ui.common

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.iptv.player.R
import com.iptv.player.data.repository.MovieCatalogRefreshReport

/** Scrollable and remote-navigable; category names and safe localized errors only. */
object MovieRefreshFeedback {
    fun show(context: Context, report: MovieCatalogRefreshReport) {
        val rows = buildList {
            report.indexFailure?.let {
                add(context.getString(R.string.catalog_movie_index_failed) + "\n" +
                    context.getString(it.error.messageRes))
            }
            for (item in report.failures) {
                add(item.category.name + "\n" + context.getString(item.failure.error.messageRes))
            }
        }
        if (rows.isEmpty()) return
        AlertDialog.Builder(context)
            .setTitle(R.string.catalog_refresh_incomplete)
            .setItems(rows.toTypedArray()) { _, _ -> }
            .setPositiveButton(R.string.action_close, null)
            .show()
    }
}
