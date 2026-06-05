/*
 * SkeletonHelper.kt
 * Show/hide a shimmering skeleton placeholder while a list (or any view) is
 * loading. It overlays the target's parent with a stack of shimmer rows and
 * removes them when content arrives. No external libraries.
 *
 * Usage:
 *   val skeleton = SkeletonHelper(recyclerView)
 *   skeleton.show()                 // before/while loading
 *   ...load data...
 *   skeleton.hide()                 // after data is bound
 */
package com.iptv.player.ui.common

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

class SkeletonHelper(
    private val target: View,
    private val rowCount: Int = 8,
    private val rowHeightDp: Int = 64
) {

    private var overlay: View? = null
    private val drawables = mutableListOf<ShimmerDrawable>()

    fun show() {
        if (overlay != null) return
        val parent = target.parent as? ViewGroup ?: return
        val context = target.context
        val density = context.resources.displayMetrics.density
        val rowH = (rowHeightDp * density).toInt()
        val margin = (8 * density).toInt()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(margin, margin, margin, margin)
        }

        repeat(rowCount) {
            val row = View(context)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowH
            ).apply { bottomMargin = margin }
            row.layoutParams = lp
            val shimmer = ShimmerDrawable.default()
            row.background = shimmer
            shimmer.start()
            drawables.add(shimmer)
            column.addView(row)
        }

        // Match the target's position within its parent so the overlay sits on top.
        val index = parent.indexOfChild(target)
        parent.addView(column, index + 1)
        overlay = column
    }

    fun hide() {
        drawables.forEach { it.stop() }
        drawables.clear()
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }

    val isShowing: Boolean get() = overlay != null
}
