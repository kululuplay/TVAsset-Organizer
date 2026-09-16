/*
 * TimeFormat.kt
 * Shared wall-clock formatting. Formatters are cached per pattern but rebuilt
 * whenever the device's default time zone or locale changes, so a long-running
 * screen never keeps rendering the zone/locale the app started with. Callers
 * with a Context get the user's 12/24-hour preference; the pure overloads stay
 * 24-hour and JVM-testable.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeFormat {

    private const val PATTERN_24H = "HH:mm"
    private const val PATTERN_12H = "h:mm a"

    private val cache = HashMap<String, SimpleDateFormat>()
    private var zoneId: String? = null
    private var localeTag: String? = null

    /** Formats [ms] with [pattern] in the current default zone and locale. */
    @Synchronized
    fun format(pattern: String, ms: Long): String = formatter(pattern).format(Date(ms))

    /** Time of day as 24-hour "HH:mm" (pure; used by tests and pure helpers). */
    fun clock24(ms: Long): String = format(PATTERN_24H, ms)

    /** Time of day honouring the device's 12/24-hour setting. */
    fun clock(context: Context, ms: Long): String = format(clockPattern(context), ms)

    /** "Wed 17 Sep • 20:30"-style day/date + time honouring the 12/24-hour setting. */
    fun dayDateTime(context: Context, ms: Long): String {
        val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM")
        return format(datePattern, ms) + " • " + clock(context, ms)
    }

    private fun clockPattern(context: Context): String =
        if (DateFormat.is24HourFormat(context)) PATTERN_24H else PATTERN_12H

    // Caller holds the object lock (format is @Synchronized), so the cache and
    // the SimpleDateFormat instances are never touched concurrently.
    private fun formatter(pattern: String): SimpleDateFormat {
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        val tag = locale.toLanguageTag()
        if (zone.id != zoneId || tag != localeTag) {
            cache.clear()
            zoneId = zone.id
            localeTag = tag
        }
        return cache.getOrPut(pattern) {
            SimpleDateFormat(pattern, locale).apply { timeZone = zone }
        }
    }
}
