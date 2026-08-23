package com.iptv.player.player

/**
 * Keeps live-TV loading UI tied to verified output instead of one-shot callbacks.
 *
 * A cold start, zap or engine restart must render a fresh frame before the video
 * is uncovered. A normal rebuffer is different: the same surface has already
 * been verified, so a later Playing signal may safely clear the spinner even if
 * the backend does not emit another first-frame callback.
 */
internal class LiveLoadingOverlayPolicy {
    private var activeEngine: String? = null
    private var hasVerifiedFrame = false

    /** A new source/decoder attempt invalidates pixels from the previous attempt. */
    fun requireFreshFrame() {
        hasVerifiedFrame = false
    }

    /**
     * Ordinary cache top-ups retain the current surface proof. The caller still
     * shows loading immediately; [shouldHideOnPlaying] decides whether resume is
     * allowed to remove it.
     */
    fun onBuffering() = Unit

    /** Engine changes can never inherit a frame verified on another backend. */
    fun onEngineChanged(engineName: String) {
        if (activeEngine != null && activeEngine != engineName) {
            hasVerifiedFrame = false
        }
        activeEngine = engineName
    }

    /** Records a frame that was verified for the currently active backend. */
    fun onVideoResumed() {
        hasVerifiedFrame = true
    }

    /**
     * Audio-only playback is ready at Playing. Video may use Playing only after
     * this same backend has already produced a verified frame. A callback naming
     * a retired backend is ignored rather than changing the active backend.
     */
    fun shouldHideOnPlaying(
        engineName: String,
        expectsVideo: Boolean,
    ): Boolean {
        if (activeEngine != null && activeEngine != engineName) return false
        if (activeEngine == null) activeEngine = engineName
        return !expectsVideo || hasVerifiedFrame
    }
}
