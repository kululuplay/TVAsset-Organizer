package com.iptv.player.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateRolloutGatePolicyTest {

    @Test
    fun `future policy allows the currently published bootstrap release`() {
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Allow,
            decide(candidate = "1.5.83", target = "1.5.84", percent = 0, paused = true),
        )
    }

    @Test
    fun `exact target holds a paused cohort and preserves stable fallback`() {
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Hold("1.5.83"),
            decide(candidate = "1.5.84", target = "1.5.84", percent = 100, paused = true),
        )
    }

    @Test
    fun `exact target opens a full deterministic cohort`() {
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Allow,
            decide(candidate = "1.5.84", target = "1.5.84", percent = 100),
        )
    }

    @Test
    fun `stale malformed or unavailable policy fails closed`() {
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Hold("1.5.83"),
            decide(candidate = "1.5.85", target = "1.5.84", percent = 100),
        )
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Hold(null),
            UpdateRolloutGatePolicy.decide(404, null, "1.5.84", DEVICE),
        )
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Hold(null),
            UpdateRolloutGatePolicy.decide(200, "not-json", "1.5.84", DEVICE),
        )
    }

    @Test
    fun `emergency opens its exact target despite manual pause`() {
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Allow,
            decide(
                candidate = "1.5.84",
                target = "1.5.84",
                percent = 0,
                paused = true,
                emergency = true,
            ),
        )
    }

    private fun decide(
        candidate: String,
        target: String,
        percent: Int,
        paused: Boolean = false,
        emergency: Boolean = false,
    ): UpdateRolloutGatePolicy.Outcome = UpdateRolloutGatePolicy.decide(
        httpCode = 200,
        body = """{
            "schema":1,
            "targetVersion":"$target",
            "stableVersion":"1.5.83",
            "rolloutPercent":$percent,
            "paused":$paused,
            "emergency":$emergency,
            "salt":"release-$target"
        }""".trimIndent(),
        candidateVersion = candidate,
        deviceId = DEVICE,
    )

    private companion object {
        const val DEVICE = "device-42"
    }
}
