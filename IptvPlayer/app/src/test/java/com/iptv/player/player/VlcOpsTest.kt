package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VlcOpsTest {

    @Test
    fun `all release steps run even when stop and player release throw`() {
        val completed = mutableListOf<String>()

        val failures = VlcOps.runAllBestEffort(
            {
                completed += "stop"
                throw IllegalStateException("native stop failed")
            },
            {
                completed += "player-release"
                throw IllegalArgumentException("native player release failed")
            },
            {
                completed += "libvlc-release"
            },
        )

        assertEquals(
            listOf("stop", "player-release", "libvlc-release"),
            completed,
        )
        assertEquals(2, failures.size)
    }

    @Test
    fun `timed out worker cleanup waits until quarantine callback finishes`() {
        val gate = VlcTimeoutGate()
        val cleanupWaiting = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        gate.markTimedOut()

        val retiredWorker = Thread {
            cleanupWaiting.countDown()
            gate.awaitQuarantine()
            cleanupFinished.countDown()
        }
        retiredWorker.start()

        assertTrue(cleanupWaiting.await(1, TimeUnit.SECONDS))
        assertTrue(gate.didTimeOut())
        assertFalse(cleanupFinished.await(100, TimeUnit.MILLISECONDS))

        gate.finishQuarantine()
        assertTrue(cleanupFinished.await(1, TimeUnit.SECONDS))
        retiredWorker.join(1_000L)
        assertFalse(retiredWorker.isAlive)
    }
}
