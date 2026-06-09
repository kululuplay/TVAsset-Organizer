/*
 * DebugOverlayBinder.kt
 * Binds a TextView to the live PlaybackLog so the on-screen Debug overlay shows
 * the exact engine/stage/green/fallback events while a problem is reproduced.
 * Reused on the live preview (Home), the fullscreen live player and the VOD
 * player. Renders a small header (engine + resolution/fps/codec from the active
 * StreamInfo) plus the recent PlaybackLog tail. Self-refreshes once a second so
 * the header stays current; the tail repaints on every new log line.
 */
package com.iptv.player.util

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.iptv.player.player.StreamInfo

class DebugOverlayBinder(
    private val view: TextView,
    private val infoProvider: () -> StreamInfo?
) : PlaybackLog.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var enabled = false

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    /** Turn the overlay on/off (driven by the persisted Debug setting). */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            PlaybackLog.addListener(this)
            view.visibility = View.VISIBLE
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        } else {
            PlaybackLog.removeListener(this)
            handler.removeCallbacks(refresh)
            view.visibility = View.GONE
        }
    }

    /** Detach completely (call from onDestroy) so nothing leaks the View. */
    fun release() {
        enabled = false
        PlaybackLog.removeListener(this)
        handler.removeCallbacks(refresh)
        view.visibility = View.GONE
    }

    override fun onLog(line: String) {
        if (enabled) render()
    }

    private fun render() {
        val info = infoProvider()
        val header = if (info != null) {
            val res = if (info.width > 0 && info.height > 0) "${info.width}x${info.height}" else "—"
            val fps = if (info.fps > 0f) String.format("%.0f", info.fps) else "—"
            "▶ ${info.engine}  $res@${fps}  ${info.codec ?: "—"}"
        } else {
            "▶ (no stream)"
        }
        val tail = PlaybackLog.recentLines().joinToString("\n")
        view.text = if (tail.isEmpty()) header else "$header\n$tail"
    }

    private companion object {
        const val REFRESH_MS = 1000L
    }
}
