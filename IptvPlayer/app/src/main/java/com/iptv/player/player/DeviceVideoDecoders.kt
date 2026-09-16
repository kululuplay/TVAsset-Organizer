package com.iptv.player.player

import android.media.MediaCodecList

/**
 * One MediaCodecList(ALL_CODECS) scan per process. Decoder names are a firmware
 * property; every controller construction used to repeat the (main-thread,
 * vendor-slow) enumeration. Shared by the VLC-hardware bypass and the Exo
 * tunneling decision so both see the same Amlogic verdict.
 */
internal object DeviceVideoDecoders {

    val names: List<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS)
                .codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .mapNotNull { codec ->
                    runCatching {
                        codec.name.takeIf {
                            codec.supportedTypes.any { type ->
                                type.startsWith("video/", ignoreCase = true)
                            }
                        }
                    }.getOrNull()
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    val bypassVlcHardware: Boolean by lazy {
        VlcHardwareDevicePolicy.shouldBypassVlcHardware(names)
    }
}
