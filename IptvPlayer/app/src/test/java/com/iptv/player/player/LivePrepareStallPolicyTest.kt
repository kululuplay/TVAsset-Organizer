package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePrepareStallPolicyTest {

    private val mib = 1L shl 20

    @Test
    fun `bytes flowing for eight seconds without READY abandon the engine`() {
        assertTrue(
            LivePrepareStallPolicy.shouldAbandon(
                nowMs = 18_000L, firstBytesAtMs = 10_000L, bytesReceived = 3 * mib,
                ready = false, firstFrame = false, failureReported = false,
            ),
        )
    }

    @Test
    fun `a slow link with few bytes is left to the deadline`() {
        assertFalse(
            LivePrepareStallPolicy.shouldAbandon(
                nowMs = 30_000L, firstBytesAtMs = 10_000L, bytesReceived = mib / 2,
                ready = false, firstFrame = false, failureReported = false,
            ),
        )
    }

    @Test
    fun `nothing fires before the bound or without any bytes`() {
        assertFalse(
            LivePrepareStallPolicy.shouldAbandon(
                nowMs = 17_999L, firstBytesAtMs = 10_000L, bytesReceived = 5 * mib,
                ready = false, firstFrame = false, failureReported = false,
            ),
        )
        assertFalse(
            LivePrepareStallPolicy.shouldAbandon(
                nowMs = 60_000L, firstBytesAtMs = 0L, bytesReceived = 0L,
                ready = false, firstFrame = false, failureReported = false,
            ),
        )
    }

    @Test
    fun `READY a first frame or an earlier failure stop it`() {
        assertFalse(
            LivePrepareStallPolicy.shouldAbandon(
                nowMs = 20_000L, firstBytesAtMs = 10_000L, bytesReceived = 5 * mib,
                ready = true, firstFrame = false, failureReported = false,
            ),
        )
        assertFalse(
            LivePrepareStallPolicy.shouldAbandon(
                nowMs = 20_000L, firstBytesAtMs = 10_000L, bytesReceived = 5 * mib,
                ready = false, firstFrame = true, failureReported = false,
            ),
        )
        assertFalse(
            LivePrepareStallPolicy.shouldAbandon(
                nowMs = 20_000L, firstBytesAtMs = 10_000L, bytesReceived = 5 * mib,
                ready = false, firstFrame = false, failureReported = true,
            ),
        )
    }
}
