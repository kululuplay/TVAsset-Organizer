/*
 * SafeGridLayoutManager.kt
 * A GridLayoutManager that survives the "Inconsistency detected. Invalid item
 * position" crash that RecyclerView throws when a Paging3 PagingSource is
 * invalidated mid-layout. On Android TV this happens when the user HOLDS a D-pad
 * key (fast key-repeat scroll) while a background Room write (content sync /
 * lazy prefetch) invalidates the source: the diff/notify lands during a layout
 * pass, RecyclerView's cached item count no longer matches the adapter, and the
 * uncaught IndexOutOfBoundsException kills the process -> the launcher relaunches
 * the app at the Dashboard, so the user appears to be "thrown back to home".
 *
 * Swallowing the exception in onLayoutChildren lets RecyclerView recover on the
 * very next layout pass (the data is already consistent again by then) instead
 * of crashing. Predictive item animations are disabled because they are the path
 * that most often trips this inconsistency during rapid scrolling.
 */
package com.iptv.player.ui.common

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SafeGridLayoutManager(
    context: Context,
    spanCount: Int
) : GridLayoutManager(context, spanCount) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            // Transient adapter/layout inconsistency from a mid-layout Paging
            // invalidation. The next layout pass renders the consistent state.
        }
    }

    override fun supportsPredictiveItemAnimations(): Boolean = false
}
