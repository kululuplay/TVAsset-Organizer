package com.iptv.player.player

import com.iptv.player.playback.core.PlaybackFailure

/** Typed source errors never become decoder evidence merely by repeating. */
internal object LiveSourceRecoveryPolicy {
    enum class Action { STOP, RECONNECT_SOURCE, RECOVER_PLAYER }

    fun retryDelayMs(failure: PlaybackFailure?, backoffMs: Long): Long =
        if (failure?.code == PlaybackFailure.Code.HTTP_RATE_LIMITED) maxOf(backoffMs, 30_000L) else backoffMs

    fun action(failure: PlaybackFailure): Action = when {
        failure.retryAdvice == PlaybackFailure.RetryAdvice.DO_NOT_RETRY ||
            LiveTransportPolicy.isAuthoritativeHttpFailure(failure.httpStatus) -> Action.STOP
        failure.component == PlaybackFailure.Component.TRANSPORT -> Action.RECONNECT_SOURCE
        failure.category == PlaybackFailure.Category.NETWORK -> Action.RECONNECT_SOURCE
        else -> Action.RECOVER_PLAYER
    }

    fun canRecoverOnNetworkReturn(failure: PlaybackFailure?): Boolean = failure != null &&
        action(failure) == Action.RECONNECT_SOURCE && failure.code in setOf(
            PlaybackFailure.Code.NETWORK_UNAVAILABLE, PlaybackFailure.Code.DNS_LOOKUP_FAILED,
            PlaybackFailure.Code.CONNECTION_FAILED, PlaybackFailure.Code.CONNECTION_RESET,
            PlaybackFailure.Code.READ_TIMEOUT, PlaybackFailure.Code.END_OF_STREAM,
        )
}

/** Foreground network edge gate and most useful known failure for this request. */
internal class LiveFailureRecovery {
    var failure: PlaybackFailure? = null
        private set
    private var enabled = false
    private var online: Boolean? = null

    fun beginRequest() { enabled = true; failure = null }
    fun suspend() { enabled = false }
    fun stable() { failure = null }
    fun terminal() { enabled = LiveSourceRecoveryPolicy.canRecoverOnNetworkReturn(failure) }

    fun record(value: PlaybackFailure) {
        // A deadline explains that recovery ended, not why the server rejected
        // earlier requests. Preserve concrete evidence instead of overwriting it.
        if (failure == null || (value.category != PlaybackFailure.Category.UNKNOWN &&
                value.code !in setOf(PlaybackFailure.Code.STARTUP_TIMEOUT, PlaybackFailure.Code.PLAYBACK_STALL))) {
            failure = value
        }
    }

    fun networkChanged(available: Boolean, needsRecovery: Boolean): Boolean {
        val returned = online == false && available
        online = available
        return returned && enabled && needsRecovery && LiveSourceRecoveryPolicy.canRecoverOnNetworkReturn(failure)
    }
}
