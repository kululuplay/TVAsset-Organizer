package com.iptv.player.ui.player

import org.junit.Assert.*
import org.junit.Test

class SeekTimelineTest {
    @Test fun rapidPressesAccumulateAgainstLatestTarget() {
        val seek = SeekTimeline()
        repeat(8) { seek.offset(20_000, 10_000, 600_000, it * 50L) }
        assertEquals(100_000L, seek.targetMs)
        assertEquals(100_000L, seek.display(20_400, 900))
    }
    @Test fun rewindToZeroRemainsPendingUntilAcknowledged() {
        val seek = SeekTimeline()
        seek.offset(30_000, -60_000, 600_000, 0)
        seek.markIssued()
        assertEquals(0L, seek.display(30_500, 400))
        assertEquals(700L, seek.display(700, 900))
        assertNull(seek.targetMs)
    }
    @Test fun directionChangesAndBoundsUseRequestedPosition() {
        val seek = SeekTimeline()
        seek.offset(90_000, 60_000, 120_000, 0)
        assertEquals(120_000L, seek.targetMs)
        seek.offset(90_000, -30_000, 120_000, 100)
        assertEquals(90_000L, seek.targetMs)
    }
    @Test fun unissuedAndStaleSamplesCannotAcknowledgeNewSeek() {
        val seek = SeekTimeline()
        seek.request(50_000, 100_000, 0)
        assertEquals(50_000L, seek.display(50_100, 500))
        assertNotNull(seek.targetMs)
        seek.markIssued()
        assertEquals(50_500L, seek.display(50_500, 600))
        assertNull(seek.targetMs)
    }
    @Test fun rejectedSeekEventuallyShowsActualPosition() {
        val seek = SeekTimeline()
        seek.request(50_000, 100_000, 0)
        assertEquals(12_000L, seek.display(12_000, 10_001))
        assertNull(seek.targetMs)
    }
    @Test fun heldRemoteAcceleratesInControlledSteps() {
        assertEquals(10_000L, SeekTimeline.step(0))
        assertEquals(30_000L, SeekTimeline.step(7))
        assertEquals(60_000L, SeekTimeline.step(20))
    }
}
