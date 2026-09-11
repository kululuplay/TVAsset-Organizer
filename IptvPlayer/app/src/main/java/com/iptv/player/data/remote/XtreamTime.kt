package com.iptv.player.data.remote

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object XtreamTime {
    private val zones = TimeZone.getAvailableIDs().toSet()

    /** Unknown panel zone must not silently become the TV's local timezone. */
    fun formatCatchup(timestampMs: Long, panelTimezone: String?): String? {
        if (panelTimezone !in zones) return null
        return SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(panelTimezone)
        }.format(Date(timestampMs))
    }
}
