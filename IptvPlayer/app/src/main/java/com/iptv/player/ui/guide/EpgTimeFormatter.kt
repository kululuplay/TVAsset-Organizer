/*
 * EpgTimeFormatter.kt
 * Tiny reusable helper to render program time ranges as HH:mm-HH:mm. Backed by
 * ui.common.TimeFormat, which rebuilds its formatter whenever the device zone or
 * locale changes (so a guide left open across a zone change stays correct) and
 * serialises access because SimpleDateFormat is not thread-safe. The Context
 * overloads honour the user's 12/24-hour preference; the pure overloads stay
 * 24-hour and JVM-testable.
 */
package com.iptv.player.ui.guide

import android.content.Context
import com.iptv.player.data.model.Program
import com.iptv.player.ui.common.TimeFormat

object EpgTimeFormatter {

    /** Single timestamp formatted as 24-hour HH:mm in the current default zone. */
    fun time(ms: Long): String = TimeFormat.clock24(ms)

    /** Single timestamp honouring the device's 12/24-hour setting. */
    fun time(context: Context, ms: Long): String = TimeFormat.clock(context, ms)

    /** A start/stop window formatted as "HH:mm-HH:mm". */
    fun range(startMs: Long, stopMs: Long): String = "${time(startMs)}-${time(stopMs)}"

    /** Convenience overload for a [Program]. */
    fun range(program: Program): String = range(program.startMs, program.stopMs)

    /** A start/stop window honouring the device's 12/24-hour setting. */
    fun range(context: Context, program: Program): String =
        "${time(context, program.startMs)}-${time(context, program.stopMs)}"
}
