/*
 * CastController.kt
 * Per-activity glue around [CastHelper]. Listens for Cast sessions and pushes the
 * current stream to the connected device; surfaces a route chooser when the user
 * taps the cast button. Every call is guarded so devices without Play services
 * (most TV boxes) simply get a "cast unavailable" toast instead of a crash.
 */
package com.iptv.player.cast

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.mediarouter.app.MediaRouteChooserDialog
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.iptv.player.R

class CastController(
    private val activity: Activity,
    /** Close the local provider socket, then invoke the continuation exactly once. */
    private val onCastLoadStarting: ((onQuiesced: (Boolean) -> Unit) -> Unit)? = null,
    /** Local/receiver ownership is uncertain; do not auto-open another socket. */
    private val onCastQuiesceFailed: (() -> Unit)? = null,
    /** Restore local playback when the receiver rejects or loses a pending load. */
    private val onCastLoadFailed: (() -> Unit)? = null,
    private val onCastStarted: (() -> Unit)? = null,
    private val onCastEnded: (() -> Unit)? = null,
    private val mediaProvider: () -> CastMedia?,
) {

    data class CastMedia(
        val url: String,
        val title: String,
        val imageUrl: String?,
        val isLive: Boolean,
        /** Resume position handed to the receiver for buffered VOD. */
        val startPositionMs: Long = 0L,
    )

    private data class PendingLoad(
        val generation: Long,
        val media: CastMedia,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attached = false
    private var loadGeneration = 0L
    private var quiesceGeneration = 0L
    private var castOwnsPlayback = false
    private var localQuiescedForLoad = false
    private var quiesceInFlight = false
    private var pendingLoad: PendingLoad? = null
    private var submittedLoadGeneration: Long? = null
    private var quiesceTimeout: Runnable? = null
    private var receiverLoadTimeout: Runnable? = null
    private var receiverPositionMs = 0L
    private var sessionSuspended = false
    private var ownershipUncertainty: ProviderConnectionSafety.Uncertainty? = null
    private var remoteOwnerToken = 0L

    /** Most recent buffered-media position captured before the receiver disconnected. */
    val lastKnownPositionMs: Long
        get() = receiverPositionMs

    private val isTelevision: Boolean
        get() =
            (activity.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    val isAvailable: Boolean
        get() = CastDevicePolicy.shouldInitialize(isTelevision) &&
            CastHelper.isAvailable(activity)

    val isConnected: Boolean
        get() = isAvailable && CastHelper.isConnected(activity)

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            sessionSuspended = false
            loadCurrent()
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            sessionSuspended = false
            loadCurrent()
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            sessionSuspended = false
            if (quiesceInFlight) failLocalQuiesce() else cancelPendingLoad(restoreLocal = true)
        }
        override fun onSessionEnding(session: CastSession) {
            sessionSuspended = true
            captureReceiverPosition()
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            sessionSuspended = false
            finishCastOwnership()
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            // A failed control-channel resume is not proof that the receiver
            // stopped fetching media. Keep local ownership quiesced until the SDK
            // delivers a definitive session end.
            sessionSuspended = true
            captureReceiverPosition()
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            sessionSuspended = true
            captureReceiverPosition()
        }
    }

    fun attach() {
        if (!isAvailable || attached) return
        val sessionManager = CastHelper.castContext(activity)?.sessionManager ?: return
        runCatching {
            sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        }.onSuccess {
            attached = true
            // A previous screen may have failed closed while a Cast session was
            // suspended or a receiver result was unresolved. If there is now no
            // SDK session at all, the receiver has definitively ended and the new
            // screen may safely regain local ownership. An existing (even
            // disconnected/suspended) session remains blocked until onSessionEnded.
            val currentSession = sessionManager.currentCastSession
            if (currentSession == null) {
                ownershipUncertainty = null
                ProviderConnectionSafety.resolveDefinitiveCastEnd()
            } else {
                val becameOwner = !castOwnsPlayback
                castOwnsPlayback = true
                ensureRemoteOwnershipToken()
                if (becameOwner) runCatching { onCastStarted?.invoke() }
            }
        }
    }

    fun detach() {
        if (!attached) return
        ProviderConnectionSafety.uncertaintyForControllerDetach(
            localQuiesceInFlight = quiesceInFlight,
            receiverLoadSubmitted = submittedLoadGeneration != null,
            sessionSuspended = sessionSuspended,
            receiverOwnsPlayback = castOwnsPlayback,
        )?.let { reason ->
            ownershipUncertainty = reason
            registerConnectionUncertainty(reason)
        }
        cancelPendingLoad(restoreLocal = false)
        sessionSuspended = false
        attached = false
        runCatching {
            CastHelper.castContext(activity)
                ?.sessionManager
                ?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
    }

    /** Cast button handler: load if connected, otherwise show the route chooser. */
    fun onCastButtonClicked() {
        if (!isAvailable) {
            toast(R.string.cast_unavailable)
            return
        }
        val ctx = CastHelper.castContext(activity)
        if (ctx == null) {
            toast(R.string.cast_unavailable)
            return
        }
        if (CastHelper.isConnected(activity)) {
            loadCurrent()
            return
        }
        val selector = runCatching { ctx.mergedSelector }.getOrNull()
        if (selector == null) {
            toast(R.string.cast_unavailable)
            return
        }
        runCatching {
            MediaRouteChooserDialog(activity).apply { routeSelector = selector }.show()
        }.onFailure { toast(R.string.cast_unavailable) }
    }

    /** Reload the provider's latest channel/file on an already-connected receiver. */
    fun reloadCurrentMedia() {
        if (isConnected) loadCurrent()
    }

    private fun loadCurrent() {
        if (!connectionOperationAllowed()) {
            pendingLoad = null
            runCatching { onCastQuiesceFailed?.invoke() }
            toast(R.string.cast_unavailable)
            return
        }
        val media = mediaProvider() ?: return
        pendingLoad = PendingLoad(++loadGeneration, media)
        advanceLoadState()
    }

    /**
     * Serializes local socket shutdown and receiver loads. A newer channel/file
     * replaces the pending request, but never opens a second receiver load while
     * the previous request is unresolved.
     */
    private fun advanceLoadState() {
        if (
            !attached ||
            !connectionOperationAllowed() ||
            sessionSuspended ||
            activity.isFinishing ||
            activity.isDestroyed
        ) return
        if (submittedLoadGeneration != null || quiesceInFlight) return
        if (!castOwnsPlayback && !localQuiescedForLoad) {
            beginLocalQuiesce()
            return
        }
        val request = pendingLoad ?: return
        pendingLoad = null
        submitReceiverLoad(request)
    }

    private fun beginLocalQuiesce() {
        val callback = onCastLoadStarting
        val attempt = ++quiesceGeneration
        quiesceInFlight = true
        // The activity may already have paused audio/finalized UI state even if
        // a vendor JNI stop later times out, so failure must restore it locally.
        localQuiescedForLoad = true
        val timeout = Runnable {
            if (!quiesceInFlight || attempt != quiesceGeneration) return@Runnable
            failLocalQuiesce()
        }
        quiesceTimeout = timeout
        mainHandler.postDelayed(timeout, LOCAL_QUIESCE_TIMEOUT_MS)

        var continued = false
        val onQuiesced = onQuiesced@{ stopped: Boolean ->
            activity.runOnUiThread {
                if (continued) return@runOnUiThread
                continued = true
                if (
                    !attached ||
                    activity.isFinishing ||
                    activity.isDestroyed ||
                    !quiesceInFlight ||
                    attempt != quiesceGeneration
                ) return@runOnUiThread
                if (!stopped) {
                    failLocalQuiesce()
                    return@runOnUiThread
                }
                quiesceInFlight = false
                clearQuiesceTimeout()
                advanceLoadState()
            }
        }
        val failed = runCatching {
            if (callback == null) onQuiesced(true) else callback(onQuiesced)
        }.isFailure
        if (failed && quiesceInFlight && attempt == quiesceGeneration) {
            failLocalQuiesce()
        }
    }

    private fun submitReceiverLoad(request: PendingLoad) {
        // Reserve remote ownership before the asynchronous request crosses the
        // process boundary. A lost result can mean the receiver already opened
        // the provider URL, so no local screen may use the gap.
        ensureRemoteOwnershipToken()
        submittedLoadGeneration = request.generation
        val timeout = Runnable {
            if (submittedLoadGeneration != request.generation) return@Runnable
            submittedLoadGeneration = null
            pendingLoad = null
            loadGeneration++
            if (!sessionSuspended && !castOwnsPlayback) {
                // No callback is not a rejection: the receiver may have accepted
                // the load while the sender lost its result. Fail closed instead
                // of reopening local playback under a possibly active receiver.
                failClosed(ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT)
            } else if (!sessionSuspended) {
                toast(R.string.cast_unavailable)
            }
        }
        receiverLoadTimeout = timeout
        mainHandler.postDelayed(timeout, RECEIVER_LOAD_TIMEOUT_MS)
        val submitted = CastHelper.loadMedia(
            context = activity,
            url = request.media.url,
            title = request.media.title,
            imageUrl = request.media.imageUrl,
            isLive = request.media.isLive,
            startPositionMs = request.media.startPositionMs,
            onResult = { loaded ->
                activity.runOnUiThread {
                    if (submittedLoadGeneration != request.generation) {
                        return@runOnUiThread
                    }
                    submittedLoadGeneration = null
                    clearReceiverLoadTimeout()
                    if (!attached || activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                    // A successful RemoteMediaClient result is the ownership
                    // proof. isConnected can transiently lag the result; a real
                    // end invalidates submittedLoadGeneration before this branch.
                    val receiverAccepted = loaded
                    if (receiverAccepted) {
                        ensureRemoteOwnershipToken()
                        receiverPositionMs = request.media.startPositionMs.coerceAtLeast(0L)
                        val becameOwner = !castOwnsPlayback
                        localQuiescedForLoad = false
                        castOwnsPlayback = true
                        if (becameOwner) runCatching { onCastStarted?.invoke() }
                    }

                    // A channel/file changed while the receiver request was in
                    // flight. Let the latest request win before transferring
                    // ownership or restoring the local socket.
                    if (pendingLoad != null) {
                        advanceLoadState()
                    } else if (receiverAccepted) {
                        toast(R.string.cast_started)
                    } else if (!sessionSuspended && !castOwnsPlayback) {
                        resolveRemoteOwnershipToken()
                        restoreQuiescedLocal()
                        toast(R.string.cast_unavailable)
                    } else if (!sessionSuspended) {
                        // A reload failed after the receiver already owned the
                        // stream. Never reopen local playback underneath it.
                        toast(R.string.cast_unavailable)
                    }
                }
            },
        )
        if (!submitted) {
            submittedLoadGeneration = null
            clearReceiverLoadTimeout()
            if (pendingLoad != null) {
                advanceLoadState()
            } else if (!sessionSuspended && !castOwnsPlayback) {
                resolveRemoteOwnershipToken()
                restoreQuiescedLocal()
                toast(R.string.cast_unavailable)
            } else if (!sessionSuspended) {
                toast(R.string.cast_unavailable)
            }
        }
    }

    private fun captureReceiverPosition() {
        CastHelper.currentPositionMs(activity)?.let { receiverPositionMs = it }
    }

    private fun finishCastOwnership() {
        // onSessionEnded is definitive remote proof even when a separate local
        // native token currently has higher fail-closed priority.
        ProviderConnectionSafety.resolveDefinitiveCastEnd()
        resolveRemoteOwnershipToken()
        if (quiesceInFlight) {
            // The receiver session ended, but a vendor-local stop is still
            // unresolved. Do not race it by reopening the provider connection.
            failClosed(ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP)
            return
        }
        val uncertainty = ownershipUncertainty
            ?: ProviderConnectionSafety.currentUncertainty()
        when (uncertainty) {
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT -> {
                val receiverOwnedPlayback = castOwnsPlayback
                castOwnsPlayback = false
                ownershipUncertainty = null
                ProviderConnectionSafety.resolveDefinitiveCastEnd()
                cancelPendingLoad(restoreLocal = false)
                if (receiverOwnedPlayback) {
                    runCatching { onCastEnded?.invoke() }
                } else {
                    runCatching { onCastLoadFailed?.invoke() }
                }
                return
            }
            ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP -> {
                castOwnsPlayback = false
                cancelPendingLoad(restoreLocal = false)
                runCatching { onCastQuiesceFailed?.invoke() }
                return
            }
            null -> Unit
        }
        val receiverOwnedPlayback = castOwnsPlayback
        castOwnsPlayback = false
        cancelPendingLoad(restoreLocal = !receiverOwnedPlayback)
        if (receiverOwnedPlayback) {
            runCatching { onCastEnded?.invoke() }
        }
    }

    private fun restoreQuiescedLocal() {
        if (!localQuiescedForLoad || castOwnsPlayback) return
        localQuiescedForLoad = false
        runCatching { onCastLoadFailed?.invoke() }
    }

    private fun failLocalQuiesce() {
        failClosed(ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP)
    }

    private fun failClosed(reason: ProviderConnectionSafety.Uncertainty) {
        quiesceGeneration++
        loadGeneration++
        quiesceInFlight = false
        pendingLoad = null
        submittedLoadGeneration = null
        clearQuiesceTimeout()
        clearReceiverLoadTimeout()
        // A retired native call or an accepted-but-unacknowledged receiver may
        // still own its socket. Clearing our logical flag prevents the ordinary
        // receiver-failure path from auto-opening a second local player; the UI
        // exposes an explicit blocked/error state instead.
        localQuiescedForLoad = false
        ownershipUncertainty = reason
        registerConnectionUncertainty(reason)
        runCatching { onCastQuiesceFailed?.invoke() }
        toast(R.string.cast_unavailable)
    }

    /**
     * The player engine owns exact LOCAL tokens. Do not layer an unresolvable
     * process-wide bit over that token; only create an unowned recovery marker
     * when a lifecycle race genuinely lost the engine's registration. Remote
     * receiver ambiguity remains independently fail-closed until Cast ends.
     */
    private fun registerConnectionUncertainty(reason: ProviderConnectionSafety.Uncertainty) {
        when (reason) {
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT -> {
                val token = remoteOwnerToken
                remoteOwnerToken = 0L
                if (token != 0L) {
                    ProviderConnectionSafety.transferRemoteOwnershipToUncertainty(token)
                } else {
                    ProviderConnectionSafety.block(reason)
                }
            }
            ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP -> {
                if (ProviderConnectionSafety.snapshot().localPendingCount == 0) {
                    ProviderConnectionSafety.block(reason)
                }
            }
        }
    }

    private fun ensureRemoteOwnershipToken() {
        if (remoteOwnerToken == 0L) {
            remoteOwnerToken = ProviderConnectionSafety.beginRemoteOwnership()
        }
    }

    private fun resolveRemoteOwnershipToken() {
        val token = remoteOwnerToken
        remoteOwnerToken = 0L
        if (token != 0L) {
            ProviderConnectionSafety.resolveDefinitiveRemoteOwnership(token)
        }
    }

    /** The active receiver may replace its own media, but no ambiguous/local owner may overlap. */
    private fun connectionOperationAllowed(): Boolean {
        val safety = ProviderConnectionSafety.snapshot()
        if (safety.newConnectionAllowed) return true
        return remoteOwnerToken != 0L &&
            !safety.remoteUncertain &&
            safety.localPendingCount == 0 &&
            safety.activeRemoteOwnerCount == 1
    }

    private fun cancelPendingLoad(restoreLocal: Boolean) {
        loadGeneration++
        quiesceGeneration++
        pendingLoad = null
        submittedLoadGeneration = null
        quiesceInFlight = false
        clearQuiesceTimeout()
        clearReceiverLoadTimeout()
        if (restoreLocal) {
            if (!castOwnsPlayback) resolveRemoteOwnershipToken()
            restoreQuiescedLocal()
        } else {
            localQuiescedForLoad = false
        }
    }

    private fun clearQuiesceTimeout() {
        quiesceTimeout?.let(mainHandler::removeCallbacks)
        quiesceTimeout = null
    }

    private fun clearReceiverLoadTimeout() {
        receiverLoadTimeout?.let(mainHandler::removeCallbacks)
        receiverLoadTimeout = null
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val LOCAL_QUIESCE_TIMEOUT_MS = 12_000L
        const val RECEIVER_LOAD_TIMEOUT_MS = 15_000L
    }
}
