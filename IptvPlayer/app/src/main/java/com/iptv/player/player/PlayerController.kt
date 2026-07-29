/*
 * PlayerController.kt
 * Orchestrates the two engines and their reconnect/fallback policy — one
 * connection at a time (full stop -> release -> restart).
 *
 * AUTO + automatic decoder is reason-aware:
 *   audio/source failure: EXO -> VLC_HW -> VLC_SW
 *   invalid video/decode: EXO/VLC_HW -> VLC_SW
 *
 * Manual choices remain the preferred path for ordinary errors. A confirmed
 * green/frozen/unsupported decode may use one bounded compatibility fallback so
 * the user is never trapped on an unusable "only" path. Pure decisions live in
 * PlaybackRoutingPolicy and are covered by JVM tests.
 */
package com.iptv.player.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.player.PlaybackRoutingPolicy.Failure as Reason
import com.iptv.player.player.PlaybackRoutingPolicy.Stage
import com.iptv.player.util.PlaybackLog
import com.iptv.player.util.PlaybackRouteMemory
import com.iptv.player.util.StabilityTelemetry

class PlayerController(
    // var (not val) so a handed-over controller can be re-homed onto the
    // fullscreen player: context + container are reassigned on rebind() and the
    // callback is swapped via setCallback() when ownership transfers.
    private var context: Context,
    private var container: ViewGroup,
    private val mode: PlayerMode,
    private val decoderMode: DecoderMode = DecoderMode.AUTO,
    private val allowPassthrough: Boolean = false,
    private val bufferMode: BufferMode = BufferMode.NORMAL,
    // Live TV gets the same-channel auto-reconnect loop (a dropped/ended live
    // stream re-opens itself); set false for any non-live reuse so a stream that
    // legitimately ends is not endlessly re-opened.
    private val isLive: Boolean = true,
    private var callback: Callback
) {

    /** Swap the UI callback (used when a preview controller is adopted by the player). */
    fun setCallback(cb: Callback) { callback = cb }

    /**
     * Re-home the currently running engine onto [newContainer] for a live
     * preview <-> fullscreen hand-off, then RESTART the stream on the same engine
     * so the picture reliably reappears on the new surface.
     *
     * The detach -> delay -> attach sequence mirrors the engine-swap timing: a
     * SurfaceView's underlying surface is torn down asynchronously, so adding the
     * surface to the new container in the same pass would race that teardown and
     * green the fresh surface on Amlogic. Deferring the re-attach clears it.
     *
     * A bare re-attach (no new play) was NOT enough: the decoder's video output
     * stayed bound to the old, now-destroyed surface, so audio kept playing but no
     * frames composited (black video, esp. the Amlogic MediaCodec underlay) and no
     * fresh Vout/first-frame event fired — leaving the hand-off cover / preview
     * logo stuck until a timeout. Re-issuing play() on the SAME engine recreates
     * the output on the new surface and emits a frame event (this is exactly why a
     * channel zap already "fixed" the black picture). Single-connection is
     * preserved: every engine.play() stops the current stream before starting.
     *
     * Rapid back-and-forth hand-offs (preview -> fullscreen -> BACK within the
     * ENGINE_SWAP_DELAY_MS window) would otherwise stack two delayed re-attaches
     * that both run, churning surface ownership. We reuse the startGeneration token:
     * each rebind bumps it, so only the latest delayed runnable executes, and we
     * re-check the engine is still the active one before touching it. (A play() in
     * between already clears the handler, so that path is covered too.)
     */
    fun rebind(newContainer: ViewGroup) {
        context = newContainer.context
        container = newContainer
        val eng = engine ?: return
        val url = currentUrl
        val generation = ++startGeneration
        // The replay below is a restart boundary for the watchdog: drop the stall
        // poll now — its position baseline belongs to the pre-handoff surface, and
        // the reissued play() resets the engine clock toward 0, which the old
        // baseline would read as "no progress" and false-trigger a reconnect.
        cancelWatchdog()
        eng.detachVideo()
        mainHandler.postDelayed({
            if (generation != startGeneration || eng !== engine) return@postDelayed
            eng.attachVideo(newContainer)
            // reset=true: hand-off needs a deterministic decoder/renderer reset
            // onto the re-attached surface (bare re-add can stall video).
            if (url != null) {
                // Re-baseline as a fresh (re)start: clear the prior confirmation +
                // stable timer so the next real frame runs onPlaybackProgress and
                // re-arms the stall poll against the new clock; the startup timeout
                // covers the replay until that frame arrives.
                playbackConfirmed = false
                videoOutputConfirmed = false
                stageStartMs = 0L
                stableHandler.removeCallbacksAndMessages(null)
                eng.play(url, reset = true)
                armStartupTimeout()
            }
        }, ENGINE_SWAP_DELAY_MS)
    }

    /** UI-facing events from the controller (already engine-agnostic). */
    interface Callback {
        fun onBuffering()
        fun onPlaying(engineName: String)
        /**
         * The controller is recreating/re-routing the decoder. UIs that gate
         * readiness on a real frame must discard any frame state from the old stage.
         */
        fun onPlaybackRestarting() {}
        /**
         * A real video frame is now on screen. Fires on every video-output start
         * (initial play, zap, and after a preview->fullscreen surface hand-off).
         * Default no-op so callbacks that don't care need not implement it.
         */
        fun onVideoResumed() {}
        /** Emitted only after all retries + fallback are exhausted. */
        fun onFatalError()
        fun onRetrying(attempt: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: PlayerEngine? = null
    private var currentUrl: String? = null
    private var stage = Stage.EXO
    private val triedStages = mutableSetOf<Stage>()
    // Bumped on every (re)start so a delayed engine creation that has been
    // superseded becomes a no-op. Guards the SurfaceView-swap handoff gap.
    private var startGeneration = 0

    // ---- Per-channel route memory (Tier 2 self-healing) -------------------
    // Remembers which stage a channel last proved STABLE on and starts there next
    // time, skipping the failing ladder steps the user already waited through.
    // ACTIVE for live + automatic decoder. AUTO may remember any compatible
    // engine stage; an explicit VLC choice may remember only VLC hardware/software.
    // ExoPlayer and explicit decoder choices are never overridden by memory.
    private var currentRawRouteKey: String? = null
    private var currentRouteKey: String? = null
    // True while the CURRENT play started on a remembered stage that DIFFERS from
    // the cold base stage and has not yet proved stable. If it fails before then,
    // we distrust the memory and restart from the base ladder (see handleFailure).
    // Cleared the moment the stage proves stable, so later drops use the normal
    // same-stage reconnect path.
    private var usingRememberedRoute = false
    // Set once a remembered route has been distrusted on this play so the base
    // ladder is used for the rest of it (and memory isn't re-read mid-stream).
    private var memoryIgnoredThisPlay = false
    // A generic source/startup failure can be a transient CDN outage shared by
    // every engine. We may try another stage for immediate recovery, but must not
    // remember that accidental winner as a decoder preference for fourteen days.
    private var routeLearningAllowed = true

    /** Route memory only steers fully-automatic live playback. */
    private val routeMemoryEligible: Boolean
        get() = isLive &&
            decoderMode == DecoderMode.AUTO &&
            (mode == PlayerMode.AUTO || mode == PlayerMode.VLC)

    // True once the CURRENT stage produced confirmed playback (onPlaying /
    // onVideoOutput). A plain ERROR after this is a mid-stream DROP, not a
    // startup decode problem, so it reconnects the same stage instead of walking
    // the decode-fallback ladder onto another engine. Reset on every (re)start.
    private var playbackConfirmed = false
    // Kept separate from onPlaying because audio/cache readiness can advance even
    // while the video surface is green or frozen. Route memory is written only
    // after the engine supplies a real frame signal.
    private var videoOutputConfirmed = false

    // When the current stage's playback was confirmed (first frame / onPlaying),
    // so we can tell a stream that played stably for a while from one that died a
    // second or two after rendering its first frame (the Amlogic MPEG2 hardware
    // decoder loop). 0 = not confirmed on this stage yet.
    private var stageStartMs = 0L

    // Consecutive HARDWARE decode failures on the current stream. A hardware
    // decoder that renders a frame then dies ~1.5s later would otherwise reconnect
    // to the SAME dead decoder forever; after MAX_QUICK_DECODE_FAILURES of these we
    // force the stream onto software decode instead. NOT cleared by resetReconnect:
    // a brief first frame must not reset it (that is exactly what made the counter
    // restart every loop). Cleared on a new channel, a genuine stage change, and a
    // stretch of stable playback.
    private var quickDecodeFailures = 0
    // Generic errors before any playback may be transport noise once, but repeated
    // unconfirmed failures mean the preferred engine cannot start this stream.
    // Preserve this across same-stage reconnects and route after the second one.
    private var unconfirmedStartFailures = 0

    // Fires once the current stage has played for STABLE_PLAYBACK_MS without
    // failing: only THEN is playback treated as truly recovered (reconnect window
    // + quick-failure counter reset). Kept separate from the reconnect/engine-swap
    // handlers so a brief first frame can't mark a looping stream as recovered.
    private val stableHandler = Handler(Looper.getMainLooper())

    private var audioDelayMs = 0L
    private var subtitleDelayMs = 0L

    // ---- Live-TV auto-reconnect -------------------------------------------
    // Layered ABOVE the decode-fallback ladder: once the ladder has no untried
    // engine/decode path left (or a working live stream drops or ends), this
    // loop re-opens the SAME channel with a backoff schedule bounded by a total
    // window, so a brief server restart (~2-3s) recovers by itself. Each retry
    // fully releases the previous connection (stop -> release) before the next,
    // so two connections never stack. The schedule + window reset on confirmed
    // playback, so a later, separate drop gets a fresh full set of attempts.
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var reconnectWindowStartMs = 0L
    private var reconnecting = false
    // Guards against stacking: a single drop can fire BOTH EncounteredError and
    // EndReached; only one attempt may be scheduled/in-flight at a time.
    private var reconnectPending = false

    // Lifecycle gate. Engine callbacks can arrive after Activity.onStop() (notably
    // a native VLC error queued just before pause). Never let such a late callback
    // start a hidden reconnect in the background; remember it and recover once the
    // owner resumes instead.
    private var suspended = false
    private var recoveryNeededOnResume = false

    // ---- Stall / startup watchdog -----------------------------------------
    // The reconnect loop above is ENTIRELY event-driven (EndReached / error). A
    // half-open upstream connection (NAT / load-balancer idle or max-age timeout,
    // ~1-2h on real IPTV providers) can leave the engine BLOCKED reading with no
    // EOF and no error event, so nothing fires and the picture silently freezes
    // until the user kills the app. This watchdog supplies the missing signal:
    //   - mid-stream: once playback is confirmed, poll the engine's playback clock;
    //     if it stops advancing for STALL_TIMEOUT_MS the stream has silently stalled
    //     -> force a fresh reconnect (full release+recreate = new socket).
    //   - startup/reconnect attempt: if a (re)start never reaches confirmed playback
    //     within STARTUP_TIMEOUT_MS (a connection that opens but delivers no data),
    //     treat it as an error so the normal ladder/reconnect sequencing runs.
    // Dedicated handler so the controller's other removeCallbacksAndMessages(null)
    // calls can't clobber it. Generation-guarded against an already-dequeued post.
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogGen = 0
    private var lastPositionMs = -1L
    private var lastProgressAtMs = 0L

    /** True when the active engine can apply audio/subtitle delay (libVLC). */
    val supportsDelay: Boolean get() = engine?.supportsDelay == true

    val currentAudioDelayMs: Long get() = audioDelayMs
    val currentSubtitleDelayMs: Long get() = subtitleDelayMs

    fun setAudioDelay(ms: Long) {
        audioDelayMs = ms
        engine?.setAudioDelayMs(ms)
    }

    fun setSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms
        engine?.setSubtitleDelayMs(ms)
    }

    fun play(url: String, routeKey: String? = null) {
        suspended = false
        recoveryNeededOnResume = false
        // Cancel any pending retry from a previous stream so zapping is clean.
        mainHandler.removeCallbacksAndMessages(null)
        // A new channel cancels any in-flight reconnect so it can't fire against
        // the stale stream or open a second connection.
        resetReconnect()
        // New channel: drop the stable-playback timer and the quick-decode-failure
        // count from the previous stream so they can't bleed into this one.
        stableHandler.removeCallbacksAndMessages(null)
        quickDecodeFailures = 0
        unconfirmedStartFailures = 0
        // Tear down the previous channel's stall/startup watchdog; the (re)start
        // path below re-arms it for this channel.
        cancelWatchdog()
        currentUrl = url
        // A route learned under VLC/Auto must not override a later Exo/Hardware
        // preference (or vice versa). The source/policy fingerprint is already in
        // routeKey; namespace it by the active settings pair as well.
        currentRawRouteKey = routeKey
        currentRouteKey = routeKey?.let { "${mode.name}|${decoderMode.name}|$it" }
        playbackConfirmed = false
        videoOutputConfirmed = false
        memoryIgnoredThisPlay = false
        routeLearningAllowed = true
        // Tier 2 self-healing: prefer the stage this channel last proved STABLE on
        // (route memory) over the cold base ladder, so a channel that always needs,
        // say, software decode starts there instead of greening on hardware first.
        val base = baseInitialStage()
        val remembered = rememberedStage()
        val initial = remembered ?: base
        // Only treat it as a "remembered route" (eligible for the distrust-on-fail
        // restart) when memory actually changes the starting stage.
        usingRememberedRoute = remembered != null && remembered != base

        // Fast zap: when the next stream would start on the very same stage the
        // current engine is already running, keep that engine alive and just swap
        // the stream on it. This skips the full release + re-create of a fresh
        // LibVLC/ExoPlayer instance AND the ENGINE_SWAP_DELAY_MS surface-handoff
        // guard. The SurfaceView is retained (no teardown), so it sidesteps the
        // swap-gap green frame — but the decoder is still cleanly reconfigured for
        // the new stream (reset below) so a resolution change can't freeze it.
        // Only the steady state qualifies: if the current stream had already
        // fallen back to a different stage, drop through to a clean restart so the
        // new channel still gets the full hardware-first fallback ladder.
        val reusable = engine
        if (reusable != null && stage == initial) {
            // A fast zap keeps the same SurfaceView, but the frame currently on it
            // belongs to the previous channel and the next decoder may briefly
            // output green while its format settles. Re-arm the UI cover exactly
            // like a full stage restart; the verified frame callback removes it.
            callback.onPlaybackRestarting()
            triedStages.clear()
            triedStages.add(initial)
            // reset=true: a zap to a DIFFERENT-resolution stream must reconfigure
            // the decoder. ExoPlayer's no-stop swap can leave the Amlogic OMX codec
            // locked to the previous channel's geometry (e.g. 1080p<->720x576), so
            // the new size never renders and the picture freezes on the differing
            // channel. A clean stop()+prepare on the SAME engine rebuilds the codec
            // for the new size WITHOUT recreating the SurfaceView (no Amlogic green).
            // Still far cheaper than a full engine release+recreate: no new player
            // instance, no ENGINE_SWAP surface-handoff gap. (libVLC already rebuilds
            // a fresh Media + stop()s every play(), so reset is a no-op there.)
            PlaybackLog.log(context, "Controller", "fast-zap reuse stage=$initial reset")
            reusable.play(url, reset = true)
            // The reused engine swaps in a fresh Media on a new socket, so cover the
            // zap with the startup timeout too (a silently stalling new channel that
            // never produces a frame would otherwise hang with no event).
            armStartupTimeout()
            return
        }

        triedStages.clear()
        stage = initial
        PlaybackLog.log(
            context, "Controller",
            "play mode=$mode decoder=$decoderMode start=$stage" +
                if (usingRememberedRoute) " (route memory)" else ""
        )
        startStage(stage)
    }

    /**
     * First stage to try from a COLD start, given the engine choice and decoder
     * strategy (no route memory). [play] prefers [rememberedStage] over this.
     */
    private fun baseInitialStage(): Stage =
        PlaybackRoutingPolicy.initialStage(mode, decoderMode)

    /**
     * The stage this channel last proved STABLE on, or null when route memory does
     * not apply (ineligible mode, no key, no/expired entry, distrusted this play).
     * A purely in-memory read — never touches disk on this hot path.
     */
    private fun rememberedStage(): Stage? {
        if (memoryIgnoredThisPlay || !routeMemoryEligible) return null
        val name = PlaybackRouteMemory.bestStage(currentRouteKey) ?: return null
        val s = runCatching { Stage.valueOf(name) }.getOrNull() ?: return null
        if (mode == PlayerMode.VLC && s == Stage.EXO) return null
        return s
    }

    private fun startStage(target: Stage) {
        callback.onPlaybackRestarting()
        // A GENUINE stage change (ladder move / escalation) starts a fresh decoder,
        // so reset the quick-decode-failure count. A reconnect replay of the SAME
        // stage keeps it, so a 1.5s-then-fail loop still escalates after a couple.
        if (target != stage) {
            quickDecodeFailures = 0
            unconfirmedStartFailures = 0
        }
        triedStages.add(target)
        stage = target
        // Fresh (re)start: this stage has not confirmed playback yet, so cancel the
        // previous stable-playback timer (re-armed on the next confirmed frame).
        playbackConfirmed = false
        videoOutputConfirmed = false
        stableHandler.removeCallbacksAndMessages(null)
        val useVlc = target != Stage.EXO
        val forceSoftware = target == Stage.VLC_SW
        startEngine(useVlc, forceSoftware)
        // Guard this (re)start: if it never reaches confirmed playback (a socket
        // that opens but delivers no data, firing no error), the startup timeout
        // turns the silent hang into a normal failure the ladder/reconnect handles.
        armStartupTimeout()
    }

    private fun startEngine(useVlc: Boolean, forceSoftware: Boolean) {
        // True when we are SWAPPING engines (fallback/stage change), as opposed to
        // the very first start where the container is already empty.
        val swapping = engine != null
        // Invalidate any create that is still pending from an earlier call. A stale
        // failure callback from the just-released engine can re-enter this method
        // during the swap gap (that path does NOT clear the handler), so a token
        // check stops a second, overlapping engine from being created.
        val generation = ++startGeneration
        releaseEngine()

        val create = Runnable {
            if (generation != startGeneration) return@Runnable
            val newEngine: PlayerEngine =
                if (useVlc) VlcPlayerEngine(context, forceSoftware, allowPassthrough, bufferMode.networkCachingMs)
                else ExoPlayerEngine(
                    context = context,
                    allowPassthrough = allowPassthrough,
                    bufferMode = bufferMode,
                )
            newEngine.bind(container)
            engine = newEngine
            // Capture the concrete engine instance. Native VLC callbacks can arrive
            // after release; without this identity gate a stale error from the old
            // engine can incorrectly advance/reconnect the newly-created route.
            newEngine.setListener(engineListener(newEngine))
            // Re-apply any user delay offsets to the (new) engine.
            newEngine.setAudioDelayMs(audioDelayMs)
            newEngine.setSubtitleDelayMs(subtitleDelayMs)
            currentUrl?.let { newEngine.play(it) }
        }

        if (swapping) {
            // SurfaceView handoff fix. releaseEngine() removed the previous
            // engine's SurfaceView, but a SurfaceView's underlying surface is torn
            // down ASYNCHRONOUSLY on the render thread. Adding the next engine's
            // SurfaceView and starting playback in the SAME synchronous pass races
            // that teardown: the Amlogic compositor then shows a GREEN frame on the
            // freshly-added surface even though the new engine decodes perfectly
            // (libVLC logs a healthy "output: 21 Biplanar"). This is exactly why
            // green only hit channels that fell back EXO -> VLC (e.g. MP2 audio),
            // never channels that started directly on VLC (empty container). Deferring
            // the new engine until the old surface has been destroyed clears it.
            //
            // Trampoline through the shared VLC ops thread: the old engine's
            // blocking stop/release now runs there asynchronously (ANR fix), so
            // FIFO ordering guarantees the old libVLC instance has fully released
            // its network connection (single-connection contract) before the next
            // engine is created — without ever blocking the main thread. With an
            // idle queue (e.g. old engine was ExoPlayer) the hop is immediate.
            mainHandler.postDelayed({
                VlcOps.post { mainHandler.post(create) }
            }, ENGINE_SWAP_DELAY_MS)
        } else {
            create.run()
        }
    }

    private fun engineListener(source: PlayerEngine) = object : PlayerListener {
        override fun onBuffering() = post {
            if (source !== engine || suspended) return@post
            callback.onBuffering()
        }

        override fun onPlaying() = post {
            if (source !== engine || suspended) return@post
            callback.onPlaying(source.engineName)
            onPlaybackProgress()
        }

        override fun onVideoOutput() = post {
            if (source !== engine || suspended) return@post
            val firstVideoOutput = !videoOutputConfirmed
            videoOutputConfirmed = true
            unconfirmedStartFailures = 0
            callback.onVideoResumed()
            if (firstVideoOutput && playbackConfirmed) {
                // Playing may precede the first real picture by several seconds.
                // Measure route stability from this verified frame, not from the
                // earlier audio/cache-ready event.
                stageStartMs = SystemClock.elapsedRealtime()
                armStablePlaybackTimer()
            } else {
                onPlaybackProgress()
            }
        }

        override fun onEnded() = post {
            if (source !== engine) return@post
            if (suspended) {
                recoveryNeededOnResume = true
                return@post
            }
            // A live stream should not end; the server closed/restarted it. Treat
            // as a drop and reconnect the same channel. (Non-live ignores it.)
            if (isLive) {
                PlaybackLog.log(context, "Controller", "live stream ended -> reconnect")
                engageReconnect()
            }
        }

        override fun onError(message: String?) =
            post { if (source === engine) handleEngineFailure(Reason.ERROR) }

        override fun onStartupFailure(message: String?) =
            post { if (source === engine) handleEngineFailure(Reason.STARTUP) }

        override fun onDecodeError(message: String?) =
            post { if (source === engine) handleEngineFailure(Reason.DECODE) }

        override fun onAudioUnavailable() =
            post { if (source === engine) handleEngineFailure(Reason.AUDIO) }

        override fun onVideoInvalid() =
            post { if (source === engine) handleEngineFailure(Reason.VIDEO) }

        override fun onSoftwareTooSlow() =
            post { if (source === engine) handleEngineFailure(Reason.SOFTWARE_SLOW) }
    }

    private fun handleEngineFailure(reason: Reason) {
        if (suspended) {
            recoveryNeededOnResume = true
            return
        }
        handleFailure(reason)
    }

    /**
     * Called on the first confirmed playback (onPlaying / onVideoOutput) of the
     * current stage. We mark playback confirmed and arm the stable-playback timer
     * but deliberately do NOT reset the reconnect window or the quick-decode-failure
     * counter yet: a stream that renders one frame then dies ~1.5s later (the
     * Amlogic MPEG2 hardware decoder loop) must not look "recovered", or the retry
     * counter restarts every loop and escalation to software never fires. Only
     * surviving [STABLE_PLAYBACK_MS] counts as a genuine recovery.
     */
    private fun onPlaybackProgress() {
        if (playbackConfirmed) return
        playbackConfirmed = true
        stageStartMs = SystemClock.elapsedRealtime()
        armStablePlaybackTimer()
        // First confirmed progress: swap the startup timeout for the mid-stream
        // stall poll so a later silent freeze (no EndReached/error) still recovers.
        armStallWatchdog()
    }

    /**
     * Starts (or restarts) the stable-playback window. A delayed first video frame
     * re-arms this timer, ensuring route memory always observes a full interval of
     * real displayed video.
     */
    private fun armStablePlaybackTimer() {
        stableHandler.removeCallbacksAndMessages(null)
        // Capture the stage + route key as of NOW: if this stable callback ever
        // survives to fire, it must record the route it was ARMED for, never one a
        // later (un-cancelled) transition swapped in under it.
        val stableStage = stage
        val stableKey = currentRouteKey
        stableHandler.postDelayed({
            if (suspended || stableStage != stage) return@postDelayed
            PlaybackLog.log(context, "Controller", "stable playback ${STABLE_PLAYBACK_MS}ms -> recovered")
            quickDecodeFailures = 0
            unconfirmedStartFailures = 0
            resetReconnect()
            // Learn only after a real displayed video frame. getStreamInfo()==null
            // means "unknown/not ready" as well as audio-only, so treating null as
            // radio could remember a green/no-frame stage for fourteen days.
            // Audio-only streams simply skip route memory; correctness wins over a
            // tiny startup optimisation for radio.
            if (routeMemoryEligible && routeLearningAllowed && videoOutputConfirmed) {
                PlaybackRouteMemory.markStable(stableKey, stableStage.name)
            } else if (routeMemoryEligible) {
                PlaybackLog.log(
                    context,
                    "Controller",
                    "route not learned (realVideo=$videoOutputConfirmed, " +
                        "eligibleFailure=$routeLearningAllowed)",
                )
            }
            // The remembered route (if any) is now confirmed; later drops take the
            // normal same-stage reconnect path, not the distrust-and-restart path.
            usingRememberedRoute = false
        }, STABLE_PLAYBACK_MS)
    }

    // ---- Watchdog ---------------------------------------------------------

    /** Stop any armed watchdog (startup timeout or stall poll). The generation
     *  bump neutralises a post that was already dequeued but not yet run. */
    private fun cancelWatchdog() {
        watchdogHandler.removeCallbacksAndMessages(null)
        watchdogGen++
    }

    /**
     * Ship one stability telemetry event tagged with the current engine/stage and
     * what is playing. Best-effort (never throws) so it can sit on any code path.
     */
    private fun recordStability(type: String, severity: String, detail: String? = null) {
        StabilityTelemetry.record(
            type = type,
            engine = engine?.engineName,
            stage = stage.name,
            severity = severity,
            detail = detail,
        )
    }

    /**
     * Arm the startup / reconnect-attempt timeout. If this (re)start does not reach
     * confirmed playback within [STARTUP_TIMEOUT_MS] — a connection that opens but
     * silently delivers no data, so no error event ever fires — treat it as an
     * error and let [handleFailure] run the normal decode-ladder / reconnect
     * sequencing. A real first frame (onPlaybackProgress) or a real error cancels
     * this first via the generation guard / removeCallbacks.
     */
    private fun armStartupTimeout() {
        if (!isLive) return
        cancelWatchdog()
        val gen = watchdogGen
        watchdogHandler.postDelayed({
            if (gen != watchdogGen || playbackConfirmed) return@postDelayed
            PlaybackLog.log(context, "Controller", "no playback within ${STARTUP_TIMEOUT_MS}ms -> treat as error")
            recordStability("start_timeout", "warn")
            handleFailure(Reason.STARTUP)
        }, STARTUP_TIMEOUT_MS)
    }

    /**
     * Arm the mid-stream stall poll. Baselines the playback clock, then re-reads it
     * every [WATCHDOG_POLL_MS]; if it fails to advance for [STALL_TIMEOUT_MS] the
     * live stream has silently frozen, so force a fresh reconnect.
     */
    private fun armStallWatchdog() {
        if (!isLive) return
        cancelWatchdog()
        lastPositionMs = -1L
        lastProgressAtMs = SystemClock.elapsedRealtime()
        scheduleStallPoll(watchdogGen)
    }

    private fun scheduleStallPoll(gen: Int) {
        watchdogHandler.postDelayed({
            if (gen != watchdogGen) return@postDelayed
            // While a reconnect is mid-flight the attempt's own startup timeout
            // owns recovery; just keep polling until it re-arms (or supersedes) us.
            if (reconnecting || engine == null) {
                scheduleStallPoll(gen)
                return@postDelayed
            }
            val pos = engine?.playbackPositionMs() ?: -1L
            val now = SystemClock.elapsedRealtime()
            when {
                // Keep the original deadline when the engine reports no clock.
                // Resetting on every -1 made a player that emitted Playing and
                // then died without a position hang forever.
                pos < 0 -> Unit
                // Clock advanced -> real progress, reset the stall timer.
                pos > lastPositionMs + PROGRESS_EPSILON_MS -> {
                    lastPositionMs = pos
                    lastProgressAtMs = now
                }
                // else: frozen clock -> let the stall timer accrue.
            }
            if (now - lastProgressAtMs >= STALL_TIMEOUT_MS) {
                PlaybackLog.log(context, "Controller", "live stall (no progress ${STALL_TIMEOUT_MS}ms) -> reconnect")
                recordStability("stall", "warn")
                cancelWatchdog()
                engageReconnect()
                return@postDelayed
            }
            scheduleStallPoll(gen)
        }, WATCHDOG_POLL_MS)
    }

    private fun handleFailure(reason: Reason) {
        // A failure before the stable window elapses MUST cancel the pending stable
        // callback first: a stage that rendered a frame then died near the 10s mark
        // could otherwise still fire it and FALSELY learn a failed route as good
        // (the delayed reconnect's startStage only clears it later). Harmless for a
        // post-stable mid-stream drop — the callback has already fired, so it's a
        // no-op there.
        stableHandler.removeCallbacksAndMessages(null)

        val effectiveReason =
            if (reason == Reason.ERROR && !playbackConfirmed) {
                unconfirmedStartFailures++
                if (unconfirmedStartFailures >= MAX_UNCONFIRMED_START_FAILURES) {
                    Reason.STARTUP
                } else {
                    reason
                }
            } else {
                reason
            }

        // Tier 2 self-healing: a remembered route that fails BEFORE proving stable
        // is no longer trustworthy (the channel's codec changed, or the entry is
        // stale/cross-device). Distrust it and restart this channel from the cold
        // base ladder so a bad memory can never strand the stream on a dead stage
        // or loop. Single-connection is preserved — startStage releases first. The
        // distrust flag stops this from re-firing for the rest of the play; a later
        // organic recovery still re-learns the corrected stage via markStable.
        if (usingRememberedRoute && !memoryIgnoredThisPlay) {
            usingRememberedRoute = false
            memoryIgnoredThisPlay = true
            PlaybackRouteMemory.markFailed(currentRouteKey, stage.name)
            PlaybackLog.log(
                context,
                "Controller",
                "route memory miss ($stage $effectiveReason) -> base ladder",
            )
            resetReconnect()
            quickDecodeFailures = 0
            triedStages.clear()
            startStage(baseInitialStage())
            return
        }

        // Repeated hardware DECODE failures must not loop on the same dead decoder.
        // Two shapes of this failure:
        //   - DECODE: ExoPlayer reported a decoder-specific error (videoCodecError /
        //     ERROR_CODE_DECODING_FAILED). Definitive.
        //   - ERROR on a hardware stage that only played BRIEFLY: the same failure
        //     surfaced without a decode-specific code (e.g. the Amlogic MPEG2 hw
        //     decoder rendering a frame then dying ~1.5s later), which would
        //     otherwise reconnect to the same hardware decoder forever.
        // Count these; after MAX_QUICK_DECODE_FAILURES force THIS stream onto
        // software decode (startStage -> startEngine fully releases the old
        // connection first, so single-connection is preserved). The counter is NOT
        // reset by a brief first frame, so a 1.5s-then-fail loop escalates instead
        // of restarting the retry count every cycle.
        val quickHwFailure = isHardwareStage(stage) &&
            (
                effectiveReason == Reason.DECODE ||
                    (effectiveReason == Reason.ERROR && playedBriefly())
                )
        if (quickHwFailure) {
            quickDecodeFailures++
            PlaybackLog.log(
                context, "Controller",
                "hw decode fail #$quickDecodeFailures stage=$stage reason=$effectiveReason"
            )
            if (quickDecodeFailures >= MAX_QUICK_DECODE_FAILURES) {
                // A generic error immediately after a hardware first frame has the
                // same compatibility signature as a decoder failure even when a
                // vendor codec reports no decoder-specific exception.
                val routeReason =
                    if (effectiveReason == Reason.ERROR) Reason.DECODE else effectiveReason
                val fallback = nextStage(stage, routeReason)
                if (fallback != null) {
                    PlaybackLog.log(
                        context,
                        "Controller",
                        "repeated hw decode fail -> fallback $stage to $fallback",
                    )
                    recordStability(
                        "decode_fallback",
                        "warn",
                        "after $quickDecodeFailures hw decode fails ($stage -> $fallback)",
                    )
                    startStage(fallback)
                    return
                }
            }
            // Below the threshold (or software already tried): reconnect the same
            // stage but KEEP the count so the next quick failure escalates.
            engageReconnect()
            return
        }

        // A plain ERROR after the stream already played — or while a reconnect
        // episode is already under way — is a stream DROP, not a startup decode
        // problem: skip the decode-fallback ladder (which would needlessly switch
        // a working stream onto another engine, or fight the reconnect by hopping
        // engines on each failed attempt) and just reconnect the same stage.
        // VIDEO (green) / AUDIO (silent) / SOFTWARE_SLOW (4K) are decode-quality
        // problems the ladder must still handle even after the first frame, since
        // their detection fires only once playback has begun.
        val dropAfterPlay =
            effectiveReason == Reason.ERROR && (playbackConfirmed || reconnecting)
        if (!dropAfterPlay) {
            val next = nextStage(stage, effectiveReason)
            if (next != null) {
                if (
                    effectiveReason == Reason.ERROR ||
                    effectiveReason == Reason.STARTUP
                ) {
                    routeLearningAllowed = false
                }
                PlaybackLog.log(
                    context,
                    "Controller",
                    "fallback $stage --$effectiveReason--> $next",
                )
                recordStability(
                    "fallback",
                    "warn",
                    "$stage --$effectiveReason--> $next",
                )
                startStage(next)
                return
            }
        }

        // No further engine/decode path (or a confirmed-playback drop): hand off
        // to the bounded live-TV reconnect loop.
        engageReconnect()
    }

    private fun isHardwareStage(s: Stage): Boolean = s == Stage.EXO || s == Stage.VLC_HW

    /**
     * True when the current stage rendered a frame but did NOT survive the stable
     * window — the signature of a hardware decoder that plays ~1.5s then fails.
     */
    private fun playedBriefly(): Boolean =
        playbackConfirmed && (SystemClock.elapsedRealtime() - stageStartMs) < STABLE_PLAYBACK_MS

    /**
     * A live stream dropped/ended with no further decode path to try. Re-open the
     * SAME channel on the current stage with a backoff schedule bounded by a total
     * window, until it recovers (onPlaying / onVideoOutput resets the state) or the
     * window elapses (then a fatal error with a manual-retry UI). For non-live this
     * degrades to the previous behaviour: a single fatal error, no reconnect loop.
     */
    private fun engageReconnect() {
        // The reconnect attempt's own startup timeout (armed in startStage) takes
        // over watchdog duty, so drop any current poll/timeout to avoid overlap.
        cancelWatchdog()
        if (suspended) {
            recoveryNeededOnResume = true
            return
        }
        if (!isLive) {
            PlaybackLog.log(context, "Controller", "fatal after $stage (non-live, no reconnect)")
            recordStability("fatal", "fatal", "non-live, no further decode path (stage=$stage)")
            callback.onFatalError()
            return
        }
        // An attempt is already scheduled/in-flight: don't stack a second one
        // (EncounteredError + EndReached can both fire for the same drop).
        if (reconnectPending) return

        val now = SystemClock.elapsedRealtime()
        if (!reconnecting) {
            reconnecting = true
            reconnectWindowStartMs = now
            reconnectAttempt = 0
            // One event per reconnect EPISODE (not per attempt) so a flaky stream
            // doesn't flood the spool with a row for every backoff retry.
            recordStability("reconnect", "warn", "stage=$stage")
        }
        if (now - reconnectWindowStartMs >= RECONNECT_WINDOW_MS) {
            PlaybackLog.log(context, "Controller", "reconnect window elapsed -> fatal")
            recordStability("fatal", "fatal", "reconnect window elapsed after $reconnectAttempt attempts (stage=$stage)")
            resetReconnect()
            callback.onFatalError()
            return
        }

        val delay = RECONNECT_BACKOFF_MS[reconnectAttempt.coerceAtMost(RECONNECT_BACKOFF_MS.size - 1)]
        reconnectAttempt++
        reconnectPending = true
        PlaybackLog.log(context, "Controller", "reconnect attempt $reconnectAttempt in ${delay}ms (stage=$stage)")
        callback.onRetrying(reconnectAttempt)
        reconnectHandler.postDelayed({
            reconnectPending = false
            // Single-connection: startStage -> startEngine fully releases the old
            // engine (stop -> release) before recreating + replaying the same URL.
            currentUrl?.let { startStage(stage) }
        }, delay)
    }

    /**
     * Restart the current channel immediately and reset the reconnect state. Used
     * by the UI's manual "retry" action after the window expired.
     */
    fun retry() {
        val url = currentUrl ?: return
        PlaybackLog.log(context, "Controller", "manual retry")
        // [currentRouteKey] already contains the settings namespace used by route
        // memory. Reusing it as an input would namespace it again on every retry.
        play(url, currentRawRouteKey)
    }

    /** Cancel any in-flight reconnect and clear its schedule/window state. */
    private fun resetReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnecting = false
        reconnectPending = false
        reconnectAttempt = 0
        reconnectWindowStartMs = 0L
    }

    /** The stage to advance to, or null when no more paths are available. */
    private fun nextStage(current: Stage, reason: Reason): Stage? =
        PlaybackRoutingPolicy.nextStage(
            mode = mode,
            decoderMode = decoderMode,
            current = current,
            failure = reason,
            triedStages = triedStages,
        )

    /** Current video stream info for the diagnostics overlay, or null. */
    fun streamInfo(): StreamInfo? = engine?.getStreamInfo()

    fun pause() {
        // A paused VLC/Exo instance can keep an IPTV subscription socket occupied.
        // Stop it when the owner leaves the foreground and rebuild the same stage
        // on resume. Also invalidate a delayed engine/surface creation already in
        // flight; its generation guard then prevents a hidden background player.
        suspended = true
        recoveryNeededOnResume = currentUrl != null
        ++startGeneration
        mainHandler.removeCallbacksAndMessages(null)
        stableHandler.removeCallbacksAndMessages(null)
        resetReconnect()
        cancelWatchdog()
        engine?.stop()
    }

    fun resume() {
        suspended = false
        if (recoveryNeededOnResume) {
            recoveryNeededOnResume = false
            currentUrl?.let { startStage(stage) }
            return
        }
        engine?.resume()
        // Re-baseline + re-arm the stall poll for a stream that was already playing
        // before we backgrounded (the clock jumps on resume, so a fresh baseline
        // avoids a false stall).
        if (playbackConfirmed) armStallWatchdog()
    }

    fun stop() {
        suspended = true
        recoveryNeededOnResume = false
        ++startGeneration
        mainHandler.removeCallbacksAndMessages(null)
        stableHandler.removeCallbacksAndMessages(null)
        resetReconnect()
        cancelWatchdog()
        engine?.stop()
    }

    fun release() {
        // Invalidate any pending engine-create. The swap path trampolines the
        // create through the VLC ops thread, where it can sit for seconds behind
        // a stalled stop — removeCallbacksAndMessages() cannot reach it there, so
        // without this bump a queued create would later pass its generation
        // check, build a ghost engine and play into a destroyed screen.
        suspended = true
        recoveryNeededOnResume = false
        ++startGeneration
        mainHandler.removeCallbacksAndMessages(null)
        stableHandler.removeCallbacksAndMessages(null)
        cancelWatchdog()
        resetReconnect()
        releaseEngine()
    }

    private fun releaseEngine() {
        engine?.release()
        engine = null
        container.removeAllViews()
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    private companion object {
        /**
         * Gap between releasing one engine's SurfaceView and creating the next
         * one's, so the old surface can be destroyed first. Without it, the
         * Amlogic compositor shows a green frame on the freshly-added surface
         * during a fallback (e.g. EXO -> VLC). Short enough to stay snappy.
         */
        private const val ENGINE_SWAP_DELAY_MS = 250L

        /**
         * Backoff between live-TV reconnect attempts. Front-loaded (1s, 2s, 3s,
         * 5s) so the common ~2-3s server restart recovers within the first couple
         * of attempts, then settles at 8s for a longer outage. The last value is
         * reused for any further attempts within the window.
         */
        private val RECONNECT_BACKOFF_MS = longArrayOf(1000L, 2000L, 3000L, 5000L, 8000L)

        /**
         * Total live-TV reconnect window. Attempts keep firing until this elapses
         * from the first attempt, then a fatal error with a manual-retry UI is
         * shown. ~45s comfortably covers a brief server restart while still giving
         * up in reasonable time on a genuinely dead stream.
         */
        private const val RECONNECT_WINDOW_MS = 45_000L

        /**
         * How long a stage must play without failing before it counts as genuinely
         * recovered (reconnect window + quick-decode-failure counter reset). Longer
         * than the ~1.5s the Amlogic MPEG2 hardware decoder survives before failing,
         * so that loop never looks recovered and escalation to software still fires.
         */
        private const val STABLE_PLAYBACK_MS = 10_000L

        /**
         * Quick hardware decode failures tolerated before forcing the stream onto
         * software decode. 2 = one retry on the same hardware decoder (a transient
         * glitch may recover) before giving up on it.
         */
        private const val MAX_QUICK_DECODE_FAILURES = 2

        /** One transient startup error reconnects in place; the second routes. */
        private const val MAX_UNCONFIRMED_START_FAILURES = 2

        /** How often the mid-stream stall watchdog re-reads the playback clock. */
        private const val WATCHDOG_POLL_MS = 3_000L

        /**
         * Minimum forward movement (ms) of the playback clock between polls that
         * counts as real progress. A small epsilon ignores sub-second clock jitter
         * while still flagging a genuinely frozen stream (clock stuck) as a stall.
         */
        private const val PROGRESS_EPSILON_MS = 250L

        /**
         * How long the playback clock may fail to advance before a confirmed live
         * stream is treated as silently stalled (half-open connection, no error
         * event) and force-reconnected. Long enough not to fight a brief network
         * hiccup that libVLC/:http-reconnect can ride out, short enough that the
         * user isn't left staring at a frozen picture.
         */
        private const val STALL_TIMEOUT_MS = 15_000L

        /**
         * How long a (re)start may run without reaching confirmed playback before
         * it's treated as a failed attempt. Covers a connection that opens but
         * delivers no data (so no error fires). Generous so a slow-but-healthy
         * startup (connect + ~3s caching + first frame) never trips it.
         */
        private const val STARTUP_TIMEOUT_MS = 22_000L
    }
}
