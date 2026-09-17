/*
 * VodVlcOptions.kt
 *
 * Architecture note (VOD player decomposition):
 *   The libVLC instance options and per-Media options used by the VOD player,
 *   pulled out of VodPlayerActivity.buildPlayer/preparePlayback. Keep them in
 *   lockstep with the live path (VlcPlayerEngine): decode/anti-stutter tuning
 *   changed on one side must be mirrored on the other.
 *
 * Moved verbatim from VodPlayerActivity; option strings are unchanged.
 */
package com.iptv.player.ui.player

import com.iptv.player.player.MediaTransportPolicy
import com.iptv.player.util.AppInfo
import org.videolan.libvlc.Media

internal object VodVlcOptions {

    /** LibVLC constructor options. */
    fun libVlcOptions(
        cachingMs: Int,
        forceSoftware: Boolean,
        allowPassthrough: Boolean,
    ): ArrayList<String> {
        // Mirror live TV (VlcPlayerEngine): keep VLC's proven decode defaults.
        // avcodec-fast and skipped loop filtering caused macroblocking/pixel rain
        // on real H.264/H.265 content, so neither is enabled here.
        val options = arrayListOf(
            "--network-caching=$cachingMs",
            "--file-caching=$cachingMs",
            // MediaCodec/OMX direct rendering LEFT ON (libVLC default) on the
            // hardware path so the Amlogic decoder renders straight onto the
            // SurfaceView underlay (its native hardware-video path). The DR-off +
            // android_display vout path could not colour-convert NV12 on the Xiaomi
            // compositor and stayed GREEN (RV16/RV32 chroma overrides had zero
            // effect). forceSoftware uses avcodec-hw=none so DR is irrelevant there.
            // Enables cumulative decoded/displayed/audio counters used by the
            // bounded frozen-video watchdog. Without this flag libVLC 3 may return
            // zeroed media statistics on some builds.
            "--stats",
            "--http-reconnect",
            "--http-user-agent=${AppInfo.USER_AGENT}"
        )
        if (forceSoftware) options.add("--avcodec-hw=none")
        // Match the global passthrough setting. Default OFF decodes Dolby/DTS to
        // stereo PCM; explicit opt-in leaves the encoded bitstream available to an
        // AV receiver/soundbar.
        if (!allowPassthrough) {
            options.add("--no-spdif")
            options.add("--stereo-mode=1")
        }
        // Do not force bob deinterlacing globally. It doubles output work on
        // interlaced sources and overloads weaker sticks; VLC may select a
        // suitable deinterlacer from stream/device capabilities when needed.
        return options
    }

    /** Per-Media options applied right after construction. */
    fun configureMedia(
        media: Media,
        url: String,
        cachingMs: Int,
        forceSoftware: Boolean,
        allowPassthrough: Boolean,
    ) {
        media.apply {
            MediaTransportPolicy.requireDirectMedia(url)
            addOption(MediaTransportPolicy.VLC_FILE_DEMUX)
            // Hardware decoding unless the global Decoder setting forces software
            // (mirrors VlcPlayerEngine on the live TV path).
            // Prefer hardware while respecting libVLC's device/codec safety list.
            // force=true enabled known-broken Amlogic MediaCodec paths and could
            // leave the SurfaceView permanently green.
            setHWDecoderEnabled(!forceSoftware, false)
            addOption(":network-caching=$cachingMs")
            addOption(":file-caching=$cachingMs")
            if (forceSoftware) addOption(":avcodec-hw=none")
            if (!allowPassthrough) {
                addOption(":no-spdif")
                // Force a 5.1 -> 2.0 stereo downmix so boxes that cannot open a
                // multichannel PCM AudioTrack still get sound.
                addOption(":stereo-mode=1")
            }
            addOption(":http-reconnect")
            addOption(":http-user-agent=${AppInfo.USER_AGENT}")
        }
    }
}
