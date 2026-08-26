package com.iptv.player.cast

/**
 * One decision model for every local playback entry point.
 *
 * Ordinary asynchronous VLC teardown is WAITING, never a customer-facing error.
 * A proven native hang is recovered by recycling the app process. Remote Cast
 * ownership remains fail-closed because a process recycle would forget the
 * receiver and could open a second provider connection.
 */
internal object ProviderStartGatePolicy {

    enum class Decision {
        READY,
        WAIT_FOR_LOCAL_CLEANUP,
        RECOVER_LOCAL_PROCESS,
        BLOCKED_BY_REMOTE_OWNER,
        BLOCKED,
    }

    fun decide(safety: ProviderConnectionSafety.Snapshot): Decision = when {
        safety.newConnectionAllowed -> Decision.READY
        safety.remoteUncertain || safety.activeRemoteOwnerCount > 0 ->
            Decision.BLOCKED_BY_REMOTE_OWNER
        safety.localProcessRecoveryRequired -> Decision.RECOVER_LOCAL_PROCESS
        safety.localPendingCount > 0 -> Decision.WAIT_FOR_LOCAL_CLEANUP
        else -> Decision.BLOCKED
    }
}
