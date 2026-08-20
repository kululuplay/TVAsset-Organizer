package com.iptv.player.cast

/**
 * Process-wide fail-closed gate for ambiguous Cast/native ownership.
 *
 * A timed-out libVLC stop can continue inside vendor JNI and a lost Cast load
 * result can mean the receiver is already fetching media. Opening another local
 * player in either state can violate a one-connection IPTV subscription. Remote
 * uncertainty is cleared by a definitive Cast session end; an unconfirmed native
 * stop remains blocked until the exact retired worker proves that cleanup ended.
 */
internal object ProviderConnectionSafety {

    enum class Uncertainty { REMOTE_LOAD_RESULT, LOCAL_NATIVE_STOP }

    @Volatile private var remoteUncertain = false
    @Volatile private var legacyLocalNativeStopUncertain = false
    private var nextLocalToken = 1L
    private val localNativeStopTokens = linkedSetOf<Long>()

    val newConnectionAllowed: Boolean
        @Synchronized get() =
            !remoteUncertain &&
                !legacyLocalNativeStopUncertain &&
                localNativeStopTokens.isEmpty()

    @Synchronized
    fun block(reason: Uncertainty) {
        when (reason) {
            Uncertainty.REMOTE_LOAD_RESULT -> remoteUncertain = true
            // Legacy callers intentionally have no proof callback. Keep their
            // uncertainty independent from tokenized owners until process restart.
            Uncertainty.LOCAL_NATIVE_STOP -> legacyLocalNativeStopUncertain = true
        }
    }

    /** Register one timed-out native owner whose provider socket may still be open. */
    @Synchronized
    fun beginLocalNativeStopUncertainty(): Long {
        val token = nextLocalToken++
        localNativeStopTokens += token
        return token
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
        legacyLocalNativeStopUncertain || localNativeStopTokens.isNotEmpty() ->
            Uncertainty.LOCAL_NATIVE_STOP
        remoteUncertain -> Uncertainty.REMOTE_LOAD_RESULT
        else -> null
    }

    @Synchronized
    internal fun resetForTests() {
        remoteUncertain = false
        legacyLocalNativeStopUncertain = false
        nextLocalToken = 1L
        localNativeStopTokens.clear()
    }
}
