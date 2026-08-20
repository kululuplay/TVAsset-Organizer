package com.iptv.player.util

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ExitReasonPolicyTest {

    @Test
    fun `native crash and anr keep distinct support categories`() {
        assertEquals(
            "native_crash",
            ExitReasonPolicy.classify(ApplicationExitInfo.REASON_CRASH_NATIVE).type,
        )
        assertEquals(
            "os_anr",
            ExitReasonPolicy.classify(ApplicationExitInfo.REASON_ANR).type,
        )
    }

    @Test
    fun `low memory exit is a warning rather than an app crash`() {
        val result = ExitReasonPolicy.classify(ApplicationExitInfo.REASON_LOW_MEMORY)

        assertEquals("low_memory_exit", result.type)
        assertEquals("warning", result.severity)
    }

    @Test
    fun `generic signal exit is not mislabeled as native crash`() {
        val result = ExitReasonPolicy.classify(ApplicationExitInfo.REASON_SIGNALED)

        assertEquals("signaled_exit", result.type)
        assertEquals("warning", result.severity)
    }

    @Test
    fun `unknown legacy reason remains conservative`() {
        assertEquals(
            "suspected_abnormal_exit",
            ExitReasonPolicy.classify(null).type,
        )
    }
}
