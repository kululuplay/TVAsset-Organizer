package com.iptv.player.player

/**
 * Pure decision for a source failure while opening a live stream.
 *
 * A 5xx answer (panel cold start, overload, upstream down) is the server's
 * problem: swapping the decoder cannot repair it, and the old behaviour (two
 * Exo attempts, then the compatibility ladder) parked the channel on the
 * software route where the same 503 kept the retry loop going. Such failures
 * stay on the current stage and use the ordinary reconnect backoff.
 */
internal object StartupErrorRoutingPolicy {

    fun retrySameStage(httpStatus: Int?): Boolean =
        httpStatus != null && httpStatus in 500..599
}
