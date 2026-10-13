package com.iptv.player.player

import android.content.Context
import com.iptv.player.cast.ProviderConnectionSafety
import com.iptv.player.cast.ProviderStartGatePolicy
import com.iptv.player.cast.ProviderStartGatePolicy.Decision
import com.iptv.player.util.PlaybackLog
import com.iptv.player.util.StabilityTelemetry

/**
 * The bounded half of the provider-connection gate (1.6.2).
 *
 * [ProviderStartGatePolicy] says what a snapshot means; this object turns the
 * two outcomes that used to be dead ends into progress:
 *
 *  - [forceOpen] clears the gate ([ProviderConnectionSafety.forceReset]) and
 *    writes one log line plus a `provider_gate_forced` stability event, so the
 *    panel shows how often a device needed it and why.
 *  - [settleAfterWait] is the decision after one
 *    [ProviderStartGatePolicy.DRAIN_WAIT_MS] wait behind the native queue: a
 *    queue that did not drain in time is forced open, never waited for again.
 *
 * Only RECOVER_LOCAL_PROCESS is left to the caller, because launching the
 * controlled process recycle needs an Activity. Every caller ends in
 * [forceOpen] when that launch does not happen or does not replace the
 * process; nothing waits for a Force Stop any more.
 */
internal object ProviderStartGate {

    const val TELEMETRY_TYPE = "provider_gate_forced"

    /**
     * Clear every pending ownership state and allow the new connection. Returns
     * READY so a caller can continue as if the gate had been open. [where]
     * names the entry point and [why] the reason; both reach the playback log
     * and the panel when something was actually cleared.
     */
    fun forceOpen(context: Context, where: String, why: String): Decision {
        val cleared = ProviderConnectionSafety.forceReset()
        if (cleared != null) {
            val detail = "$where: $why -> cleared $cleared"
            PlaybackLog.log(context, "ProviderGate", "forced open ($detail)")
            StabilityTelemetry.record(
                type = TELEMETRY_TYPE,
                severity = "warn",
                detail = detail,
            )
        }
        return Decision.READY
    }

    /**
     * Decision after one bounded wait. [drained] is false when the wait timed
     * out: the queue is then still busy with an ordinary stop, which the server
     * supersedes the moment the new request arrives. Returns READY (possibly
     * after a forced reset) or RECOVER_LOCAL_PROCESS.
     */
    fun settleAfterWait(context: Context, where: String, drained: Boolean): Decision =
        when (
            val decision =
                ProviderStartGatePolicy.decideAfterWait(ProviderConnectionSafety.snapshot())
        ) {
            Decision.PROCEED_AFTER_RESET -> forceOpen(
                context,
                where,
                if (drained) {
                    "queue drained without proof"
                } else {
                    "drain timeout ${ProviderStartGatePolicy.DRAIN_WAIT_MS} ms"
                },
            )
            else -> decision
        }
}
