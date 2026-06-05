/*
 * EpgTimeFormatter.kt
 * Tiny reusable helper to render program time ranges as HH:mm-HH:mm using the
 * device's 24h SimpleDateFormat. Synchronised because SimpleDateFormat is not
 * thread-safe and may be called from background-loaded EPG rows.
 */
package com.iptv.player.ui.guide

import com.iptv.player.data.model.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EpgTimeFormatter {

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Single timestamp formatted as HH:mm. */
    fun time(ms: Long): String = synchronized(fmt) { fmt.format(Date(ms)) }

    /** A start/stop window formatted as "HH:mm-HH:mm". */
    fun range(startMs: Long, stopMs: Long): String = "${time(startMs)}-${time(stopMs)}"

    /** Convenience overload for a [Program]. */
    fun range(program: Program): String = range(program.startMs, program.stopMs)
}
