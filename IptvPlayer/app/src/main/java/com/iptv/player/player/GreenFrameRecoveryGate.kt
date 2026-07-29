package com.iptv.player.player

/**
 * Pure state machine used by [SurfaceFrameHealthMonitor].
 *
 * Hardware decoders on some TV SoCs output green buffers while their format is
 * settling, then recover without intervention after a few seconds. Treating the
 * first two green captures as a permanent failure restarts a decoder that was
 * about to become healthy. This gate therefore:
 *
 *  - confirms a healthy picture quickly with two fresh, non-green samples;
 *  - tolerates the known transient green startup window;
 *  - resets every pending green decision as soon as a fresh healthy sample lands;
 *  - still detects a new, persistent green failure after playback was healthy.
 */
internal class GreenFrameRecoveryGate(
    private val startupGreenGraceMs: Long = STARTUP_GREEN_GRACE_MS,
    private val steadyGreenFailureMs: Long = STEADY_GREEN_FAILURE_MS,
    private val healthyConfirmationMs: Long = HEALTHY_CONFIRMATION_MS,
    private val requiredGreenSamples: Int = REQUIRED_GREEN_SAMPLES,
    private val requiredHealthySamples: Int = REQUIRED_HEALTHY_SAMPLES,
) {
    enum class Decision {
        WAIT,
        FIRST_HEALTHY_FRAME,
        SOLID_GREEN_FAILURE,
    }

    var hasHealthyFrame: Boolean = false
        private set

    private var greenSinceMs = UNSET
    private var healthySinceMs = UNSET
    private var consecutiveGreenSamples = 0
    private var consecutiveHealthySamples = 0
    private var failureReported = false

    fun reset() {
        hasHealthyFrame = false
        greenSinceMs = UNSET
        healthySinceMs = UNSET
        consecutiveGreenSamples = 0
        consecutiveHealthySamples = 0
        failureReported = false
    }

    fun onSample(solidGreen: Boolean, nowMs: Long): Decision {
        if (failureReported) return Decision.WAIT

        if (!solidGreen) {
            // A fresh healthy capture always cancels green evidence. This is the
            // important stale-result guard for a decoder that just settled.
            greenSinceMs = UNSET
            consecutiveGreenSamples = 0

            if (hasHealthyFrame) return Decision.WAIT
            if (healthySinceMs == UNSET) healthySinceMs = nowMs
            consecutiveHealthySamples++
            if (
                consecutiveHealthySamples >= requiredHealthySamples &&
                nowMs - healthySinceMs >= healthyConfirmationMs
            ) {
                hasHealthyFrame = true
                return Decision.FIRST_HEALTHY_FRAME
            }
            return Decision.WAIT
        }

        healthySinceMs = UNSET
        consecutiveHealthySamples = 0
        if (greenSinceMs == UNSET) greenSinceMs = nowMs
        consecutiveGreenSamples++

        val requiredDuration =
            if (hasHealthyFrame) steadyGreenFailureMs else startupGreenGraceMs
        if (
            consecutiveGreenSamples >= requiredGreenSamples &&
            nowMs - greenSinceMs >= requiredDuration
        ) {
            failureReported = true
            return Decision.SOLID_GREEN_FAILURE
        }
        return Decision.WAIT
    }

    private companion object {
        private const val UNSET = -1L
        private const val STARTUP_GREEN_GRACE_MS = 7_000L
        private const val STEADY_GREEN_FAILURE_MS = 1_200L
        private const val HEALTHY_CONFIRMATION_MS = 150L
        private const val REQUIRED_GREEN_SAMPLES = 2
        private const val REQUIRED_HEALTHY_SAMPLES = 2
    }
}
