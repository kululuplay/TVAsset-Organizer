/*
 * HomeRowFocuser.kt
 *
 * Architecture note (Home screen decomposition):
 *   The shared "land D-pad focus on a specific RecyclerView row" mechanism of
 *   HomeActivity. Focuses the row at a position, retrying (bounded) until the
 *   view holder is laid out. When a pane was just toggled GONE->VISIBLE the
 *   relayout is still pending, so a single post() finds no holder and the old
 *   fallback to list.requestFocus() landed on the FIRST visible child, snapping
 *   focus back to the top category. Re-posting until the target holder exists
 *   lands focus on the correct row. Requests are generation-stamped so a
 *   mode/layout change can invalidate delayed ones; adapter lookups (stable id
 *   per position, position per stable id, which lists may take focus) stay in
 *   the Activity via [Host].
 *
 * Moved verbatim from HomeActivity.
 */
package com.iptv.player.ui.home

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

internal class HomeRowFocuser(private val host: Host) {

    interface Host {
        /** Stable item id at [pos] for adapters that support it, else null. */
        fun stableIdFor(list: RecyclerView, pos: Int): String?
        /** Current position of [stableId], or null when the list is not id-addressable. */
        fun positionFor(list: RecyclerView, stableId: String?): Int?
        /** False while another mode (inline fullscreen) owns focus for this list. */
        fun listAllowed(list: RecyclerView): Boolean
    }

    /** Invalidates delayed RecyclerView focus requests after a mode/layout change. */
    private var generation = 0

    fun invalidate() {
        generation++
    }

    fun focusRow(list: RecyclerView, pos: Int, attempts: Int = 10, center: Boolean = false) {
        val generation = ++this.generation
        val stableId = host.stableIdFor(list, pos)
        list.scrollToPosition(pos)
        list.post(object : Runnable {
            private var remaining = attempts
            override fun run() {
                if (
                    generation != this@HomeRowFocuser.generation ||
                    !list.isShown ||
                    !host.listAllowed(list)
                ) return
                val resolvedPosition = host.positionFor(list, stableId) ?: pos
                if (resolvedPosition < 0) {
                    if ((list.adapter?.itemCount ?: 0) > 0) focusRow(list, 0, center = false)
                    return
                }
                val holder = list.findViewHolderForAdapterPosition(resolvedPosition)
                when {
                    holder != null -> {
                        // Center the target row in the viewport when asked, so the
                        // restored/playing channel sits mid-list with rows visible
                        // above and below it (not pinned to the top edge).
                        if (center) {
                            (list.layoutManager as? LinearLayoutManager)?.let { lm ->
                                val offset = (list.height - holder.itemView.height) / 2
                                lm.scrollToPositionWithOffset(
                                    resolvedPosition,
                                    offset.coerceAtLeast(0),
                                )
                            }
                        }
                        holder.itemView.requestFocus()
                    }
                    remaining-- > 0 -> {
                        list.scrollToPosition(resolvedPosition)
                        list.post(this)
                    }
                    else -> list.requestFocus()
                }
            }
        })
    }
}
