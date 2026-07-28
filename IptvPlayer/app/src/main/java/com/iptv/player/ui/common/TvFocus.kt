/*
 * TvFocus.kt
 * Small Android TV focus helpers shared by catalog and detail screens.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Moves D-pad focus to an adapter position after RecyclerView has laid it out.
 *
 * Data arrives asynchronously on the TV browse screens. A single post() is not
 * enough when a list has just changed from GONE to VISIBLE or a Paging refresh
 * is still committing, so retry for a few frames instead of leaving focus on a
 * disappearing loading/error control.
 */
fun RecyclerView.requestFocusAt(position: Int = 0, attempts: Int = 12) {
    val count = adapter?.itemCount ?: 0
    if (count <= 0) return
    val target = position.coerceIn(0, count - 1)
    scrollToPosition(target)
    post(object : Runnable {
        private var remaining = attempts

        override fun run() {
            if (!isAttachedToWindow) return
            val item = findViewHolderForAdapterPosition(target)?.itemView
            when {
                item != null -> item.requestFocus()
                remaining-- > 0 -> postOnAnimation(this)
            }
        }
    })
}

/** Closes the on-screen keyboard before focus is returned to TV content. */
fun View.hideSoftKeyboard() {
    val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    input?.hideSoftInputFromWindow(windowToken, 0)
}
