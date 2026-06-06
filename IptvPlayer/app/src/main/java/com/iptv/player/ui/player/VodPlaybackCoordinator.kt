/*
 * VodPlaybackCoordinator.kt
 * Pure (Android-free) state machine for VodPlayerActivity's background
 * release / foreground re-acquire ordering. Extracted from the activity so the
 * single-connection lifecycle (onStop closes the socket + records position;
 * onStart re-opens from exactly that position; the stop always happens before
 * the next start) is unit-testable without an emulator or libVLC.
 *
 * The activity owns the actual libVLC side effects; this class only decides
 * *when* and *with what position* they fire, via the [Actions] callbacks.
 */
package com.iptv.player.ui.player

class VodPlaybackCoordinator(private val actions: Actions) {

    /** Side effects the activity performs; ordering is owned by this class. */
    interface Actions {
        /** Persist the current resume position before tearing the stream down. */
        fun persistResume()

        /** Current playback position in ms (>= 0). */
        fun currentPositionMs(): Long

        /** Fully stop the stream (close the socket) and detach the surface. */
        fun stopStream()

        /** (Re)start playback of the current stream from [positionMs]. */
        fun startPlayback(positionMs: Long)

        /** Attach the video surface without (re)starting playback. */
        fun attachViews()

        /** Detach the video surface. */
        fun detachViews()
    }

    /** True once playback has actually been started at least once. */
    var playbackStarted = false
        private set

    private var resumeOnForeground = false
    private var backgroundPositionMs = 0L

    /** Called by the activity right after it kicks off playback. */
    fun markPlaybackStarted() {
        playbackStarted = true
    }

    /**
     * Foreground entry. If we released a stream on the last background, re-acquire
     * it from where we left off; otherwise this is the first foreground (or there
     * is nothing to resume) so we only (re)attach the surface.
     */
    fun onStart() {
        if (resumeOnForeground) {
            resumeOnForeground = false
            startPlayback(backgroundPositionMs)
        } else {
            actions.attachViews()
        }
    }

    /**
     * Background exit. If playback was running, persist + record the position and
     * fully stop the stream so the single connection slot frees immediately.
     * Otherwise just detach the (idle) surface.
     */
    fun onStop() {
        if (playbackStarted) {
            actions.persistResume()
            backgroundPositionMs = actions.currentPositionMs().coerceAtLeast(0L)
            resumeOnForeground = true
            actions.stopStream()
        } else {
            actions.detachViews()
        }
    }

    /** Shared by [onStart] resume and the very first playback kick-off. */
    private fun startPlayback(positionMs: Long) {
        actions.startPlayback(positionMs)
    }
}
