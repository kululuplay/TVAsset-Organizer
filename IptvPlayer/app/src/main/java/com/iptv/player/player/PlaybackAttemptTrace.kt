package com.iptv.player.player

import java.util.UUID

/** Closed fields only: never accept a URL, exception message, account or token. */
internal class PlaybackAttemptTrace(private val startedAtMs: Long) {
    val id: String = UUID.randomUUID().toString()
    enum class Phase { REQUESTED, WAITING_OWNER, SUBMITTED, CONNECTING, FIRST_BYTES, READY, FRAME, STABLE, FAILURE, TIMEOUT, TERMINAL, CANCELLED }
    private val recorded = mutableSetOf<Phase>()

    fun event(phase: Phase, nowMs: Long, httpStatus: Int? = null): String? {
        if (!recorded.add(phase)) return null
        val status = httpStatus?.takeIf { it in 100..599 }
        return "attempt=$id phase=${phase.name} elapsedMs=${(nowMs - startedAtMs).coerceAtLeast(0)}" +
            (status?.let { " http=$it" } ?: "")
    }

    companion object {
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        fun safeId(value: String?): String? = value?.takeIf(ID::matches)
    }
}
