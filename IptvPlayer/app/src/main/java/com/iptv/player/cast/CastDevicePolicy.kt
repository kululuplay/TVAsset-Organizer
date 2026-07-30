package com.iptv.player.cast

/**
 * Cast is a sender feature for phones/tablets. Initialising the Cast SDK on an
 * Android TV/Fire TV playback device starts MediaRouter polling even when the
 * user never casts, repeatedly touching the active HDMI route during playback.
 */
internal object CastDevicePolicy {
    fun shouldInitialize(isTelevision: Boolean): Boolean = !isTelevision
}
