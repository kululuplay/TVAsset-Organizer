package com.iptv.player.util

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchExitPolicyTest {

    @Test
    fun `benign os exits do not count as launch crashes`() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        )) {
            assertFalse("reason=$reason", LaunchExitPolicy.isCrash(reason))
        }
    }

    @Test
    fun `crashes anrs and unknown exits still count`() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_EXIT_SELF,
        )) {
            assertTrue("reason=$reason", LaunchExitPolicy.isCrash(reason))
        }
        // No OS record at all keeps the conservative behaviour.
        assertTrue(LaunchExitPolicy.isCrash(null))
    }
}
