package com.iptv.player.playback.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResourceGovernorTest {

    @Test
    fun `exact tokens prevent duplicate end and counter underflow`() {
        val gate = PlaybackResourceGate()
        val live = gate.begin("live")
        val vod = gate.begin("vod")

        assertEquals(2, gate.activeOwnerCount.value)
        assertTrue(gate.end(live))
        assertFalse(gate.end(live))
        assertEquals(1, gate.activeOwnerCount.value)
        assertTrue(gate.isPlaybackActive)
        assertTrue(gate.end(vod))
        assertEquals(0, gate.activeOwnerCount.value)
        assertFalse(gate.isPlaybackActive)
    }

    @Test
    fun `idle listener fires only after last owner ends`() {
        val gate = PlaybackResourceGate()
        var idleEdges = 0
        val registration = gate.addIdleListener { idleEdges++ }
        val first = gate.begin("preview")
        val second = gate.begin("fullscreen")

        gate.end(first)
        assertEquals(0, idleEdges)
        gate.end(second)
        assertEquals(1, idleEdges)

        registration.close()
        gate.end(gate.begin("another"))
        assertEquals(1, idleEdges)
    }

    @Test
    fun `work is deferred without executing when playback already active`() = runBlocking {
        val gate = PlaybackResourceGate()
        val token = gate.begin("live")
        var executed = false

        val result = gate.runWhileIdle {
            executed = true
            "done"
        }

        assertEquals(IdleWorkResult.InterruptedByPlayback, result)
        assertFalse(executed)
        gate.end(token)
        Unit
    }

    @Test
    fun `playback start cancels in-flight nonessential work`() = runBlocking {
        val gate = PlaybackResourceGate()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val result = async {
            gate.runWhileIdle {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }

        entered.await()
        val token = gate.begin("vod")

        assertEquals(IdleWorkResult.InterruptedByPlayback, result.await())
        assertTrue(cancelled.isCompleted)
        gate.end(token)
        Unit
    }

    @Test
    fun `idle work returns its value normally`() = runBlocking {
        val gate = PlaybackResourceGate()

        val result = gate.runWhileIdle { 42 }

        assertEquals(IdleWorkResult.Completed(42), result)
    }
}
