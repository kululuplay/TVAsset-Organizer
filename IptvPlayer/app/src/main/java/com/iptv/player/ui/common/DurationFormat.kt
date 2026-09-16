/*
 * DurationFormat.kt
 * Shared "H:MM:SS" / "M:SS" rendering for content durations so movie details
 * and episode rows read the same way.
 */
package com.iptv.player.ui.common

import java.util.Locale

object DurationFormat {

    /** Seconds -> "H:MM:SS" (or "M:SS" when under an hour). */
    fun seconds(secs: Int): String {
        val total = secs.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", m, s)
        }
    }
}
