/*
 * FullscreenZapDebouncer.kt
 *
 * Architecture note (Home screen decomposition):
 *   Collapses fast CH+/CH- taps in HomeActivity's inline fullscreen into one
 *   playback start (~280 ms settle) before touching VLC/Exo on slower TV
 *   chipsets; the name confirmation chip updates instantly on every press. The
 *   Activity decides the target channel and whether a commit is still allowed;
 *   this class only owns the dedicated handler, the pending target and the chip.
 *   [HomeZapTarget] is the pure "next channel in scope" rule.
 *
 * Moved verbatim from HomeActivity.
 */
package com.iptv.player.ui.home

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.iptv.player.data.model.Channel
import com.iptv.player.ui.common.ChannelText

internal object HomeZapTarget {
    /** Index of the channel [direction] steps from [currentId] (wrapping), or 0 when unknown. */
    fun nextIndex(channels: List<Channel>, currentId: String?, direction: Int): Int {
        val currentIndex = channels.indexOfFirst { it.id == currentId }
        return if (currentIndex >= 0) {
            (currentIndex + direction + channels.size) % channels.size
        } else {
            0
        }
    }
}

internal class FullscreenZapDebouncer(
    private val overlay: TextView,
    private val debounceMs: Long,
    private val host: Host,
) {
    interface Host {
        /** False once fullscreen ended or the Activity left STARTED. */
        fun canCommit(): Boolean
        fun commit(channel: Channel)
    }

    /** Collapses fast CH+/- taps before touching VLC/Exo on slower TV chipsets. */
    private val handler = Handler(Looper.getMainLooper())
    var pending: Channel? = null
        private set

    fun arm(target: Channel) {
        pending = target
        // CH+/CH- keeps a subtle name confirmation without flashing the channel
        // number in the top-right corner.
        overlay.text = ChannelText.clean(target.name)
        overlay.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!host.canCommit()) {
                cancel()
                return@postDelayed
            }
            val channel = pending ?: return@postDelayed
            pending = null
            overlay.visibility = View.GONE
            host.commit(channel)
        }, debounceMs)
    }

    fun cancel(hideOverlay: Boolean = true) {
        handler.removeCallbacksAndMessages(null)
        pending = null
        if (hideOverlay) overlay.visibility = View.GONE
    }
}
