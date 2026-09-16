package com.iptv.player.player

/**
 * Media3 video tunneling is opt-in per device class. It is only ever enabled
 * when the remote policy says so for this device AND the box has no Amlogic
 * video decoder (tunneling greens/blacks the Amlogic underlay), and it is
 * withdrawn for the rest of the stream after one tunneled no-first-frame retry.
 */
internal object ExoTunnelingPolicy {

    fun shouldEnable(
        remoteOptIn: Boolean?,
        videoDecoderNames: Iterable<String>,
        retryWithoutTunnelingUsed: Boolean,
    ): Boolean =
        remoteOptIn == true &&
            !retryWithoutTunnelingUsed &&
            !VlcHardwareDevicePolicy.shouldBypassVlcHardware(videoDecoderNames)

    /**
     * A tunneled decoder that reached READY but never rendered gets exactly one
     * untunneled replay of the same stage before the ordinary ladder runs.
     */
    fun shouldRetryWithoutTunneling(
        tunnelingActive: Boolean,
        retryWithoutTunnelingUsed: Boolean,
    ): Boolean = tunnelingActive && !retryWithoutTunnelingUsed
}
