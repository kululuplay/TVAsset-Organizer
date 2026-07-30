/*
 * VlcOps.kt
 * Shared serial background queue for BLOCKING libVLC calls (MediaPlayer.stop()
 * / release(), and the retired-player cleanup -> setMedia -> play sequence).
 *
 * Why it exists: libVLC's MediaPlayer.stop() is SYNCHRONOUS — it waits for the
 * input/demuxer thread to wind down, which on a stalled/slow network can take
 * many SECONDS. Every channel replacement also stops/releases the retired player
 * first (single-connection contract), and the failure ladder re-enters that path
 * on retries, so running these on the main thread froze the whole UI ("app stops
 * responding") exactly when the user's internet was bad. ExoPlayer/Media3 calls
 * are async and MUST stay on the main thread — this thread is for libVLC only.
 *
 * Why ONE shared serial queue instead of one thread per engine instance:
 *  - While native calls return before their bound, FIFO ordering across engine
 *    instances preserves the single-connection contract during engine swaps:
 *    the OLD engine's queued stop/release finishes before the NEXT engine's play.
 *    After a timeout Android cannot cancel the blocked JNI call, so its retired
 *    worker/socket may temporarily overlap a replacement worker using DIFFERENT
 *    quarantined native handles. Callers must never give both workers the same
 *    MediaPlayer/LibVLC objects.
 *  - The fallback ladder (EXO -> VLC_HW -> VLC_SW) plus the reconnect loop can
 *    create/destroy engines every few seconds on a bad network; per-instance
 *    threads would churn.
 *
 * View operations (attachViews/detachViews, adding/removing the VLCVideoLayout)
 * stay on the main thread — only the potentially-blocking native calls come
 * here. Each runnable is exception-guarded. A bounded native call that never
 * returns retires its worker generation while the serial queue continues on a
 * fresh one, so one vendor JNI deadlock cannot freeze every later zap forever.
 */
package com.iptv.player.player

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.iptv.player.util.Logger
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-operation timeout ordering gate. Kept framework-free so the critical
 * quarantine-before-cleanup contract can be exercised by the JVM unit tests.
 */
internal class VlcTimeoutGate {
    private val timedOut = AtomicBoolean(false)
    private val quarantineFinished = CountDownLatch(1)

    fun markTimedOut() {
        timedOut.set(true)
    }

    fun didTimeOut(): Boolean = timedOut.get()

    fun finishQuarantine() {
        quarantineFinished.countDown()
    }

    /** Returns whether interruption must be restored after native cleanup. */
    fun awaitQuarantine(): Boolean {
        var interrupted = false
        while (true) {
            try {
                quarantineFinished.await()
                break
            } catch (_: InterruptedException) {
                // Cleanup owns quarantined handles and cannot safely be skipped.
                // Restore the interrupt only after the ordering gate has opened.
                interrupted = true
            }
        }
        return interrupted
    }
}

object VlcOps {

    private const val TAG = "VlcOps"

    private data class Worker(
        val generation: Int,
        val thread: HandlerThread,
        val handler: Handler,
    )

    private class PendingOp(
        val action: () -> Unit,
        val timeoutMs: Long,
        val onTimeout: () -> Unit,
        val onTimedOutActionFinished: () -> Unit,
    ) {
        val timeoutGate = VlcTimeoutGate()
        // The retired worker can return while the watchdog is still executing
        // onTimeout on main. Cleanup must not touch quarantined handles until
        // that callback has finished invalidating every fresh-worker path.
        var timeoutRunnable: Runnable? = null
    }

    private val queueLock = Any()
    private val pending = ArrayDeque<PendingOp>()
    private var active: PendingOp? = null
    private var worker: Worker? = null
    private var workerGeneration = 0
    private var recoveringFromTimeout = false

    private val watchdogHandler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }

    private fun createWorkerLocked(): Worker {
        val generation = ++workerGeneration
        val thread = HandlerThread("vlc-ops-$generation")
        thread.start()
        return Worker(generation, thread, Handler(thread.looper))
    }

    /** Run [action] on the shared VLC ops thread (FIFO, exception-guarded). */
    fun post(action: () -> Unit) {
        enqueue(
            PendingOp(
                action = action,
                timeoutMs = 0L,
                onTimeout = {},
                onTimedOutActionFinished = {},
            ),
        )
    }

    /**
     * Run a native operation with a liveness bound.
     *
     * A vendor libVLC stop/release call can block inside native code forever.
     * Killing that thread is unsafe and not supported by Android, so when the
     * bound expires the queue moves to a fresh worker generation and invokes
     * [onTimeout] on the main thread. The abandoned action is still allowed to
     * unwind naturally, but it can no longer hold every later zap, release and
     * fallback behind it. [onTimedOutActionFinished] runs only on that operation's
     * retired worker, after its native action actually unwinds. This gives the
     * owner a race-free place to release handles quarantined by [onTimeout].
     */
    fun postBounded(
        timeoutMs: Long,
        onTimeout: () -> Unit,
        onTimedOutActionFinished: () -> Unit = {},
        action: () -> Unit,
    ) {
        require(timeoutMs > 0L)
        enqueue(PendingOp(action, timeoutMs, onTimeout, onTimedOutActionFinished))
    }

    private fun enqueue(operation: PendingOp) {
        synchronized(queueLock) {
            pending.addLast(operation)
            dispatchNextLocked()
        }
    }

    private fun dispatchNextLocked() {
        if (active != null || recoveringFromTimeout) return
        val operation = pending.pollFirst() ?: return
        val target = worker ?: createWorkerLocked().also { worker = it }
        active = operation

        if (operation.timeoutMs > 0L) {
            val timeout = Runnable { timeOut(operation, target) }
            operation.timeoutRunnable = timeout
            watchdogHandler.postDelayed(timeout, operation.timeoutMs)
        }

        val accepted = target.handler.post {
            try {
                operation.action()
            } catch (failure: Throwable) {
                Logger.w(TAG, "vlc op failed", failure)
            } finally {
                if (complete(operation)) {
                    val restoreInterrupt = operation.timeoutGate.awaitQuarantine()
                    runCatching(operation.onTimedOutActionFinished).onFailure {
                        Logger.w(TAG, "timed-out vlc cleanup failed", it)
                    }
                    if (restoreInterrupt) Thread.currentThread().interrupt()
                }
            }
        }
        if (!accepted) {
            operation.timeoutRunnable?.let(watchdogHandler::removeCallbacks)
            active = null
            rotateWorkerLocked(target)
            pending.addFirst(operation)
            dispatchNextLocked()
        }
    }

    /**
     * Returns true only when this exact operation lost the completion-vs-timeout
     * race. The decision is made under the same lock as [timeOut], so a queued
     * later action can never accidentally claim another operation's cleanup.
     */
    private fun complete(operation: PendingOp): Boolean =
        synchronized(queueLock) {
            if (active !== operation) {
                return@synchronized operation.timeoutGate.didTimeOut()
            }
            operation.timeoutRunnable?.let(watchdogHandler::removeCallbacks)
            active = null
            dispatchNextLocked()
            false
        }

    private fun timeOut(operation: PendingOp, target: Worker) {
        val callback = synchronized(queueLock) {
            if (active !== operation) {
                null
            } else {
                operation.timeoutGate.markTimedOut()
                active = null
                recoveringFromTimeout = true
                rotateWorkerLocked(target)
                operation.onTimeout
            }
        }
        if (callback != null) {
            Logger.w(TAG, "vlc native operation timed out; rotated worker")
            try {
                // Invalidate/quarantine the timed-out owner's native handles
                // before the new worker is allowed to dequeue any later work.
                runCatching(callback).onFailure {
                    Logger.w(TAG, "vlc timeout callback failed", it)
                }
            } finally {
                // Quarantine is now complete. The retired worker may release its
                // own handles; a fresh worker may proceed with different handles.
                operation.timeoutGate.finishQuarantine()
                synchronized(queueLock) {
                    recoveringFromTimeout = false
                    dispatchNextLocked()
                }
            }
        }
    }

    private fun rotateWorkerLocked(stalled: Worker) {
        if (worker === stalled) {
            // quit() cannot interrupt a call currently blocked in JNI, but it
            // prevents any queued messages from running on that retired worker.
            stalled.thread.quit()
            worker = createWorkerLocked()
        }
    }

    /**
     * Run every cleanup action even when an earlier native step throws.
     *
     * MediaPlayer.stop(), MediaPlayer.release() and LibVLC.release() are separate
     * native teardown boundaries. Letting stop() abort the rest leaks decoder and
     * surface resources, which eventually presents as MediaCodec configure
     * failures after repeated channel changes.
     *
     * Failures are returned after all actions have run so the caller may log them
     * without weakening the cleanup guarantee. Kept framework-free for JVM tests.
     */
    internal fun runAllBestEffort(vararg actions: () -> Unit): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        actions.forEach { action ->
            try {
                action()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        return failures
    }
}
