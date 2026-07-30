package com.iptv.player.player

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode

/**
 * Pure, testable playback-routing policy.
 *
 * The user-facing settings have two independent axes:
 *  - [PlayerMode] chooses the engine (automatic, ExoPlayer, or VLC).
 *  - [DecoderMode] chooses the decode preference (automatic, hardware, software).
 *
 * User choices are preferences, not failure traps. Explicit engines are kept for
 * ordinary source/network failures, but a confirmed invalid video/decode path or
 * unsupported Exo audio may use one bounded compatibility fallback.
 */
internal object PlaybackRoutingPolicy {

    enum class Stage { EXO, VLC_HW, VLC_SW }

    enum class Failure {
        /** Network/source error; explicit engines normally reconnect in place. */
        ERROR,
        /** Stream opened but no playable output was confirmed within the deadline. */
        STARTUP,
        AUDIO,
        VIDEO,
        SOFTWARE_SLOW,
        DECODE,
    }

    /**
     * AUTO is deliberately ExoPlayer hardware-first on every device. This matches
     * the app's documented policy and the latest real-device result: Media3's
     * MediaCodec -> SurfaceView path is the most reliable Amlogic hardware route.
     */
    fun initialStage(mode: PlayerMode, decoderMode: DecoderMode): Stage = when (mode) {
        PlayerMode.AUTO -> when (decoderMode) {
            DecoderMode.SOFTWARE -> Stage.VLC_SW
            DecoderMode.AUTO,
            DecoderMode.HARDWARE -> Stage.EXO
        }
        PlayerMode.EXOPLAYER -> Stage.EXO
        PlayerMode.VLC -> if (decoderMode == DecoderMode.SOFTWARE) {
            Stage.VLC_SW
        } else {
            Stage.VLC_HW
        }
    }

    /**
     * Returns the first compatible stage that has not already been attempted in
     * this channel session. Supplying [triedStages] is what makes compound recovery
     * bounded: EXO green -> VLC hardware green -> VLC software can try each path,
     * but can never bounce forever between hardware and software.
     */
    fun nextStage(
        mode: PlayerMode,
        decoderMode: DecoderMode,
        current: Stage,
        failure: Failure,
        triedStages: Set<Stage> = emptySet(),
    ): Stage? = candidates(mode, decoderMode, current, failure)
        .firstOrNull { it != current && it !in triedStages }

    private fun candidates(
        mode: PlayerMode,
        decoderMode: DecoderMode,
        current: Stage,
        failure: Failure,
    ): List<Stage> = when (mode) {
        PlayerMode.EXOPLAYER -> when (failure) {
            Failure.ERROR -> emptyList()
            Failure.STARTUP -> when (current) {
                Stage.EXO -> listOf(Stage.VLC_HW, Stage.VLC_SW)
                Stage.VLC_HW -> listOf(Stage.VLC_SW)
                Stage.VLC_SW -> listOf(Stage.VLC_HW)
            }
            Failure.AUDIO -> when (current) {
                Stage.EXO -> listOf(Stage.VLC_HW, Stage.VLC_SW)
                Stage.VLC_HW -> listOf(Stage.VLC_SW)
                Stage.VLC_SW -> listOf(Stage.VLC_HW)
            }
            Failure.VIDEO,
            Failure.DECODE -> when (current) {
                Stage.EXO -> listOf(Stage.VLC_HW, Stage.VLC_SW)
                Stage.VLC_HW -> listOf(Stage.VLC_SW)
                Stage.VLC_SW -> listOf(Stage.VLC_HW)
            }
            Failure.SOFTWARE_SLOW ->
                if (current == Stage.VLC_SW) listOf(Stage.VLC_HW) else emptyList()
        }

        PlayerMode.VLC -> when (failure) {
            // An ordinary server/network drop must not change decoder mode.
            Failure.ERROR -> emptyList()
            Failure.STARTUP,
            Failure.AUDIO,
            Failure.VIDEO,
            Failure.DECODE -> when (current) {
                // VLC remains the user's preferred engine. Exo is a final,
                // one-shot rescue when both VLC decode paths are proven unusable.
                Stage.VLC_HW -> listOf(Stage.VLC_SW, Stage.EXO)
                Stage.VLC_SW -> listOf(Stage.VLC_HW, Stage.EXO)
                Stage.EXO -> emptyList()
            }
            Failure.SOFTWARE_SLOW ->
                if (current == Stage.VLC_SW) {
                    listOf(Stage.VLC_HW, Stage.EXO)
                } else {
                    emptyList()
                }
        }

        PlayerMode.AUTO -> when (failure) {
            Failure.ERROR -> when (decoderMode) {
                DecoderMode.AUTO -> when (current) {
                    Stage.EXO -> listOf(Stage.VLC_HW, Stage.VLC_SW)
                    Stage.VLC_HW -> listOf(Stage.VLC_SW)
                    Stage.VLC_SW -> emptyList()
                }
                DecoderMode.HARDWARE -> when (current) {
                    Stage.EXO -> listOf(Stage.VLC_HW)
                    Stage.VLC_HW,
                    Stage.VLC_SW -> emptyList()
                }
                DecoderMode.SOFTWARE -> emptyList()
            }
            Failure.STARTUP -> when (current) {
                Stage.EXO -> listOf(Stage.VLC_HW, Stage.VLC_SW)
                Stage.VLC_HW -> listOf(Stage.VLC_SW, Stage.EXO)
                Stage.VLC_SW -> listOf(Stage.VLC_HW, Stage.EXO)
            }
            Failure.AUDIO -> when (current) {
                Stage.EXO -> listOf(Stage.VLC_HW, Stage.VLC_SW)
                Stage.VLC_HW -> listOf(Stage.VLC_SW, Stage.EXO)
                Stage.VLC_SW -> listOf(Stage.VLC_HW, Stage.EXO)
            }
            Failure.VIDEO -> when (current) {
                // Try libVLC's safe hardware implementation before software.
                // 1080p50 software decode overload is a common source of frozen
                // or macro-blocked output on Android TV sticks.
                Stage.EXO -> listOf(Stage.VLC_HW, Stage.VLC_SW)
                Stage.VLC_HW -> listOf(Stage.VLC_SW, Stage.EXO)
                Stage.VLC_SW -> listOf(Stage.VLC_HW, Stage.EXO)
            }
            Failure.DECODE -> when (current) {
                Stage.EXO -> listOf(Stage.VLC_HW, Stage.VLC_SW)
                Stage.VLC_HW -> listOf(Stage.VLC_SW, Stage.EXO)
                Stage.VLC_SW -> listOf(Stage.VLC_HW, Stage.EXO)
            }
            Failure.SOFTWARE_SLOW ->
                if (current == Stage.VLC_SW) {
                    // Stay with VLC first for a user who explicitly preferred
                    // software; EXO remains a last untried hardware implementation.
                    listOf(Stage.VLC_HW, Stage.EXO)
                } else {
                    emptyList()
                }
        }
    }
}
