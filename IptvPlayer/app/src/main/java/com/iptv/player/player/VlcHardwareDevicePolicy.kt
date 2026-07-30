package com.iptv.player.player

import com.iptv.player.data.model.PlayerMode
import com.iptv.player.player.PlaybackRoutingPolicy.Failure
import com.iptv.player.player.PlaybackRoutingPolicy.Stage

/**
 * libVLC and Media3 both use Android MediaCodec, but they do not drive the
 * decoder's output surface in the same way. Amlogic OMX/C2 video decoders are
 * reliable through Media3's SurfaceView path while libVLC direct rendering can
 * return an "unknown" native format followed by a solid-green frame.
 *
 * Treating VLC hardware as unavailable on those devices preserves the user's
 * hardware-decoder preference by using EXO hardware, rather than first showing
 * a broken VLC picture and waiting for PixelCopy recovery.
 */
internal object VlcHardwareDevicePolicy {

    fun shouldBypassVlcHardware(videoDecoderNames: Iterable<String>): Boolean =
        videoDecoderNames.any { rawName ->
            val name = rawName.trim().lowercase()
            name.startsWith("omx.amlogic.") ||
                name.startsWith("c2.amlogic.") ||
                name.contains(".amlogic.")
        }

    fun compatibleInitialStage(
        preferred: Stage,
        bypassVlcHardware: Boolean,
    ): Stage =
        if (bypassVlcHardware && preferred == Stage.VLC_HW) Stage.EXO else preferred

    /**
     * Amlogic's OMX decoder can emit a valid first frame after Media3 stop/prepare
     * and then stop returning video while audio continues. A fresh ExoPlayer/codec
     * instance is therefore required for each stream boundary on these devices;
     * other engines/devices retain the faster reuse path.
     */
    fun canReuseEngineForStreamChange(
        stage: Stage,
        bypassVlcHardware: Boolean,
    ): Boolean = !(bypassVlcHardware && stage == Stage.EXO)

    /**
     * EXO is the hardware substitute for an unsafe VLC hardware path. If that
     * substitute itself proves media-incompatible in explicit VLC mode, retain
     * one bounded VLC-software rescue instead of ending the ladder immediately.
     */
    fun fallbackAfterHardwareSubstitution(
        mode: PlayerMode,
        current: Stage,
        failure: Failure,
        triedStages: Set<Stage>,
        bypassVlcHardware: Boolean,
    ): Stage? {
        if (
            !bypassVlcHardware ||
            mode != PlayerMode.VLC ||
            current != Stage.EXO ||
            Stage.VLC_SW in triedStages ||
            failure == Failure.ERROR ||
            failure == Failure.SOFTWARE_SLOW
        ) {
            return null
        }
        return Stage.VLC_SW
    }
}
