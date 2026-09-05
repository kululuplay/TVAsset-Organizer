package com.iptv.player.player

/** Prefer an available platform software AUDIO decoder without changing video. */
internal object LiveAudioDecoderPolicy {
    fun <T> order(
        mimeType: String,
        preferSoftwareAudio: Boolean,
        allowPassthrough: Boolean,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
        candidates: List<T>,
        isSoftware: (T) -> Boolean,
    ): List<T> {
        if (!preferSoftwareAudio || allowPassthrough || requiresSecureDecoder ||
            requiresTunnelingDecoder || mimeType !in PCM_COMPATIBILITY_MIMES
        ) {
            return candidates
        }
        // Stable ordering retains the vendor fallback if the software codec
        // cannot initialize. An absent decoder is never invented/advertised.
        return candidates.sortedBy { if (isSoftware(it)) 0 else 1 }
    }

    private val PCM_COMPATIBILITY_MIMES = setOf("audio/ac3", "audio/mpeg-l1", "audio/mpeg-l2")
}
