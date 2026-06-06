/*
 * VodPlaybackCoordinatorTest.kt
 * Locks the VOD background-release / foreground-reacquire ordering: onStop
 * persists + records the position then stops the stream (closing the socket);
 * onStart re-opens from exactly that position. The stop always precedes the
 * next start, so the single-connection contract holds across backgrounding.
 */
package com.iptv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class VodPlaybackCoordinatorTest {

    private class FakeActions(var position: Long = 0L) : VodPlaybackCoordinator.Actions {
        val log = mutableListOf<String>()
        var persistCount = 0

        override fun persistResume() {
            persistCount++
            log.add("persist")
        }

        override fun currentPositionMs(): Long = position
        override fun stopStream() = log.add("stop").let {}
        override fun startPlayback(positionMs: Long) = log.add("start:$positionMs").let {}
        override fun attachViews() = log.add("attach").let {}
        override fun detachViews() = log.add("detach").let {}
    }

    @Test
    fun firstForeground_onlyAttaches() {
        val actions = FakeActions()
        val coordinator = VodPlaybackCoordinator(actions)

        coordinator.onStart()

        assertEquals(listOf("attach"), actions.log)
    }

    @Test
    fun background_withoutPlaybackStarted_onlyDetaches() {
        val actions = FakeActions()
        val coordinator = VodPlaybackCoordinator(actions)

        coordinator.onStop()

        assertEquals(listOf("detach"), actions.log)
        assertEquals(0, actions.persistCount)
    }

    @Test
    fun background_persistsRecordsAndStops_inOrder() {
        val actions = FakeActions(position = 42_000L)
        val coordinator = VodPlaybackCoordinator(actions)
        coordinator.markPlaybackStarted()

        coordinator.onStop()

        // Persist + record happen before the stream is stopped.
        assertEquals(listOf("persist", "stop"), actions.log)
    }

    @Test
    fun foreground_afterBackground_resumesFromRecordedPosition() {
        val actions = FakeActions(position = 42_000L)
        val coordinator = VodPlaybackCoordinator(actions)
        coordinator.markPlaybackStarted()

        coordinator.onStop()
        actions.log.clear()
        coordinator.onStart()

        // Stop already happened on background; foreground just re-opens from it.
        assertEquals(listOf("start:42000"), actions.log)
    }

    @Test
    fun secondBackground_recordsLatestPosition() {
        val actions = FakeActions(position = 1_000L)
        val coordinator = VodPlaybackCoordinator(actions)
        coordinator.markPlaybackStarted()

        coordinator.onStop()
        coordinator.onStart() // resume from 1_000
        actions.log.clear()

        actions.position = 5_000L
        coordinator.onStop()
        coordinator.onStart()

        assertEquals(listOf("persist", "stop", "start:5000"), actions.log)
    }

    @Test
    fun foreground_withoutPriorBackground_doesNotRestart() {
        val actions = FakeActions(position = 7_000L)
        val coordinator = VodPlaybackCoordinator(actions)
        coordinator.markPlaybackStarted()

        // No onStop happened, so a foreground must not re-open a stream.
        coordinator.onStart()

        assertEquals(listOf("attach"), actions.log)
    }

    @Test
    fun negativePosition_isClampedToZero() {
        val actions = FakeActions(position = -10L)
        val coordinator = VodPlaybackCoordinator(actions)
        coordinator.markPlaybackStarted()

        coordinator.onStop()
        actions.log.clear()
        coordinator.onStart()

        assertEquals(listOf("start:0"), actions.log)
    }
}
