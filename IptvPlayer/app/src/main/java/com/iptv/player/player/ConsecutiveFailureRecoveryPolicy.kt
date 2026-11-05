package com.iptv.player.player

/**
 * Pure policies for two real-world Stick failure modes:
 *
 *  1. **Stutter reconnect loop ("yayınlar devamlı takılıyor")**:
 *     `FRAME_DROP_RATIO` and `DECODER_HICCUPS` exist to move a stream that is
 *     dropping frames onto an untried alternative engine (e.g. `EXO <-> VLC_HW`).
 *     When no untried alternative video stage exists for the stream (for example,
 *     an Amlogic Stick where `VLC_HW` is bypassed and `VLC_SW` is withheld for HD,
 *     or after both `EXO` and `VLC_HW` have already been tried), treating a
 *     frame-drop ratio or brief hiccup as a fatal stage failure falls through to
 *     `engageReconnect()`, which tears down the working stream and re-buffers
 *     the exact same engine every few seconds until the 45 s window expires.
 *     [shouldKeepPlayingOnStutter] keeps the active engine running whenever no
 *     untried stage can rescue it.
 *
 *  2. **Wedged long-running process ("yayın hiç açmıyor, fişten çekmek gerekiyor")**:
 *     Because TV screens hold `FLAG_KEEP_SCREEN_ON` and remote key presses reset
 *     the 4-hour idle timer, a Stick whose native MediaCodec/AudioTrack/socket
 *     state becomes poisoned mid-session can fail every channel attempt without
 *     necessarily hitting a 12 s libVLC JNI stop timeout. After
 *     [DEFAULT_MAX_CONSECUTIVE_FAILURES] consecutive non-authoritative terminal
 *     channel failures with zero stable playbacks in between, [recordTerminalFailure]
 *     escalates to a controlled `:playback_recovery` process recycle — the
 *     automatic in-app equivalent of unplugging and replugging the Stick.
 */
internal class ConsecutiveFailureRecoveryPolicy(
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
) {
    private var consecutiveFailures = 0

    val count: Int get() = consecutiveFailures

    /** Any channel reaching sustained stable playback proves the process is healthy. */
    fun onStablePlayback() {
        consecutiveFailures = 0
    }

    /**
     * Records one terminal channel failure. Authoritative HTTP rejections
     * (401/403/404/458) and unsupported-format rejections (415 HLS) are account
     * or source refusals that recycling the process cannot fix, so they do not
     * increment the counter. Returns true when the threshold is reached and the
     * caller should trigger a controlled process recycle.
     */
    fun recordTerminalFailure(httpStatus: Int?, hlsRejected: Boolean = false): Boolean {
        if (hlsRejected || LiveTransportPolicy.isAuthoritativeHttpFailure(httpStatus)) {
            return false
        }
        consecutiveFailures++
        if (consecutiveFailures >= maxConsecutiveFailures) {
            consecutiveFailures = 0
            return true
        }
        return false
    }

    fun reset() {
        consecutiveFailures = 0
    }

    companion object {
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 3

        /**
         * True when [check] measures frame-delivery quality rather than a dead
         * picture (`FRAME_DROP_RATIO` or `DECODER_HICCUPS`) and there is no
         * untried video stage (`nextStage == null`) to switch to.
         */
        fun isQualityOnlyStutterVerdict(check: VideoVerdict.Check?): Boolean =
            check == VideoVerdict.Check.FRAME_DROP_RATIO ||
                check == VideoVerdict.Check.DECODER_HICCUPS

        fun shouldKeepPlayingOnStutter(
            check: VideoVerdict.Check?,
            hasAlternativeVideoStage: Boolean,
        ): Boolean = isQualityOnlyStutterVerdict(check) && !hasAlternativeVideoStage
    }
}
