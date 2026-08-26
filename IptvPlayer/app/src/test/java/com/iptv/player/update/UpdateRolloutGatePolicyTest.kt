package com.iptv.player.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateRolloutGatePolicyTest {

    @Test
    fun `older receiver without endpoint keeps updates available`() {
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Allow,
            UpdateRolloutGatePolicy.parse(404, null),
        )
    }

    @Test
    fun `managed hold preserves stable fallback version`() {
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Hold("1.5.82"),
            UpdateRolloutGatePolicy.parse(
                200,
                """{"decision":"hold","stableVersion":"1.5.82"}""",
            ),
        )
    }

    @Test
    fun `malformed or failed policy fails closed`() {
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Hold(null),
            UpdateRolloutGatePolicy.parse(500, null),
        )
        assertEquals(
            UpdateRolloutGatePolicy.Outcome.Hold(null),
            UpdateRolloutGatePolicy.parse(200, "not-json"),
        )
    }
}
