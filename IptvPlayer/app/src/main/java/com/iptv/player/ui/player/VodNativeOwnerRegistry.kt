/*
 * VodNativeOwnerRegistry.kt
 *
 * Architecture note (VOD player decomposition):
 *   VodPlayerActivity stays the single lifecycle/orchestration point for on-demand
 *   playback. The native libVLC handle bookkeeping that used to live inline in the
 *   Activity is owned here: exactly one [NativePlayerOwner] is "current"; every
 *   mutating JNI call reserves it through an [OwnerOperation] and runs on the
 *   shared VlcOps worker with a liveness bound; a timed-out/failed owner is
 *   quarantined (never touched again from main) and cleaned up by the very worker
 *   that owns the blocked call. The Activity observes through [Host] so the
 *   foreground/finishing guards and the failure ladder stay where they were.
 *
 * Moved verbatim from VodPlayerActivity; names/log messages are unchanged.
 */
package com.iptv.player.ui.player

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import com.iptv.player.cast.ProviderConnectionSafety
import com.iptv.player.player.StreamInfo
import com.iptv.player.player.VlcNativeCleanup
import com.iptv.player.player.VlcNativeCleanupResult
import com.iptv.player.player.VlcOps
import com.iptv.player.player.VlcSurfaceRetirement
import com.iptv.player.player.VlcSurfaceViews
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

internal class NativePlayerOwner(
    val generation: Long,
    val libVlc: LibVLC,
    val mediaPlayer: MediaPlayer,
    val videoLayout: VLCVideoLayout,
) {
    val abandoned = AtomicBoolean(false)
    val cleanupScheduled = AtomicBoolean(false)
    val cleanupClaimed = AtomicBoolean(false)
    val ownershipDefinitivelyReleased = AtomicBoolean(false)
    val surfaceRetirement = VlcSurfaceRetirement()
    val activeOperation = AtomicReference<OwnerOperation?>(null)
    val providerUncertaintyToken = AtomicLong(0L)
}

internal class OwnerOperation(
    val owner: NativePlayerOwner,
) {
    val state = AtomicInteger(STATE_ACTIVE)
    val onAbandoned = AtomicReference<(() -> Unit)?>(null)

    companion object {
        const val STATE_ACTIVE = 0
        const val STATE_ABANDONED = 1
        const val STATE_BODY_FINISHED = 2
        const val STATE_CLEANED = 3
    }
}

/** Generation-tagged libVLC state read on the owner worker, published to main. */
internal data class VlcPlaybackSnapshot(
    val ownerGeneration: Long,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val streamInfo: StreamInfo?,
    val decodedVideo: Int?,
    val displayedPictures: Int?,
    val playedAudioBuffers: Int?,
    val readBytes: Int?,
)

internal class VodNativeOwnerRegistry(
    private val handler: Handler,
    private val operationTimeoutMs: Long,
    private val releaseTimeoutMs: Long,
    private val host: Host,
) {
    interface Host {
        val foreground: Boolean
        /** `isFinishing || isDestroyed` of the owning Activity. */
        val finishingOrDestroyed: Boolean
        fun invalidateEventSession()
        /** The registry dropped [owner] as current; mirror fields must be nulled. */
        fun onOwnerCleared(owner: NativePlayerOwner)
        /** A `recoverAsPaused` command failed/timed out: restore paused later. */
        fun markPauseAfterRestore()
        fun handlePlaybackError()
        fun log(message: String)
    }

    @Volatile var current: NativePlayerOwner? = null
    private val generationCounter = AtomicLong(0L)

    fun nextGeneration(): Long = generationCounter.incrementAndGet()

    /**
     * Run every mutating libVLC command off the UI thread with the same owner
     * reservation/quarantine contract used by prepare and teardown. Vendor JNI can
     * block in seemingly small calls such as setTime(), pause() or setSpuTrack().
     */
    fun postBoundedCommand(
        label: String,
        recoverAsPaused: Boolean = false,
        recoverOnFailure: Boolean = true,
        onCompletedMain: (() -> Unit)? = null,
        command: (MediaPlayer) -> Unit,
    ): Boolean {
        val owner = current ?: return false
        val ownerOperation = beginOperation(owner) ?: return false
        VlcOps.postBounded(
            timeoutMs = operationTimeoutMs,
            onTimeout = {
                if (current === owner && !owner.abandoned.get()) {
                    if (recoverAsPaused) host.markPauseAfterRestore()
                    requireProcessRecovery(
                        owner,
                        markConnectionUncertain(owner),
                    )
                    quarantine(owner, "$label timed out")
                    if (
                        recoverOnFailure &&
                        host.foreground &&
                        !host.finishingOrDestroyed
                    ) {
                        host.handlePlaybackError()
                    }
                }
            },
        ) {
            var failure: Throwable? = null
            try {
                if (!owner.abandoned.get()) command(owner.mediaPlayer)
            } catch (error: Throwable) {
                failure = error
                // Close the tiny worker->main hand-off window immediately: no UI
                // command may reserve a native owner whose prior JNI call failed.
                owner.abandoned.set(true)
            } finally {
                finishOperationOnWorker(ownerOperation)
            }
            handler.post {
                if (
                    current !== owner ||
                    host.finishingOrDestroyed
                ) {
                    return@post
                }
                if (failure == null) {
                    if (owner.abandoned.get()) return@post
                    onCompletedMain?.invoke()
                } else {
                    host.log("$label failed: ${failure?.javaClass?.simpleName}")
                    if (recoverAsPaused) host.markPauseAfterRestore()
                    quarantine(owner, "$label failed")
                    if (recoverOnFailure && host.foreground) host.handlePlaybackError()
                }
            }
        }
        return true
    }

    fun beginOperation(owner: NativePlayerOwner): OwnerOperation? {
        if (owner.abandoned.get()) return null
        val operation = OwnerOperation(owner)
        if (!owner.activeOperation.compareAndSet(null, operation)) return null
        if (owner.abandoned.get()) {
            owner.activeOperation.compareAndSet(operation, null)
            return null
        }
        return operation
    }

    /**
     * Final handshake for the worker that owns a native operation. If main
     * quarantined the owner while JNI was blocked, this same retired worker—not
     * the replacement worker—performs the one-and-only native cleanup.
     */
    fun finishOperationOnWorker(operation: OwnerOperation) {
        var cleanOnThisWorker = false
        while (true) {
            when (operation.state.get()) {
                OwnerOperation.STATE_ACTIVE -> {
                    if (
                        operation.state.compareAndSet(
                            OwnerOperation.STATE_ACTIVE,
                            OwnerOperation.STATE_BODY_FINISHED,
                        )
                    ) {
                        break
                    }
                }
                OwnerOperation.STATE_ABANDONED -> {
                    cleanOnThisWorker = true
                    break
                }
                OwnerOperation.STATE_BODY_FINISHED,
                OwnerOperation.STATE_CLEANED -> break
                else -> break
            }
        }
        if (cleanOnThisWorker) {
            cleanupOnCurrentWorker(operation.owner)
            operation.state.set(OwnerOperation.STATE_CLEANED)
        }
        operation.owner.activeOperation.compareAndSet(operation, null)
    }

    /** Release a reservation that failed before any VlcOps action was dispatched. */
    fun cancelOperationBeforeDispatch(operation: OwnerOperation) {
        operation.onAbandoned.set(null)
        operation.state.compareAndSet(
            OwnerOperation.STATE_ACTIVE,
            OwnerOperation.STATE_BODY_FINISHED,
        )
        operation.owner.activeOperation.compareAndSet(operation, null)
    }

    /** Native cleanup after ownership has been exclusively claimed by VlcOps. */
    fun cleanupOnCurrentWorker(owner: NativePlayerOwner): VlcNativeCleanupResult {
        if (!owner.cleanupClaimed.compareAndSet(false, true)) {
            return VlcNativeCleanupResult.notClaimed()
        }
        val cleanupUncertaintyToken = markConnectionUncertain(owner)
        val result = VlcNativeCleanup.runFull(
            detachListener = { owner.mediaPlayer.setEventListener(null) },
            stop = { owner.mediaPlayer.stop() },
            releaseMediaPlayer = { owner.mediaPlayer.release() },
            releaseLibVlc = { owner.libVlc.release() },
        )
        result.failures.forEach { failure ->
            host.log(
                "owner=${owner.generation} cleanup failed: " +
                    failure.javaClass.simpleName,
            )
        }
        removeLayoutOnMain(owner)
        if (result.ownershipDefinitivelyReleased) {
            // Publish proof before clearing the exact token. A timeout callback
            // that arrives just after cleanup must not quarantine this retired
            // owner and mint a brand-new token with no remaining cleanup path.
            owner.ownershipDefinitivelyReleased.set(true)
            owner.surfaceRetirement.ownershipReleased()
            resolveConnectionUncertainty(owner)
        } else {
            requireProcessRecovery(owner, cleanupUncertaintyToken)
        }
        return result
    }

    fun markConnectionUncertain(owner: NativePlayerOwner): Long {
        while (true) {
            if (owner.ownershipDefinitivelyReleased.get()) return 0L
            val existing = owner.providerUncertaintyToken.get()
            if (existing != 0L) return existing
            val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
            if (owner.ownershipDefinitivelyReleased.get()) {
                ProviderConnectionSafety.resolveDefinitiveLocalStop(token)
                return 0L
            }
            if (owner.providerUncertaintyToken.compareAndSet(0L, token)) {
                // Cleanup may publish proof immediately after the pre-CAS check.
                if (
                    owner.ownershipDefinitivelyReleased.get() &&
                    owner.providerUncertaintyToken.compareAndSet(token, 0L)
                ) {
                    ProviderConnectionSafety.resolveDefinitiveLocalStop(token)
                    return 0L
                }
                return token
            }
            ProviderConnectionSafety.resolveDefinitiveLocalStop(token)
        }
    }

    fun resolveConnectionUncertainty(owner: NativePlayerOwner) {
        val token = owner.providerUncertaintyToken.getAndSet(0L)
        if (token != 0L) ProviderConnectionSafety.resolveDefinitiveLocalStop(token)
    }

    fun requireProcessRecovery(
        owner: NativePlayerOwner,
        token: Long = owner.providerUncertaintyToken.get(),
    ) {
        if (token != 0L) {
            ProviderConnectionSafety.requireLocalProcessRecovery(token)
        } else if (!owner.ownershipDefinitivelyReleased.get()) {
            ProviderConnectionSafety.block(
                ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP,
            )
        }
    }

    /**
     * Removing an Android View is main-thread-only. Native listener/stop/release
     * teardown remains exclusively on the owner worker.
     */
    fun removeLayoutOnMain(owner: NativePlayerOwner) {
        owner.surfaceRetirement.requestRemoval {
            val remove = {
                (owner.videoLayout.parent as? ViewGroup)?.removeView(owner.videoLayout)
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                remove()
            } else {
                handler.post { remove() }
            }
        }
    }

    private fun clearCurrent(owner: NativePlayerOwner) {
        if (current !== owner) return
        current = null
        host.onOwnerCleared(owner)
    }

    /**
     * Quarantine never calls a native method. It only invalidates callbacks,
     * removes the owner from all current fields, and hands cleanup ownership to
     * the already-running retired worker. A replacement can then be built without
     * ever touching the timed-out MediaPlayer/LibVLC handles.
     */
    fun quarantine(owner: NativePlayerOwner, reason: String) {
        VlcSurfaceViews.retire(owner.videoLayout)
        // Quarantine means native ownership is no longer synchronously provable.
        // Register the exact owner before clearing current fields so every caller
        // (including future recovery paths) preserves the one-connection gate.
        var quarantineToken = 0L
        if (!owner.ownershipDefinitivelyReleased.get()) {
            quarantineToken = markConnectionUncertain(owner)
        }
        // Close the false->mark race against cleanup publishing definitive proof.
        if (
            quarantineToken != 0L &&
            owner.ownershipDefinitivelyReleased.get() &&
            owner.providerUncertaintyToken.compareAndSet(quarantineToken, 0L)
        ) {
            ProviderConnectionSafety.resolveDefinitiveLocalStop(quarantineToken)
        }
        val wasCurrent = current === owner
        if (wasCurrent) host.invalidateEventSession()
        owner.abandoned.set(true)
        clearCurrent(owner)
        // A view removal re-enters libVLC through SurfaceHolder callbacks. Defer
        // it until release proof; the provider gate prevents a replacement start.
        removeLayoutOnMain(owner)
        host.log("owner=${owner.generation} quarantined: $reason")

        val active = owner.activeOperation.get()
        active?.onAbandoned?.getAndSet(null)?.invoke()
        val needsSafeCleanup = when {
            active == null -> true
            active.state.compareAndSet(
                OwnerOperation.STATE_ACTIVE,
                OwnerOperation.STATE_ABANDONED,
            ) -> false
            active.state.get() == OwnerOperation.STATE_BODY_FINISHED -> true
            else -> false
        }
        if (needsSafeCleanup && !owner.ownershipDefinitivelyReleased.get()) {
            scheduleQuarantinedCleanup(owner)
        }
    }

    /**
     * Used only when the previous operation has already finished its native body;
     * therefore the cleanup worker cannot overlap native access to this owner.
     */
    private fun scheduleQuarantinedCleanup(owner: NativePlayerOwner) {
        if (
            owner.cleanupClaimed.get() ||
            !owner.cleanupScheduled.compareAndSet(false, true)
        ) {
            return
        }
        val cleanupUncertaintyToken = markConnectionUncertain(owner)
        VlcOps.postBounded(
            timeoutMs = releaseTimeoutMs,
            onTimeout = {
                requireProcessRecovery(owner, cleanupUncertaintyToken)
                host.log("owner=${owner.generation} quarantined cleanup timed out")
            },
        ) {
            cleanupOnCurrentWorker(owner)
        }
    }

    /**
     * Reserve an owner for planned rebuild/destroy cleanup. Native command idle
     * is not decoder idle: detachViews itself can block on main. Stop/release
     * must finish on the owner worker before its layout can be removed.
     */
    fun retireIdleOnMain(owner: NativePlayerOwner): OwnerOperation? {
        val operation = beginOperation(owner) ?: return null
        VlcSurfaceViews.retire(owner.videoLayout)
        host.invalidateEventSession()
        owner.abandoned.set(true)
        removeLayoutOnMain(owner)
        clearCurrent(owner)
        return operation
    }
}
