package com.iptv.player.ui.player

/** One-shot token gate for duplicate/late EndReached callbacks. */
internal class VodNextEpisodeGate {
    private enum class State { IDLE, PENDING, CONSUMED, CANCELED }

    private var token = Long.MIN_VALUE
    private var state = State.IDLE

    fun arm(completionToken: Long): Boolean {
        if (completionToken == token && state != State.IDLE) return false
        token = completionToken
        state = State.PENDING
        return true
    }

    fun isPending(completionToken: Long): Boolean =
        token == completionToken && state == State.PENDING

    fun consume(completionToken: Long): Boolean {
        if (!isPending(completionToken)) return false
        state = State.CONSUMED
        return true
    }

    fun cancel() {
        if (state == State.PENDING) state = State.CANCELED
    }
}
