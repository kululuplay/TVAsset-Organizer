package com.iptv.player.util

import android.content.Context

/**
 * Device-level playback evidence that outlives a channel (route memory is per
 * channel). Currently one fact: when the Amlogic hardware decoder stalled on an
 * interlaced H.264 stream, so later interlaced streams skip it right away
 * ([com.iptv.player.player.InterlacedExoPolicy]).
 */
object DeviceQuirkMemory {

    private const val PREFS = "device_quirks"
    private const val KEY_AMLOGIC_INTERLACED_STALL_AT_MS = "amlogic_interlaced_stall_at_ms"

    fun amlogicInterlacedStallAtMs(context: Context): Long? =
        prefs(context).getLong(KEY_AMLOGIC_INTERLACED_STALL_AT_MS, 0L).takeIf { it > 0L }

    fun recordAmlogicInterlacedStall(context: Context, nowMs: Long) {
        prefs(context).edit().putLong(KEY_AMLOGIC_INTERLACED_STALL_AT_MS, nowMs).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
