package com.iptv.player.player

/**
 * Pure decision: where the Media3 FFmpeg software audio renderers sit relative
 * to the platform MediaCodec audio renderer for one player instance.
 *
 * Default is the equivalent of `EXTENSION_RENDERER_MODE_ON` restricted to
 * audio: hardware/platform decoders stay first and FFmpeg only takes formats
 * they cannot handle, so working device codecs are never displaced and video
 * is untouched. On constrained devices (`preferSoftwareAudio`) Dolby streams
 * additionally get a leading FFmpeg renderer that is gated to AC-3/E-AC-3:
 * a PCM software decode is more predictable there than a vendor codec whose
 * multichannel output the AudioTrack path may reject silently.
 *
 * Passthrough opt-in keeps FFmpeg strictly trailing; decoding the bitstream in
 * software would defeat the HDMI handoff the user asked for.
 */
internal object FfmpegAudioRendererOrder {

    /**
     * @property leadingMimes Formats an FFmpeg renderer placed BEFORE the
     *   platform renderer may claim; empty means no leading renderer.
     * @property trailing Whether an unrestricted FFmpeg renderer is appended
     *   AFTER the platform renderer (software fallback for anything else).
     */
    data class Plan(
        val leadingMimes: Set<String>,
        val trailing: Boolean,
    ) {
        val hasLeading: Boolean get() = leadingMimes.isNotEmpty()
    }

    val NONE = Plan(leadingMimes = emptySet(), trailing = false)

    fun plan(
        ffmpegAvailable: Boolean,
        preferSoftwareAudio: Boolean,
        allowPassthrough: Boolean,
    ): Plan {
        if (!ffmpegAvailable) return NONE
        val leading =
            if (preferSoftwareAudio && !allowPassthrough) SOFTWARE_FIRST_MIMES else emptySet()
        return Plan(leadingMimes = leading, trailing = true)
    }

    /**
     * Assemble the final renderer list. [platform] keeps its internal order;
     * [leading] and [trailing] are already gated/instantiated by the caller and
     * are dropped when the plan does not call for them, so a stale caller can
     * never place FFmpeg first by accident.
     */
    fun <T> arrange(
        plan: Plan,
        platform: List<T>,
        leading: List<T>,
        trailing: List<T>,
    ): List<T> {
        val out = ArrayList<T>(platform.size + leading.size + trailing.size)
        if (plan.hasLeading) out.addAll(leading)
        out.addAll(platform)
        if (plan.trailing) out.addAll(trailing)
        return out
    }

    /** Dolby formats that constrained devices decode to PCM in software first. */
    val SOFTWARE_FIRST_MIMES: Set<String> = setOf("audio/ac3", "audio/eac3", "audio/eac3-joc")
}
