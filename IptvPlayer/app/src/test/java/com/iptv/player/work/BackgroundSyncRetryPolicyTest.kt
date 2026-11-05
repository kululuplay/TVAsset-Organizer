package com.iptv.player.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncRetryPolicyTest {

    @Test
    fun `retries are bounded to protect Android 14 plus WorkManager quotas`() {
        assertTrue(BackgroundSyncRetryPolicy.shouldRetry(0))
        assertTrue(BackgroundSyncRetryPolicy.shouldRetry(1))
        assertTrue(BackgroundSyncRetryPolicy.shouldRetry(3))
        assertFalse(BackgroundSyncRetryPolicy.shouldRetry(4))
        assertFalse(BackgroundSyncRetryPolicy.shouldRetry(10))
    }
}
