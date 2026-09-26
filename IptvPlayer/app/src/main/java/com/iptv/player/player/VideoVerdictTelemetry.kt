package com.iptv.player.player

/**
 * Closed-value suffixes that let existing stability rows (reconnect, fatal,
 * fallback, playback_attempt) name the video check that fired and how video
 * was proven. No new event types: the operator risk queries count every row
 * and the spool keeps only 50 events. Never carries a decoder name, URL or
 * message text (StabilityTelemetry privacy).
 */
internal object VideoVerdictTelemetry {

    /** StabilityTelemetry clips detail at this length. */
    const val MAX_DETAIL = 300

    fun familyOf(decoderName: String?): DecoderFamily {
        val name = decoderName?.trim()?.lowercase() ?: return DecoderFamily.UNKNOWN
        if (name.isEmpty()) return DecoderFamily.UNKNOWN
        return when {
            VlcHardwareDevicePolicy.shouldBypassVlcHardware(listOf(name)) -> DecoderFamily.AMLOGIC
            name.startsWith("c2.android.") || name.startsWith("omx.google.") -> DecoderFamily.SOFTWARE
            else -> DecoderFamily.OTHER
        }
    }

    fun suffix(verdict: VideoVerdict, inlinePreview: Boolean): String =
        " check=${verdict.check.name} fam=${verdict.decoderFamily.name} " +
            "il=${when (verdict.interlaced) { true -> "1"; false -> "0"; null -> "?" }} " +
            "surf=${if (inlinePreview) "INLINE_PREVIEW" else "FULLSCREEN"}"

    fun proofSuffix(proof: VideoProof): String = " proof=${proof.name}"

    /** [base] is clipped, never the closed suffix, so the whole detail fits. */
    fun append(base: String, suffix: String): String {
        val room = (MAX_DETAIL - suffix.length).coerceAtLeast(0)
        return base.take(room) + suffix
    }
}
