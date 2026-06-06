/*
 * PlayerControllerConnectionTest.kt
 * Proves the single-open-connection contract at the controller level: zapping,
 * retry/backoff and the AUTO Exo->VLC fallback never hold two stream
 * connections (or two engines) at once, and the background release / foreground
 * re-acquire pair stops-then-reopens the same URL.
 *
 * Pure JVM: the engine and scheduler are faked; Context/ViewGroup are inert
 * Mockito stubs the controller only stores.
 */
package com.iptv.player.player

import android.content.Context
import android.view.ViewGroup
import com.iptv.player.data.model.PlayerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock

@RunWith(JUnit4::class)
class PlayerControllerConnectionTest {

    private val tracker = ConnectionTracker()
    private val log = mutableListOf<String>()
    private val scheduler = FakeScheduler()
    private val createdEngines = mutableListOf<RecordingPlayerEngine>()
    private val callbacks = RecordingCallback()

    private val context: Context = mock(Context::class.java)
    private val container: ViewGroup = mock(ViewGroup::class.java)

    private fun newEngine(useVlc: Boolean): RecordingPlayerEngine =
        RecordingPlayerEngine(if (useVlc) "VLC" else "ExoPlayer", tracker, log)
            .also { createdEngines.add(it) }

    private fun controller(mode: PlayerMode) = PlayerController(
        context = context,
        container = container,
        mode = mode,
        callback = callbacks,
        engineFactory = ::newEngine,
        scheduler = scheduler
    )

    // ---- Zap path -------------------------------------------------------

    @Test
    fun zapping_reusesEngine_andNeverHoldsTwoConnections() {
        val c = controller(PlayerMode.VLC)

        c.play("urlA")
        c.play("urlB")
        c.play("urlC")

        // One engine reused across every zap (no teardown/recreate churn).
        assertEquals(1, createdEngines.size)
        // The contract: at most one open connection at any instant.
        assertEquals(1, tracker.peakOpen)
        assertEquals(1, tracker.currentlyOpen)
        // Each new stream was opened only after the prior one was stopped.
        assertEquals(
            listOf(
                "VLC:bind",
                "VLC:play:urlA",
                "VLC:stop(implicit)",
                "VLC:play:urlB",
                "VLC:stop(implicit)",
                "VLC:play:urlC",
            ),
            log,
        )
    }

    // ---- Retry / backoff path ------------------------------------------

    @Test
    fun retry_reusesSameEngine_andNeverHoldsTwoConnections() {
        val c = controller(PlayerMode.VLC)
        c.play("url")
        val engine = createdEngines.single()

        // Two fatal errors, each scheduling and then firing a retry.
        engine.emitError()
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        engine.emitError()
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        assertEquals(listOf(1, 2), callbacks.retryAttempts)
        // Retries reuse the one engine and never stack a second connection.
        assertEquals(1, createdEngines.size)
        assertEquals(1, tracker.peakOpen)
        assertEquals(1, tracker.currentlyOpen)
    }

    @Test
    fun retry_givesUpAfterMaxAttempts_andReleasesConnection() {
        val c = controller(PlayerMode.VLC)
        c.play("url")
        val engine = createdEngines.single()

        // maxRetries = 4: fire 4 retries, the 5th error is fatal.
        repeat(4) {
            engine.emitError()
            scheduler.runNext()
        }
        engine.emitError()

        assertEquals(listOf(1, 2, 3, 4), callbacks.retryAttempts)
        assertEquals(1, callbacks.fatalCount)
        assertEquals(0, scheduler.pendingCount)
        // Connection was never doubled even across the whole retry storm.
        assertEquals(1, tracker.peakOpen)
    }

    // ---- AUTO fallback path (Exo -> VLC) -------------------------------

    @Test
    fun autoFallback_tearsDownExoBeforeOpeningVlc() {
        val c = controller(PlayerMode.AUTO)
        c.play("url")

        // AUTO starts on ExoPlayer.
        assertEquals("ExoPlayer", createdEngines[0].engineName)

        // A fatal error on Exo triggers the one-time switch to VLC.
        createdEngines[0].emitError()

        assertEquals(2, createdEngines.size)
        assertEquals("VLC", createdEngines[1].engineName)
        // Never two connections nor two live engines during the switch.
        assertEquals(1, tracker.peakOpen)
        assertEquals(1, tracker.currentlyOpen)
        assertFalse(createdEngines[0].open)
        assertTrue(createdEngines[1].open)

        // Old engine fully released before the new one is built and played.
        val exoRelease = log.indexOf("ExoPlayer:release")
        val vlcBind = log.indexOf("VLC:bind")
        val vlcPlay = log.indexOf("VLC:play:url")
        assertTrue(exoRelease >= 0)
        assertTrue(exoRelease < vlcBind)
        assertTrue(vlcBind < vlcPlay)
    }

    @Test
    fun autoFallback_thenRetry_staysSingleConnection() {
        val c = controller(PlayerMode.AUTO)
        c.play("url")
        createdEngines[0].emitError() // Exo -> VLC fallback
        val vlc = createdEngines[1]

        // After fallback, further errors retry on VLC (no third engine).
        vlc.emitError()
        scheduler.runNext()

        assertEquals(2, createdEngines.size)
        assertEquals(1, tracker.peakOpen)
        assertEquals(1, tracker.currentlyOpen)
    }

    // ---- Background release / foreground re-acquire --------------------

    @Test
    fun releaseStream_stopsEngine_andCancelsPendingRetries() {
        val c = controller(PlayerMode.VLC)
        c.play("url")
        val engine = createdEngines.single()
        // Queue a retry so we can prove releaseStream cancels it.
        engine.emitError()
        assertEquals(1, scheduler.pendingCount)

        c.releaseStream()

        assertFalse(engine.open)
        assertEquals(0, tracker.currentlyOpen)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun reacquireStream_reopensSameUrl_withoutDoublingConnection() {
        val c = controller(PlayerMode.VLC)
        c.play("url")
        val engine = createdEngines.single()

        c.releaseStream()
        assertEquals(0, tracker.currentlyOpen)

        c.reacquireStream()

        assertTrue(engine.open)
        assertEquals(1, tracker.currentlyOpen)
        assertEquals(1, tracker.peakOpen)
        // Re-acquired the exact same stream URL.
        assertEquals("VLC:play:url", log.last())
        assertEquals(1, createdEngines.size)
    }

    // ---- ensureEngine behavior -----------------------------------------

    @Test
    fun release_tearsDownEngine_andFreesConnection() {
        val c = controller(PlayerMode.VLC)
        c.play("url")
        val engine = createdEngines.single()

        c.release()

        assertFalse(engine.open)
        assertEquals(0, tracker.currentlyOpen)
        assertTrue(log.contains("VLC:release"))
    }
}
