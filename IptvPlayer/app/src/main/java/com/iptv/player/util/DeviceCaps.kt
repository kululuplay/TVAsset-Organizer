/*
 * DeviceCaps.kt
 * Runtime hardware capability probes. Currently detects Amlogic SoCs, whose
 * libVLC hardware-video underlay greens out on essentially every non-UHD profile
 * (1080i@25, 576p, 1080p@50…) through the box compositor. That green is a
 * display-plane failure with NO signal exposed by libVLC's high-level API, so we
 * cannot detect it at runtime — we detect the hardware instead and route the
 * whole libVLC hardware path to software on those boxes. See the Amlogic
 * green-screen memory note.
 */
package com.iptv.player.util

import android.media.MediaCodecList
import android.os.Build

object DeviceCaps {

    /**
     * True on Amlogic boxes (e.g. Xiaomi Mi TV Stick "soul"/MiTV-AYFR0). Detected
     * primarily from the registered MediaCodec list — Amlogic decoders are named
     * "OMX.amlogic.*", which is far more reliable than Build strings — with a
     * Build.HARDWARE/BOARD fallback. Probed once and cached.
     */
    val isAmlogic: Boolean by lazy { detectAmlogic() }

    private fun detectAmlogic(): Boolean {
        val buildHints = listOf(Build.HARDWARE, Build.BOARD, Build.DEVICE)
            .any { it?.lowercase()?.let { s -> "amlogic" in s || "meson" in s } == true }
        if (buildHints) return true
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .any { it.name.lowercase().contains("amlogic") }
        }.getOrDefault(false)
    }
}
