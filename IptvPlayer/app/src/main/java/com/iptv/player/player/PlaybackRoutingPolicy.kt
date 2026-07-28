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
 * Explicit engine choices stay on that engine. Cross-engine fallback is reserved
 * for AUTO, while VLC + AUTO decoder may still move from VLC hardware to VLC
 * software because the selected engine has not changed.
 */
internal object PlaybackRoutingPolicy {

    enum class Stage { EXO, VLC_HW, VLC_SW }

    enum class Failure { ERROR, AUDIO, VIDEO, SOFTWARE_SLOW, DECODE }

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
     * Returns the next compatible stage after a confirmed engine/decode problem.
     * General source/network errors use the same ladder only in AUTO; explicit
     * engine selections reconnect that engine instead of silently changing it.
     */
    fun nextStage(
        mode: PlayerMode,
        decoderMode: DecoderMode,
        current: Stage,
        failure: Failure,
    ): Stage? {
        if (failure == Failure.SOFTWARE_SLOW) {
            if (current != Stage.VLC_SW || decoderMode == DecoderMode.SOFTWARE) return null
            return when (mode) {
                PlayerMode.AUTO -> Stage.EXO
                PlayerMode.VLC -> Stage.VLC_HW
                PlayerMode.EXOPLAYER -> null
            }
        }

        return when (mode) {
            PlayerMode.EXOPLAYER -> null

            PlayerMode.VLC -> when {
                current == Stage.VLC_HW && decoderMode == DecoderMode.AUTO ->
                    Stage.VLC_SW
                else -> null
            }

            PlayerMode.AUTO -> when (decoderMode) {
                DecoderMode.SOFTWARE -> null
                DecoderMode.HARDWARE -> when (current) {
                    Stage.EXO -> Stage.VLC_HW
                    Stage.VLC_HW,
                    Stage.VLC_SW -> null
                }
                DecoderMode.AUTO -> when (current) {
                    Stage.EXO -> Stage.VLC_HW
                    Stage.VLC_HW -> Stage.VLC_SW
                    Stage.VLC_SW -> null
                }
            }
        }
    }
}
