package com.iptv.player.player

import com.iptv.player.player.PlaybackRoutingPolicy.Failure
import com.iptv.player.player.PlaybackRoutingPolicy.Stage

/**
 * Keeps weak sticks off software HD. On a compatibility-mode / low-RAM device,
 * libVLC software decode of 720p+ or of HEVC/VP9/AV1 turns a green or dead
 * hardware picture into a CPU-bound slideshow with heat and OS kills, so the
 * ladder is better off ending and letting the UI explain the stream is too
 * heavy. A remote per-device override can re-enable the software rung for
 * boxes proven to cope. Purely a ladder exclusion: the Amlogic Exo-first start
 * and every reactive hardware step are untouched.
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

    /** Stages the constrained device must not be routed to for this stream. */
    fun excludedStages(
        constrainedDevice: Boolean,
        width: Int,
        height: Int,
        codec: String?,
        allowSoftwareHdFallback: Boolean,
    ): Set<Stage> =
        if (constrainedDevice && !allowSoftwareHdFallback && isHeavyStream(width, height, codec)) {
            setOf(Stage.VLC_SW)
        } else {
            emptySet()
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
    private val LIGHT_CODEC_MARKERS = listOf("mpeg2", "mpeg-2", "mp2v", "mpgv", "mpeg1", "mpeg-1", "mp1v")
    private val HEAVY_CODEC_MARKERS = listOf(
        "hevc", "h265", "h.265", "hev1", "hvc1",
        "vp9", "vp09",
        "av1", "av01",
    )
}
