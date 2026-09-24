package com.iptv.player.player

/**
 * Pure decision: whether the libVLC software route runs with deinterlacing off.
 *
 * Measured on a Xiaomi MiTV Stick (Amlogic s4, 32-bit, 2026-09-24): 1080i
 * channels on the software route render at 10-12 fps with libVLC's default
 * deinterlacer and at 25 fps with `--deinterlace=0`. Every filter mode, even
 * `discard`, loses the zero-copy output path, so the cost is the filter stage
 * itself rather than its algorithm; `--avcodec-skiploopfilter` changes nothing.
 * Hardware routes are untouched (the SoC deinterlaces there), and progressive
 * streams never engage the filter, so the only visible effect is combing on
 * fast motion in interlaced streams, on devices that otherwise show a slideshow.
 *
 * The remote policy key `vlcDeinterlace` (true = keep libVLC's default, false =
 * off) overrides the device rule in both directions.
 */
internal object VlcDeinterlacePolicy {

    fun disableDeinterlace(
        softwareDecode: Boolean,
        amlogicDecoder: Boolean,
        is64Bit: Boolean,
        remoteOverride: Boolean?,
    ): Boolean {
        if (!softwareDecode) return false
        if (remoteOverride != null) return !remoteOverride
        return amlogicDecoder || !is64Bit
    }
}
