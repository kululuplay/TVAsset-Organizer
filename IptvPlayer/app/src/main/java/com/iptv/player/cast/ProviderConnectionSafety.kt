package com.iptv.player.cast

/**
 * Process-wide fail-closed gate for ambiguous Cast/native ownership.
 *
 * A timed-out libVLC stop can continue inside vendor JNI and a lost Cast load
 * result can mean the receiver is already fetching media. Opening another local
 * player in either state can violate a one-connection IPTV subscription. Remote
 * uncertainty is cleared by a definitive Cast session end; an unconfirmed native
 * stop deliberately requires a process restart because no safe completion proof
 * remains after the native operation is quarantined.
 */
internal object ProviderConnectionSafety {

    enum class Uncertainty { REMOTE_LOAD_RESULT, LOCAL_NATIVE_STOP }

    @Volatile
    private var uncertainty: Uncertainty? = null

    val newConnectionAllowed: Boolean
        get() = uncertainty == null

    @Synchronized
    fun block(reason: Uncertainty) {
        if (uncertainty == Uncertainty.LOCAL_NATIVE_STOP) return
        uncertainty = reason
    }

    @Synchronized
    fun resolveDefinitiveCastEnd() {
        if (uncertainty == Uncertainty.REMOTE_LOAD_RESULT) uncertainty = null
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

    internal fun currentUncertainty(): Uncertainty? = uncertainty

    @Synchronized
    internal fun resetForTests() {
        uncertainty = null
    }
}
