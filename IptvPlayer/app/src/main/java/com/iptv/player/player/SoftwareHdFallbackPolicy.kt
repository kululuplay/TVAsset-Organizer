package com.iptv.player.player

import com.iptv.player.player.PlaybackRoutingPolicy.Failure
import com.iptv.player.player.PlaybackRoutingPolicy.Stage

/**
 * Keeps sticks off software HD. libVLC software decode of 720p+ or of
 * HEVC/VP9/AV1 turns a green or dead hardware picture into a CPU-bound
 * slideshow with heat and OS kills, so the ladder is better off ending and
 * letting the UI explain the stream is too heavy. Until 1.6.2 only
 * compatibility-mode / low-RAM devices were protected; since 1.7.0 the AUTO
 * engine mode withholds the software rung on every device, so AUTO runs
 * between the two hardware engines and reaches software only for SD
 * (<= 576p) and light MPEG-1/2 streams. One measured exception remains:
 * interlaced H.264 up to 1080 on an Amlogic box, where the hardware decoder
 * stalls and libVLC software with the deinterlacer off plays at full rate;
 * that is the only route left for those channels. A remote per-device
 * override can re-enable the software rung for boxes proven to cope. Purely a
 * ladder exclusion: the Exo-first start and every hardware step are untouched.
 */
internal object SoftwareHdFallbackPolicy {

    fun isHeavyStream(width: Int, height: Int, codec: String?): Boolean =
        isHeavyCodec(codec) || (height >= HD_MIN_HEIGHT && !isLightCodec(codec))

    /**
     * MPEG-1/2 software decode is cheap even at 1080i, and on Amlogic it is the
     * only rung left once the hardware MPEG-2 decoder fails; never withhold it.
     */
    fun isLightCodec(codec: String?): Boolean {
        val normalized = codec?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return LIGHT_CODEC_MARKERS.any { normalized.contains(it) }
    }

    fun isHeavyCodec(codec: String?): Boolean {
        val normalized = codec?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return HEAVY_CODEC_MARKERS.any { normalized.contains(it) }
    }

    /**
     * Stages this device must not be routed to for this stream.
     * [automaticMode] is the AUTO engine choice; [allowInterlacedSoftwareRescue]
     * is the Amlogic interlaced-H.264 exception described above.
     */
    fun excludedStages(
        constrainedDevice: Boolean,
        width: Int,
        height: Int,
        codec: String?,
        allowSoftwareHdFallback: Boolean,
        automaticMode: Boolean = false,
        allowInterlacedSoftwareRescue: Boolean = false,
    ): Set<Stage> {
        if (allowSoftwareHdFallback || !isHeavyStream(width, height, codec)) return emptySet()
        if (constrainedDevice) return setOf(Stage.VLC_SW)
        if (!automaticMode) return emptySet()
        val interlacedRescue =
            allowInterlacedSoftwareRescue && !isHeavyCodec(codec) && height in 1..INTERLACED_RESCUE_MAX_HEIGHT
        return if (interlacedRescue) emptySet() else setOf(Stage.VLC_SW)
    }

    /**
     * True when the ladder ran dry only because software was withheld for a
     * decode-quality failure; the UI then explains the stream is too heavy.
     */
    fun shouldReportTooHeavy(
        nextStage: Stage?,
        excluded: Set<Stage>,
        failure: Failure,
    ): Boolean =
        nextStage == null &&
            Stage.VLC_SW in excluded &&
            (failure == Failure.VIDEO || failure == Failure.DECODE || failure == Failure.SOFTWARE_SLOW)

    private const val HD_MIN_HEIGHT = 720
    /** Software 1080i H.264 was measured at full rate on a MiTV Stick; nothing above it. */
    private const val INTERLACED_RESCUE_MAX_HEIGHT = 1080
    private val LIGHT_CODEC_MARKERS = listOf("mpeg2", "mpeg-2", "mp2v", "mpgv", "mpeg1", "mpeg-1", "mp1v")
    private val HEAVY_CODEC_MARKERS = listOf(
        "hevc", "h265", "h.265", "hev1", "hvc1",
        "vp9", "vp09",
        "av1", "av01",
    )
}
