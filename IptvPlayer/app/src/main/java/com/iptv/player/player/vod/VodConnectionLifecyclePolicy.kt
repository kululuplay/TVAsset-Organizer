package com.iptv.player.player.vod

/**
 * Decides how a VOD backend must relinquish its provider connection on stop.
 *
 * A backgrounded player may keep its engine instance for fast restoration, but
 * it must stop the current media. A player that is leaving permanently must
 * release the whole backend so a one-connection subscription can be reused by
 * the next movie or episode without waiting for Activity destruction.
 */
internal object VodConnectionLifecyclePolicy {

    enum class Teardown {
        NONE,
        STOP,
        RELEASE,
    }

    fun onStop(
        isFinishing: Boolean,
        engineExists: Boolean,
        connectionMayBeOpen: Boolean,
    ): Teardown = when {
        !engineExists -> Teardown.NONE
        isFinishing -> Teardown.RELEASE
        connectionMayBeOpen -> Teardown.STOP
        else -> Teardown.NONE
    }
}
