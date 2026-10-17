package com.iptv.player.player

/**
 * "Exo never opens it, VLC does" detector (1.6.3, rule B, prepare phase).
 *
 * Media3 finishes preparing a progressive MPEG-TS item only after every track
 * the PMT declares has produced its first sample format. A stream whose PMT
 * lists a track that never carries data (a video-only channel with a declared
 * audio PID, a radio PID in a TV program) therefore keeps the socket busy and
 * the spinner on screen until the 45 s request deadline, while libVLC plays
 * the tracks that exist. Bytes keep flowing without READY: after
 * [NO_READY_AFTER_BYTES_MS] with at least [MIN_BYTES] received, the stream is
 * handed to the other hardware engine instead of waiting out the deadline.
 *
 * Slow links and cold starts do not trip it: either READY arrives, or fewer
 * than [MIN_BYTES] arrived (that is a network problem, rule A, left to the
 * deadline and the reconnect loop).
 */
internal object LivePrepareStallPolicy {

    const val NO_READY_AFTER_BYTES_MS = 8_000L
    const val MIN_BYTES = 1L shl 20

    fun shouldAbandon(
        nowMs: Long,
        firstBytesAtMs: Long,
        bytesReceived: Long,
        ready: Boolean,
        firstFrame: Boolean,
        failureReported: Boolean,
    ): Boolean =
        !ready &&
            !firstFrame &&
            !failureReported &&
            firstBytesAtMs > 0L &&
            nowMs - firstBytesAtMs >= NO_READY_AFTER_BYTES_MS &&
            bytesReceived >= MIN_BYTES
}
