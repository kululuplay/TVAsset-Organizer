/*
 * NowNextBinder.kt
 * Small reusable binding helper for channel rows / overlays. Given a Channel it
 * asks the repository for the now/next pair (loaded lazily, per row) and fills
 * the provided TextViews. Returns the Job so callers can cancel on recycle to
 * keep memory and stale-binding issues low on weak devices.
 */
package com.iptv.player.ui.guide

import android.widget.TextView
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object NowNextBinder {

    /**
     * Loads now/next for [channel] and writes "Now: …" / "Up next: …" into the
     * given views. [nextView] is optional (channel rows often show only "now").
     */
    fun bind(
        scope: CoroutineScope,
        channel: Channel,
        nowView: TextView,
        nextView: TextView? = null
    ): Job = scope.launch {
        val ctx = nowView.context
        val nn = ServiceLocator.repository.getNowNext(channel)
        val now = nn.now
        val next = nn.next

        nowView.text = if (now != null) {
            ctx.getString(R.string.now_playing) + ": " + now.title
        } else {
            ""
        }
        nextView?.text = if (next != null) {
            ctx.getString(R.string.up_next) + ": " + next.title
        } else {
            ""
        }
    }
}
