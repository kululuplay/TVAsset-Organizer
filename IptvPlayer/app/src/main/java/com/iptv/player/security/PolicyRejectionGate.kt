/*
 * PolicyRejectionGate.kt
 * Rate limits what the heartbeat does when a remote playback policy arrives
 * unsigned or with a bad signature: one local log line per process, and one
 * `policy_signature_rejected` stability event per hour, so a misconfigured or
 * hostile server cannot flood the spool (60 beats/hour) yet the panel still
 * sees the rejection. Pure; safe from any thread.
 */
package com.iptv.player.security

class PolicyRejectionGate(private val eventIntervalMs: Long = DEFAULT_EVENT_INTERVAL_MS) {

    private val lock = Any()
    private var logged = false
    private var lastEventAtMs: Long? = null

    /** True the first time a rejection happens in this process. */
    fun shouldLog(): Boolean = synchronized(lock) {
        if (logged) return@synchronized false
        logged = true
        true
    }

    /** True at most once per [eventIntervalMs] (first call always true). */
    fun shouldRecordEvent(nowMs: Long): Boolean = synchronized(lock) {
        val last = lastEventAtMs
        if (last != null && nowMs - last in 0 until eventIntervalMs) return@synchronized false
        lastEventAtMs = nowMs
        true
    }

    companion object {
        const val EVENT_TYPE = "policy_signature_rejected"
        const val DEFAULT_EVENT_INTERVAL_MS = 60L * 60L * 1000L
    }
}
