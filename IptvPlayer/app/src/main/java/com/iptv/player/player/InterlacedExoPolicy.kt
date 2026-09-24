package com.iptv.player.player

/**
 * Pure decision: leave Exo immediately for an interlaced H.264 stream on a
 * decoder family that already proved it stalls on such streams.
 *
 * On the Xiaomi MiTV Stick (Amlogic s4 firmware, 2026-09-24) every 1080i
 * channel decodes ~5 frames and then the OMX decoder never returns an output
 * buffer again (vendor issue, identical on Media3 1.8.1 and 1.11.1). The frame
 * watchdog catches it after 7 s, so each first visit of such a channel costs
 * ~12 s before the software route starts. Once a device has recorded that
 * stall, the next interlaced stream (known from the SPS at extraction time,
 * before the first frame) skips straight to the compatibility ladder. The
 * evidence expires so a firmware fix is picked up again.
 */
internal object InterlacedExoPolicy {

    const val LEARNED_STALL_TTL_MS: Long = 14L * 24 * 60 * 60 * 1000

    fun failFast(
        interlaced: Boolean?,
        amlogicDecoder: Boolean,
        stallLearnedAtMs: Long?,
        nowMs: Long,
    ): Boolean =
        interlaced == true &&
            amlogicDecoder &&
            stallLearnedAtMs != null &&
            nowMs >= stallLearnedAtMs &&
            nowMs - stallLearnedAtMs < LEARNED_STALL_TTL_MS

    /** Only a frame-watchdog stall on an interlaced stream on Amlogic is evidence. */
    fun shouldRecordStall(interlaced: Boolean?, amlogicDecoder: Boolean): Boolean =
        interlaced == true && amlogicDecoder
}
