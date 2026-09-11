package com.iptv.player.ui.player

import org.junit.Assert.*
import org.junit.Test

class WatchTimeAccumulatorTest {
    @Test fun countsActualAdvancementAndDrainsExactlyOnce() {
        val a = WatchTimeAccumulator()
        (0L..10L).forEach { a.sample(it * 500, it * 500, true) }
        assertEquals(5000L, a.drain())
        assertEquals(0L, a.drain())
    }
    @Test fun seekingForwardAndBackDoesNotCreateWatchTime() {
        val a = WatchTimeAccumulator()
        a.sample(0, 0, true)
        a.sample(500, 120000, true)
        a.sample(1000, 30000, true)
        assertEquals(0L, a.drain())
        a.sample(1500, 30500, true)
        assertEquals(500L, a.drain())
    }
    @Test fun backgroundPauseAndStallAreNotViewing() {
        val a = WatchTimeAccumulator()
        a.sample(0, 0, true)
        a.sample(500, 500, false)
        a.sample(60000, 500, true)
        a.sample(60500, 1000, true)
        a.sample(80000, 1000, true)
        a.sample(80500, 1500, true)
        assertEquals(1000L, a.drain())
    }
    @Test fun repeatedCachedVlcSnapshotsDoNotLoseHalfTheWatchTime() {
        val a = WatchTimeAccumulator()
        (0L..20L).forEach { a.sample(it * 500, (it / 2) * 1000, true) }
        assertEquals(10000L, a.drain())
    }
    @Test fun farAheadResumePositionAloneIsNotAViewingSignal() {
        val a = WatchTimeAccumulator()
        a.sample(0, 3_600_000, true)
        a.sample(500, 3_600_000, true)
        a.sample(1000, 3_600_000, false)
        assertEquals(0L, a.drain())
    }
}
