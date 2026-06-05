/*
 * GridExtensions.kt
 * Resolution-independent grid sizing. Instead of a hard-coded span count (which
 * leaves gaps on 4K and can overflow on 720p), the column count is derived from
 * the grid's own measured width so the poster grid stays proportional and fills
 * the screen at every TV resolution (720p / 1080p / 4K) and aspect ratio.
 */
package com.iptv.player.ui.common

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import kotlin.math.max

/**
 * Recompute this grid's [GridLayoutManager.spanCount] from its measured width.
 * Each column targets [itemWidthDimen] (default poster width); at least [min]
 * columns are always shown so narrow panels (e.g. the search columns) stay sane.
 *
 * Safe to call once after the layout manager is set; it waits for the first
 * layout pass (via [post]) so the real width is known.
 */
fun RecyclerView.autoFitColumns(
    itemWidthDimen: Int = R.dimen.poster_width,
    min: Int = 2
) {
    val grid = layoutManager as? GridLayoutManager ?: return
    val itemPx = resources.getDimensionPixelSize(itemWidthDimen)
    val gapPx = resources.getDimensionPixelSize(R.dimen.space_xs) * 2
    post {
        val available = width - paddingLeft - paddingRight
        if (available <= 0 || itemPx <= 0) return@post
        val span = max(min, available / (itemPx + gapPx))
        if (grid.spanCount != span) {
            grid.spanCount = span
            requestLayout()
        }
    }
}
