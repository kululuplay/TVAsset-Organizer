package com.iptv.player.player

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.player.PlaybackRoutingPolicy.Failure
import com.iptv.player.player.PlaybackRoutingPolicy.Stage

/**
 * Pure ladder resolution behind PlayerController.nextStage: the compatibility
 * ladder minus every stage this device/stream cannot use (tried stages, VLC
 * hardware on Amlogic, software for QHD/4K, HEVC without a software rescue and
 * software HD on constrained devices). Side-effect free so the Exo engine can
 * ask "would a VIDEO verdict go anywhere?" without toasts or telemetry.
 */
internal object LiveVideoRoute {

    /** [softwareHdExcluded] feeds the controller's "too heavy" notice. */
    data class Resolved(val next: Stage?, val softwareHdExcluded: Set<Stage>)

    fun next(
        mode: PlayerMode,
        decoderMode: DecoderMode,
        current: Stage,
        reason: Failure,
        triedStages: Set<Stage>,
        bypassVlcHardware: Boolean,
        constrainedDevice: Boolean,
        allowSoftwareHdFallback: Boolean,
        allowSoftwareHevcRescue: Boolean,
        width: Int,
        height: Int,
        codec: String?,
    ): Resolved {
        val softwareCodecUnavailable = !allowSoftwareHevcRescue && isHevcCodec(codec)
        // Weak sticks must not be pushed onto software HD/HEVC: it trades a
        // green picture for a CPU-bound slideshow. Remote override re-enables it.
        val softwareHdExcluded = SoftwareHdFallbackPolicy.excludedStages(
            constrainedDevice = constrainedDevice,
            width = width,
            height = height,
            codec = codec,
            allowSoftwareHdFallback = allowSoftwareHdFallback,
        )
        val unavailableAwareTriedStages =
            triedStages + VlcHardwareDevicePolicy.unavailableStages(
                bypassVlcHardware = bypassVlcHardware,
                width = width,
                height = height,
            ) + (if (softwareCodecUnavailable) setOf(Stage.VLC_SW) else emptySet()) +
                softwareHdExcluded
        val next = PlaybackRoutingPolicy.nextStage(
            mode = mode,
            decoderMode = decoderMode,
            current = current,
            failure = reason,
            triedStages = unavailableAwareTriedStages,
        ) ?: VlcHardwareDevicePolicy.fallbackAfterHardwareSubstitution(
            mode = mode,
            current = current,
            failure = reason,
            triedStages = unavailableAwareTriedStages,
            bypassVlcHardware = bypassVlcHardware,
        )
        return Resolved(next, softwareHdExcluded)
    }

    fun isHevcCodec(codec: String?): Boolean {
        val normalized = codec?.trim()?.lowercase().orEmpty()
        return normalized.contains("hevc") ||
            normalized.contains("h265") ||
            normalized.contains("h.265") ||
            normalized.contains("hev1") ||
            normalized.contains("hvc1")
    }
}
