package com.iptv.player.cast

/**
 * One decision model for every local playback entry point.
 *
 * Ordinary asynchronous VLC teardown is a short WAIT, never a customer-facing
 * error. A proven native hang asks for a controlled process recycle. Everything
 * else the app cannot prove is PROCEED_AFTER_RESET: the entry point clears the
 * gate (ProviderConnectionSafety.forceReset) and connects, because the server
 * replaces a stale lease from the same device itself. Until 1.6.1 the remote
 * states were a permanent fail-closed error that only a Force Stop ended.
 *
 * BLOCKED and BLOCKED_BY_REMOTE_OWNER are screen-level outcomes (audio focus
 * refused, a Cast session that this screen is driving); [decide] never returns
 * them.
 */
internal object ProviderStartGatePolicy {

    /** Upper bound for one wait behind the shared native queue (ms). */
    const val DRAIN_WAIT_MS = 1_000L

    enum class Decision {
        READY,
        WAIT_FOR_LOCAL_CLEANUP,
        RECOVER_LOCAL_PROCESS,
        PROCEED_AFTER_RESET,
        BLOCKED_BY_REMOTE_OWNER,
        BLOCKED,
    }

    fun decide(safety: ProviderConnectionSafety.Snapshot): Decision = when {
        safety.newConnectionAllowed -> Decision.READY
        safety.localProcessRecoveryRequired -> Decision.RECOVER_LOCAL_PROCESS
        safety.localPendingCount > 0 -> Decision.WAIT_FOR_LOCAL_CLEANUP
        else -> Decision.PROCEED_AFTER_RESET
    }

    /**
     * The decision after one bounded wait: a queue that still has pending stops
     * is not waited for again. Returns READY, RECOVER_LOCAL_PROCESS or
     * PROCEED_AFTER_RESET.
     */
    fun decideAfterWait(safety: ProviderConnectionSafety.Snapshot): Decision =
        when (val decision = decide(safety)) {
            Decision.WAIT_FOR_LOCAL_CLEANUP -> Decision.PROCEED_AFTER_RESET
            else -> decision
        }
}
