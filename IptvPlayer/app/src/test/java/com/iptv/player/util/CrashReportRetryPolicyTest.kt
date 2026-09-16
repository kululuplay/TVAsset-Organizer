package com.iptv.player.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportRetryPolicyTest {

    @Test
    fun `a fresh report and early failures are retried`() {
        assertTrue(CrashReportRetryPolicy.shouldAttempt(0))
        assertTrue(CrashReportRetryPolicy.shouldAttempt(CrashReportRetryPolicy.MAX_ATTEMPTS - 1))
    }

    @Test
    fun `retries stop at the bound`() {
        assertFalse(CrashReportRetryPolicy.shouldAttempt(CrashReportRetryPolicy.MAX_ATTEMPTS))
        assertFalse(CrashReportRetryPolicy.shouldAttempt(CrashReportRetryPolicy.MAX_ATTEMPTS + 5))
        assertFalse(CrashReportRetryPolicy.shouldAttempt(-1))
    }
}
