package com.iptv.player.player

import android.graphics.Bitmap
import android.os.Handler
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.ArrayDeque
import java.util.PriorityQueue
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Exercises the real scheduling/callback code; no Android GPU or network is used. */
class SurfaceFrameHealthMonitorTest {
    @Test fun uhdStopsAfterTwoFreshHealthyCapturesWithoutDiscardingEvidence() {
        Fixture(uhd = true).use { f ->
            f.start()
            f.sample()
            assertFalse(f.monitor.hasHealthyFrame())
            f.sample()
            assertEquals(1, f.healthy)
            assertTrue(f.monitor.hasClassifiedFrame())
            assertTrue(f.monitor.hasHealthyFrame())
            assertFalse(f.monitor.isSamplingUnavailable())
            assertEquals(2, f.copies)
            assertFalse(f.runNext())
        }
    }

    @Test fun costlyCallbacksDoNotScheduleRecurringUhdReadbacks() {
        Fixture(uhd = true).use { f ->
            f.start()
            f.sample(copyDurationMs = 2_700)
            f.sample(copyDurationMs = 2_700)
            assertEquals(1, f.healthy)
            assertEquals(2, f.copies)
            assertFalse(f.runNext())
        }
    }

    @Test fun ordinaryHardwareOutputKeepsPeriodicValidation() {
        Fixture().use { f ->
            f.start()
            f.sample()
            f.sample()
            val validatedAt = f.now
            f.sample()
            assertEquals(5_000L, f.now - validatedAt)
            assertEquals(3, f.copies)
            assertEquals(1, f.healthy)
        }
    }

    @Test fun lateUhdMetadataRetiresQueuedReadbackBeforeItTouchesGpu() {
        Fixture().use { f ->
            f.start()
            f.sample()
            f.sample()
            f.policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
            f.policy.onVideoFormat(3840, 2160)
            assertTrue(f.runNext()) // Already queued five-second check, but no PixelCopy.
            assertEquals(2, f.copies)
            assertTrue(f.monitor.hasHealthyFrame())
            assertFalse(f.runNext())
        }
    }

    @Test fun policyChangeDuringInFlightHealthyCaptureStopsAfterCompletion() {
        Fixture().use { f ->
            f.start()
            f.sample()
            f.sample()
            assertTrue(f.runNext())
            f.enableUhd()
            f.complete()
            assertEquals(3, f.copies)
            assertTrue(f.monitor.hasHealthyFrame())
            assertFalse(f.runNext())
        }
    }

    @Test fun policyChangeCannotDiscardInFlightGreenEvidence() {
        Fixture().use { f ->
            f.start()
            f.sample()
            f.sample()
            assertTrue(f.runNext())
            f.enableUhd()
            f.complete(GREEN)
            repeat(3) { f.sample(GREEN) }
            assertEquals(1, f.green)
            assertFalse(f.runNext())
        }
    }

    @Test fun policyChangeCannotDiscardPendingBlankEvidence() {
        Fixture().use { f ->
            f.start()
            f.sample()
            f.sample()
            f.sample(BLACK)
            f.enableUhd()
            repeat(14) { f.sample(BLACK) }
            assertEquals(1, f.blank)
            assertFalse(f.runNext())
        }
    }

    @Test fun pendingBadPictureCanRecoverThenRetireWithoutDuplicateHealthyEvent() {
        Fixture().use { f ->
            f.start()
            f.sample()
            f.sample()
            f.sample(GREEN)
            f.enableUhd()
            f.sample()
            assertEquals(0, f.green)
            assertEquals(1, f.healthy)
            assertFalse(f.runNext())
        }
    }

    @Test fun startupGreenStillHasGraceThenFails() {
        Fixture(uhd = true).use { f ->
            f.start()
            f.sample(GREEN)
            f.sample(GREEN)
            assertEquals(0, f.green)
            repeat(31) { f.sample(GREEN) }
            assertEquals(1, f.green)
            assertEquals(0, f.healthy)
            assertFalse(f.runNext())
        }
    }

    @Test fun startupBlankStillHasGraceThenFails() {
        Fixture(uhd = true).use { f ->
            f.start()
            f.sample(BLACK)
            repeat(41) { f.sample(BLACK) }
            assertEquals(1, f.blank)
            assertEquals(0, f.healthy)
            assertFalse(f.runNext())
        }
    }

    @Test fun stoppedUhdMonitorRevalidatesReplacementSurface() {
        Fixture(uhd = true).use { f ->
            f.start()
            f.sample()
            f.sample()
            f.monitor.onOutputTransition()
            assertFalse(f.monitor.hasHealthyFrame())
            f.sample()
            assertFalse(f.monitor.hasHealthyFrame())
            f.sample()
            assertEquals(2, f.healthy)
            assertEquals(4, f.copies)
            assertFalse(f.runNext())
        }
    }

    @Test fun changedUhdSourceRevalidatesEvenWithUnchanged1080pSurface() {
        Fixture().use { f ->
            f.policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
            f.policy.onVideoFormat(1920, 1080)
            f.start()
            f.sample()
            f.sample()
            if (f.policy.onVideoFormat(3840, 2160)) f.monitor.onOutputTransition()
            assertFalse(f.monitor.hasHealthyFrame())
            f.sample()
            f.sample()
            assertEquals(2, f.healthy)
            assertEquals(4, f.copies)
            while (f.runNext()) { /* Drain the stale old-generation check. */ }
            assertEquals(4, f.copies)
        }
    }

    @Test fun resetInvalidatesQueuedCapture() {
        Fixture(uhd = true).use { f ->
            f.start()
            f.monitor.reset()
            assertTrue(f.runNext())
            assertEquals(0, f.copies)
            assertFalse(f.monitor.hasHealthyFrame())
        }
    }

    @Test fun oldOutputCallbackCannotValidateReplacementSurface() {
        Fixture(uhd = true).use { f ->
            f.start()
            f.sample()
            assertTrue(f.runNext()) // Second old output request is in flight.
            f.monitor.onOutputTransition()
            f.complete()
            assertEquals(0, f.healthy)
            assertFalse(f.monitor.hasHealthyFrame())
            f.sample()
            f.sample()
            assertEquals(1, f.healthy)
            assertEquals(f.copies, f.recycled)
        }
    }

    @Test fun unavailableReadbackRemainsBoundedInsteadOfInventingHealthyEvidence() {
        Fixture(uhd = true).use { f ->
            f.start()
            repeat(20) { if (f.runNext()) f.complete(result = PixelCopy.ERROR_SOURCE_NO_DATA) }
            assertEquals(1, f.unavailable)
            assertEquals(0, f.healthy)
            assertTrue(f.monitor.isSamplingUnavailable())
            assertFalse(f.monitor.hasHealthyFrame())
            assertFalse(f.runNext())
        }
    }

    @Test fun hungPixelCopyReportsSamplingUnavailableInsteadOfWaitingForever() {
        Fixture(uhd = true).use { f ->
            f.start()
            assertTrue(f.runNext()) // Request issued; the native callback never fires.
            assertEquals(1, f.copies)
            val requestedAt = f.now
            assertTrue(f.runNext()) // Per-sample timeout.
            assertEquals(4_000L, f.now - requestedAt)
            assertEquals(1, f.unavailable)
            assertTrue(f.monitor.isSamplingUnavailable())
            assertEquals(0, f.healthy)
            assertFalse(f.runNext())
            // A late completion must not validate the retired sample.
            f.complete()
            assertEquals(0, f.healthy)
            assertFalse(f.monitor.hasHealthyFrame())
            assertEquals(f.copies, f.recycled)
        }
    }

    @Test fun completedCopyCancelsItsTimeout() {
        Fixture(uhd = true).use { f ->
            f.start()
            f.sample(copyDurationMs = 3_900)
            f.sample()
            assertEquals(1, f.healthy)
            assertEquals(0, f.unavailable)
            assertFalse(f.runNext())
        }
    }

    @Test fun constrainedDevicesRetainExistingStartupOnlyBehavior() {
        Fixture(constrained = true).use { f ->
            f.start()
            f.sample()
            f.sample()
            assertEquals(2, f.copies)
            assertFalse(f.runNext())
        }
    }

    @Test fun inlineCopyOverBudgetMakesTheSurfaceUnsampleable() {
        Fixture(inlineCopyBudgetMs = 1_500).use { f ->
            f.inlineMs = 1_600 // PixelCopy.request() itself blocked the caller.
            f.start()
            assertTrue(f.runNext())
            assertEquals(1, f.copies)
            assertEquals(1, f.unavailable)
            assertEquals(
                SurfaceFrameHealthMonitor.UnavailableReason.SLOW_INLINE_COPY,
                f.monitor.unavailableReason(),
            )
            assertTrue(f.monitor.isSamplingUnavailable())
            assertFalse(f.runNext()) // No callback timeout was queued.
            // The already-queued result is ignored and only recycles its bitmap.
            f.complete()
            assertEquals(0, f.healthy)
            assertFalse(f.monitor.hasHealthyFrame())
            assertEquals(f.copies, f.recycled)
            assertFalse(f.runNext())
        }
    }

    @Test fun inlineCopyOfTheWholeCallbackTimeoutIsUnsampleable() {
        Fixture(inlineCopyBudgetMs = 4_000).use { f ->
            f.inlineMs = 4_000
            f.start()
            assertTrue(f.runNext())
            assertEquals(1, f.unavailable)
            assertEquals(
                SurfaceFrameHealthMonitor.UnavailableReason.SLOW_INLINE_COPY,
                f.monitor.unavailableReason(),
            )
            assertFalse(f.runNext())
        }
    }

    @Test fun inlineCopyUnderBudgetIsClassifiedAndSpacesTheNextSample() {
        Fixture().use { f ->
            f.inlineMs = 600
            f.start()
            assertTrue(f.runNext())
            f.complete()
            assertEquals(0, f.unavailable)
            assertEquals(1, f.monitor.sampleTally().other)
            // 2 x 600 ms instead of the 220 ms startup cadence.
            assertEquals(1_200L, f.nextTaskDelayMs())
        }
    }

    @Test fun callbackTimeoutAndExhaustedRetriesNameTheirReason() {
        Fixture(uhd = true).use { f ->
            f.start()
            assertTrue(f.runNext())
            assertTrue(f.runNext()) // The native callback never fires.
            assertEquals(
                SurfaceFrameHealthMonitor.UnavailableReason.CALLBACK_TIMEOUT,
                f.monitor.unavailableReason(),
            )
        }
        Fixture(uhd = true).use { f ->
            f.start()
            repeat(20) { if (f.runNext()) f.complete(result = PixelCopy.ERROR_SOURCE_NO_DATA) }
            assertEquals(
                SurfaceFrameHealthMonitor.UnavailableReason.RETRIES_EXHAUSTED,
                f.monitor.unavailableReason(),
            )
            f.monitor.reset()
            assertNull(f.monitor.unavailableReason())
        }
    }

    @Test fun tallyCountsEachSampleClassAndErrors() {
        Fixture().use { f ->
            f.start()
            f.sample(BLACK)
            f.sample(GREEN)
            f.sample(HEALTHY)
            assertTrue(f.runNext())
            f.complete(result = PixelCopy.ERROR_SOURCE_NO_DATA)
            assertEquals(
                SurfaceFrameHealthMonitor.SampleTally(blank = 1, green = 1, other = 1, errors = 1),
                f.monitor.sampleTally(),
            )
            assertEquals("1/1/1/1", f.monitor.sampleTally().compact())
            f.monitor.reset()
            assertEquals(SurfaceFrameHealthMonitor.SampleTally(), f.monitor.sampleTally())
        }
    }

    @Test fun tallyIsPerOutputAndPerStart() {
        Fixture(uhd = true).use { f ->
            f.start()
            f.sample(GREEN)
            assertEquals(1, f.monitor.sampleTally().green)
            f.monitor.onOutputTransition()
            assertEquals(SurfaceFrameHealthMonitor.SampleTally(), f.monitor.sampleTally())
            while (f.runNext()) { if (f.pendingCopies() > 0) f.complete() }
            assertTrue(f.monitor.hasHealthyFrame()) // UHD stops after two healthy samples.
            assertEquals(2, f.monitor.sampleTally().other)
            f.start() // A stopped monitor restarts with a fresh tally.
            assertEquals(SurfaceFrameHealthMonitor.SampleTally(), f.monitor.sampleTally())
        }
    }

    @Test fun preNougatDoesNotSchedulePixelCopy() {
        Fixture(sdkInt = 23).use { f ->
            f.start()
            f.monitor.onOutputTransition()
            assertFalse(f.monitor.requestProgressProbe { fail("No probe on API 23") })
            assertFalse(f.runNext())
            assertEquals(0, f.copies)
        }
    }

    private class Fixture(
        uhd: Boolean = false,
        constrained: Boolean = false,
        sdkInt: Int = 30,
        inlineCopyBudgetMs: Long = Long.MAX_VALUE,
    ) : AutoCloseable {
        var now = 1_000L
        /** Time PixelCopy.request() blocks its caller (inline copy platforms). */
        var inlineMs = 0L
        var copies = 0
        var recycled = 0
        var healthy = 0
        var green = 0
        var blank = 0
        var unavailable = 0
        private var pixel = HEALTHY
        private data class Task(val at: Long, val runnable: Runnable)
        private val tasks = PriorityQueue<Task>(compareBy { it.at })
        private val callbacks = ArrayDeque<PixelCopy.OnPixelCopyFinishedListener>()
        val policy = SurfaceReadbackPolicy(constrained)
        private val bitmap = Mockito.mock(Bitmap::class.java) { invocation ->
            when (invocation.method.name) {
                "getPixels" -> { (invocation.arguments[0] as IntArray).fill(pixel); null }
                "recycle" -> { recycled++; null }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        private val bitmapMock = Mockito.mockStatic(Bitmap::class.java) { invocation ->
            if (invocation.method.name == "createBitmap") bitmap
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        private val pixelCopyMock = Mockito.mockStatic(PixelCopy::class.java) { invocation ->
            if (invocation.method.name == "request") {
                copies++
                now += inlineMs
                callbacks.add(invocation.arguments[2] as PixelCopy.OnPixelCopyFinishedListener)
                null
            } else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        private val surface = Mockito.mock(Surface::class.java) { invocation ->
            if (invocation.method.name == "isValid") true else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        private val holder = Mockito.mock(SurfaceHolder::class.java) { invocation ->
            if (invocation.method.name == "getSurface") surface else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        // Source can be 3840x2160 even though the actual Android view is 1920x1080.
        private val view = Mockito.mock(SurfaceView::class.java) { invocation ->
            when (invocation.method.name) {
                "getHolder" -> holder
                "getWidth" -> 1920
                "getHeight" -> 1080
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        private val handler = Mockito.mock(Handler::class.java) { invocation ->
            when (invocation.method.name) {
                "postDelayed" -> {
                    tasks.add(Task(now + (invocation.arguments[1] as Long), invocation.arguments[0] as Runnable))
                    true
                }
                "post" -> { tasks.add(Task(now, invocation.arguments[0] as Runnable)); true }
                "removeCallbacks" -> {
                    tasks.removeIf { it.runnable === invocation.arguments[0] }
                    null
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val monitor = SurfaceFrameHealthMonitor(
            handler = handler,
            onSolidGreen = { green++ },
            onPersistentBlank = { blank++ },
            onHealthyFrame = { healthy++ },
            onSamplingUnavailable = { unavailable++ },
            continueAfterHealthy = !constrained,
            allowPeriodicSampling = { policy.mode == SurfaceReadbackPolicy.Mode.CONTINUOUS },
            inlineCopyBudgetMs = { inlineCopyBudgetMs },
            sdkInt = sdkInt,
            nowMs = { now },
        )

        init { if (uhd) enableUhd() }
        fun enableUhd() {
            policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
            policy.onVideoFormat(3840, 2160)
        }
        fun start() = monitor.start { view }
        fun runNext(): Boolean {
            val task = tasks.poll() ?: return false
            now = maxOf(now, task.at)
            task.runnable.run()
            return true
        }
        fun nextTaskDelayMs(): Long? = tasks.peek()?.let { it.at - now }
        fun pendingCopies(): Int = callbacks.size
        fun complete(color: Int = HEALTHY, result: Int = PixelCopy.SUCCESS) {
            pixel = color
            check(!callbacks.isEmpty()) { "No native request to complete" }
            callbacks.removeFirst().onPixelCopyFinished(result)
        }
        fun sample(color: Int = HEALTHY, copyDurationMs: Long = 0) {
            assertTrue("Expected a scheduled sample", runNext())
            now += copyDurationMs
            complete(color)
        }
        override fun close() {
            pixelCopyMock.close()
            bitmapMock.close()
        }
    }

    private companion object {
        val HEALTHY = 0xff204080.toInt()
        val GREEN = 0xff00ff00.toInt()
        val BLACK = 0xff000000.toInt()
    }
}
