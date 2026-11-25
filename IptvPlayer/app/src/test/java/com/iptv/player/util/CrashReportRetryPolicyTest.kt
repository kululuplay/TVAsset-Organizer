package com.iptv.player.util

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `only uploads of the last day count against the budget`() {
        val now = 10 * CrashReportRetryPolicy.DAY_MS
        val sent = listOf(now - CrashReportRetryPolicy.DAY_MS, now - 1_000L, now, now + 5_000L)
        // Older than a day, and an entry from a clock that was ahead, both drop out.
        assertEquals(listOf(now - 1_000L, now), CrashReportRetryPolicy.recentUploads(sent, now))
    }

    @Test
    fun `only a native crash or an ANR is a system exit worth a report`() {
        assertEquals("native_crash", CrashReportRetryPolicy.osExitKind(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("anr", CrashReportRetryPolicy.osExitKind(ApplicationExitInfo.REASON_ANR))
        // A Java crash already has its own report with the stack trace.
        assertNull(CrashReportRetryPolicy.osExitKind(ApplicationExitInfo.REASON_CRASH))
        assertNull(CrashReportRetryPolicy.osExitKind(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertNull(CrashReportRetryPolicy.osExitKind(ApplicationExitInfo.REASON_USER_REQUESTED))
    }
}
