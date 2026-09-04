package com.iptv.player.player

/**
 * Measures recovery from observed clock progress, never from an idle timer.
 *
 * The controller starts this only after a verified TV frame (or radio Playing).
 * Every source/engine interruption invalidates that attempt. A live-window clock
 * reset must prove two forward samples before it may refresh the stall deadline;
 * repeatedly bouncing timestamps therefore cannot masquerade as healthy playback.
 */
internal class LivePlaybackProgressPolicy(
    private val stablePlaybackMs: Long = 10_000L,
    private val stallTimeoutMs: Long = 15_000L,
    private val minimumAdvanceMs: Long = 250L,
    private val maximumSampleGapMs: Long = 6_000L,
) {
    enum class Decision { WAIT, STABLE, STALLED }

    private var active = false
    private var lastPositionMs = -1L
    private var lastProgressAtMs = 0L
    private var stableSinceMs: Long? = null
    private var stableReported = false
    private var discontinuityPending = false
    private var forwardSamplesAfterDiscontinuity = 0

    fun start(nowMs: Long, positionMs: Long) {
        reset()
        active = true
        lastPositionMs = positionMs
        lastProgressAtMs = nowMs
    }

    /** Source end/error, zap and suspension retire all evidence from this attempt. */
    fun reset() {
        active = false
        lastPositionMs = -1L
        lastProgressAtMs = 0L
        stableSinceMs = null
        stableReported = false
        discontinuityPending = false
        forwardSamplesAfterDiscontinuity = 0
    }

    fun onBuffering() {
        stableSinceMs = null
    }

    fun sample(nowMs: Long, positionMs: Long, buffering: Boolean): Decision {
        if (!active) return Decision.WAIT

        var progressed = false
        if (positionMs >= 0L) {
            when {
                lastPositionMs < 0L -> lastPositionMs = positionMs
                positionMs < lastPositionMs - minimumAdvanceMs -> {
                    lastPositionMs = positionMs
                    discontinuityPending = true
                    forwardSamplesAfterDiscontinuity = 0
                    stableSinceMs = null
                }
                positionMs > lastPositionMs + minimumAdvanceMs -> {
                    lastPositionMs = positionMs
                    if (discontinuityPending) {
                        forwardSamplesAfterDiscontinuity++
                        if (forwardSamplesAfterDiscontinuity >= 2) {
                            discontinuityPending = false
                            progressed = true
                        }
                    } else {
                        progressed = true
                    }
                }
            }
        }

        val progressGapMs = nowMs - lastProgressAtMs
        if (progressed) lastProgressAtMs = nowMs
        if (!progressed || buffering || progressGapMs > maximumSampleGapMs) {
            stableSinceMs = null
        }
        if (progressed && !buffering) {
            val sinceMs = stableSinceMs ?: nowMs.also { stableSinceMs = it }
            if (!stableReported && nowMs - sinceMs >= stablePlaybackMs) {
                stableReported = true
                return Decision.STABLE
            }
        }
        if (nowMs - lastProgressAtMs >= stallTimeoutMs) {
            active = false
            return Decision.STALLED
        }
        return Decision.WAIT
    }
}
