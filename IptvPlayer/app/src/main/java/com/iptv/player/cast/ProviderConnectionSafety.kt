package com.iptv.player.cast

/**
 * Process-wide fail-closed gate for ambiguous Cast/native ownership.
 *
 * A timed-out libVLC stop can continue inside vendor JNI and a lost Cast load
 * result can mean the receiver is already fetching media. Opening another local
 * player in either state can violate a one-connection IPTV subscription. Remote
 * uncertainty is cleared by a definitive Cast session end. Local owners use
 * exact tokens; a token is either resolved by proven native shutdown or escalated
 * to controlled main-process recovery without ever failing open.
 */
internal object ProviderConnectionSafety {

    enum class Uncertainty { REMOTE_LOAD_RESULT, LOCAL_NATIVE_STOP }

    data class Snapshot internal constructor(
        val remoteUncertain: Boolean,
        val activeRemoteOwnerCount: Int,
        val localPendingCount: Int,
        val localProcessRecoveryRequired: Boolean,
    ) {
        val newConnectionAllowed: Boolean
            get() =
                !remoteUncertain &&
                    activeRemoteOwnerCount == 0 &&
                    localPendingCount == 0

        /** A local process recycle is safe only when no Cast receiver may own media. */
        val canRecoverLocalProcess: Boolean
            get() =
                localProcessRecoveryRequired &&
                    !remoteUncertain &&
                    activeRemoteOwnerCount == 0
    }

    private var remoteUncertain = false
    private var unownedLocalProcessRecoveryRequired = false
    private var nextRemoteOwnerToken = 1L
    private val activeRemoteOwnerTokens = linkedSetOf<Long>()
    private var nextLocalToken = 1L
    /** token -> whether its native owner exceeded the liveness/release contract. */
    private val localNativeStopTokens = linkedMapOf<Long, Boolean>()

    val newConnectionAllowed: Boolean
        @Synchronized get() = snapshotLocked().newConnectionAllowed

    @Synchronized
    fun block(reason: Uncertainty) {
        when (reason) {
            Uncertainty.REMOTE_LOAD_RESULT -> remoteUncertain = true
            // This is reserved for a local ambiguity that has no concrete owner
            // token (for example a lifecycle hand-off lost before registration).
            // Playback entry points convert it to a controlled process recovery.
            Uncertainty.LOCAL_NATIVE_STOP ->
                unownedLocalProcessRecoveryRequired = true
        }
    }

    /** Register one timed-out native owner whose provider socket may still be open. */
    @Synchronized
    fun beginLocalNativeStopUncertainty(): Long {
        val token = nextLocalToken++
        localNativeStopTokens[token] = false
        return token
    }

    /** Register a receiver that definitively accepted and now owns playback. */
    @Synchronized
    fun beginRemoteOwnership(): Long {
        val token = nextRemoteOwnerToken++
        activeRemoteOwnerTokens += token
        return token
    }

    @Synchronized
    fun resolveDefinitiveRemoteOwnership(token: Long) {
        if (token > 0L) activeRemoteOwnerTokens.remove(token)
    }

    /** Controller detach loses callbacks; retain fail-closed remote uncertainty. */
    @Synchronized
    fun transferRemoteOwnershipToUncertainty(token: Long) {
        // A late detach after definitive Cast end must not resurrect ambiguity.
        if (token > 0L && activeRemoteOwnerTokens.remove(token)) {
            remoteUncertain = true
        }
    }

    /** Mark a timed-out/failed owner as requiring process recovery if it stays open. */
    @Synchronized
    fun requireLocalProcessRecovery(token: Long) {
        if (token > 0L) {
            // A late timeout callback must never resurrect an owner whose exact
            // token was already resolved by definitive release.
            if (localNativeStopTokens.containsKey(token)) {
                localNativeStopTokens[token] = true
            }
        } else {
            unownedLocalProcessRecoveryRequired = true
        }
    }

    /** A drained native queue with unresolved tokens has no remaining proof path. */
    @Synchronized
    fun requireProcessRecoveryForPendingLocalStops() {
        // Snapshot->queue-drain->escalate races with a successful release. An
        // already-empty set is success, not an unowned ambiguity.
        localNativeStopTokens.keys.toList().forEach { token ->
            localNativeStopTokens[token] = true
        }
    }

    /**
     * Called only by the retired worker after stop/release returned without an
     * error. A counter prevents one old completion from clearing a newer timeout.
     */
    @Synchronized
    fun resolveDefinitiveLocalStop(token: Long) {
        if (token > 0L) localNativeStopTokens.remove(token)
    }

    @Synchronized
    fun resolveDefinitiveCastEnd() {
        remoteUncertain = false
        // One CastSession is process-global. Its definitive end proves every
        // per-screen token for that receiver is obsolete, including tokens from
        // controllers detached before the end callback reached a newer screen.
        activeRemoteOwnerTokens.clear()
    }

    /**
     * Converts per-screen Cast state into a process-wide safety decision before
     * its SDK listener is detached. A local native stop that is still running is
     * stronger than remote uncertainty because even a later Cast end cannot
     * prove that the quarantined JNI call released its provider socket.
     */
    internal fun uncertaintyForControllerDetach(
        localQuiesceInFlight: Boolean,
        receiverLoadSubmitted: Boolean,
        sessionSuspended: Boolean,
        receiverOwnsPlayback: Boolean,
    ): Uncertainty? = when {
        localQuiesceInFlight -> Uncertainty.LOCAL_NATIVE_STOP
        receiverLoadSubmitted || sessionSuspended || receiverOwnsPlayback ->
            Uncertainty.REMOTE_LOAD_RESULT
        else -> null
    }

    @Synchronized
    internal fun currentUncertainty(): Uncertainty? = when {
        unownedLocalProcessRecoveryRequired || localNativeStopTokens.isNotEmpty() ->
            Uncertainty.LOCAL_NATIVE_STOP
        remoteUncertain || activeRemoteOwnerTokens.isNotEmpty() ->
            Uncertainty.REMOTE_LOAD_RESULT
        else -> null
    }

    @Synchronized
    fun snapshot(): Snapshot = snapshotLocked()

    private fun snapshotLocked(): Snapshot = Snapshot(
        remoteUncertain = remoteUncertain,
        activeRemoteOwnerCount = activeRemoteOwnerTokens.size,
        localPendingCount =
            localNativeStopTokens.size +
                if (unownedLocalProcessRecoveryRequired) 1 else 0,
        localProcessRecoveryRequired =
            unownedLocalProcessRecoveryRequired ||
                localNativeStopTokens.values.any { it },
    )

    @Synchronized
    internal fun resetForTests() {
        remoteUncertain = false
        unownedLocalProcessRecoveryRequired = false
        nextRemoteOwnerToken = 1L
        activeRemoteOwnerTokens.clear()
        nextLocalToken = 1L
        localNativeStopTokens.clear()
    }
}
