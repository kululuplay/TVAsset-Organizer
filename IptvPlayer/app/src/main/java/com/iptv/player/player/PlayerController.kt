/*
 * PlayerController.kt
 * Orchestrates the two engines and their reconnect/fallback policy — one
 * connection at a time (full stop -> release -> restart).
 *
 * AUTO + automatic decoder is reason-aware:
 *   audio/source failure: EXO -> VLC_HW -> VLC_SW
 *   invalid video/decode: EXO -> VLC_HW -> VLC_SW
 *
 * Manual choices remain the preferred path for ordinary errors. A confirmed
 * green/frozen/unsupported decode may use one bounded compatibility fallback so
 * the user is never trapped on an unusable "only" path. Pure decisions live in
 * PlaybackRoutingPolicy and are covered by JVM tests.
 */
package com.iptv.player.player

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import com.iptv.player.cast.ProviderConnectionSafety
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.playback.android.PlaybackQoeRuntime
import com.iptv.player.playback.core.AudioFailureEvidence
import com.iptv.player.playback.core.FailureSignal
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackFailureClassifier
import com.iptv.player.player.PlaybackRoutingPolicy.Failure as Reason
import com.iptv.player.player.PlaybackRoutingPolicy.Stage
import com.iptv.player.util.PlaybackLog
import com.iptv.player.util.PlaybackRouteMemory
import com.iptv.player.util.LiveTransportMemory
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
    // Live TV must not be considered healthy merely because audio/cache reached
    // READY. A verified video frame is the readiness boundary. Radio has no video
    // frame, so it deliberately confirms playback from onPlaying instead.
    private val expectsVideo: Boolean = true,
    // Live TV gets the same-channel auto-reconnect loop (a dropped/ended live
    // stream re-opens itself); set false for any non-live reuse so a stream that
    // legitimately ends is not endlessly re-opened.
    private val isLive: Boolean = true,
    preferredAudioLanguage: String? = null,
    preferredSubtitlePreference: LiveSubtitlePreference = LiveSubtitlePreference.Auto,
    private var callback: Callback
) {

    /** Swap the UI callback (used when a preview controller is adopted by the player). */
    fun setCallback(cb: Callback) { callback = cb }

    /**
     * Re-home the currently running engine onto [newContainer] for a live
     * preview <-> fullscreen hand-off, then restart the stream so the picture
     * reliably reappears on the new surface. Amlogic Exo uses a complete cold
     * engine restart because same-codec stop/prepare can freeze after one frame.
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
        if (
            url != null &&
            !VlcHardwareDevicePolicy.canReuseEngineForStreamChange(
                stage = stage,
                bypassVlcHardware = bypassVlcHardware,
            )
        ) {
            // Moving a running Amlogic Exo Surface and stop/prepare-reusing the
            // same codec has the same one-frame-then-freeze signature as a zap.
            // Retire the complete engine and bind a fresh codec to the new host.
            PlaybackLog.log(
                context,
                "Controller",
                "Amlogic EXO handoff -> cold engine restart",
            )
            videoRebindPending = false
            playbackConfirmed = false
            videoOutputConfirmed = false
            stageStartMs = 0L
            mainHandler.removeCallbacksAndMessages(null)
            progressPolicy.reset()
            resetReconnect()
            cancelWatchdog()
            startStage(stage)
            return
        }
        val generation = ++startGeneration
        // The replay below is a restart boundary for the watchdog: drop the stall
        // poll now — its position baseline belongs to the pre-handoff surface, and
        // the reissued play() resets the engine clock toward 0, which the old
        // baseline would read as "no progress" and false-trigger a reconnect.
        cancelWatchdog()
        videoRebindPending = true
        eng.detachVideo()
        mainHandler.postDelayed({
            if (generation != startGeneration || eng !== engine) {
                if (generation == startGeneration) videoRebindPending = false
                return@postDelayed
            }
            videoRebindPending = false
            eng.attachVideo(newContainer)
            // reset=true: hand-off needs a deterministic decoder/renderer reset
            // onto the re-attached surface (bare re-add can stall video).
            if (url != null) {
                // Re-baseline as a fresh (re)start: clear the prior confirmation +
                // stability evidence so the next real frame runs onPlaybackProgress and
                // re-arms the stall poll against the new clock; the startup timeout
                // covers the replay until that frame arrives.
                playbackConfirmed = false
                videoOutputConfirmed = false
                playbackBuffering = true
                stageStartMs = 0L
                progressPolicy.reset()
                eng.play(url, reset = true)
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
        /** Sustained ready playback with an advancing clock, not merely one frame. */
        fun onStablePlayback() {}
        /** Active backend changed; value is mapped to a closed enum before QoE. */
        fun onEngineChanged(engineName: String) {}
        /** Effective TS/HLS route after channel memory or a bounded fallback. */
        fun onTransportResolved(format: StreamFormat) {}
        /** Structured, URL/message-free failure suitable for bounded QoE. */
        fun onPlaybackFailure(failure: PlaybackFailure) {}
        /**
         * A vendor-native owner cannot prove socket closure. Return true only
         * when the UI launched controlled main-process recovery.
         */
        fun onLocalProcessRecoveryRequired(): Boolean = false
        /** Emitted only after all retries + fallback are exhausted. */
        fun onFatalError()
        fun onRetrying(attempt: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The supplied Xiaomi/Amlogic logs prove that libVLC's direct-rendering
     * MediaCodec surface is corrupt on this decoder family while Media3's
     * hardware SurfaceView path is healthy. Probe decoder names once per
     * controller and treat only VLC hardware as unavailable on those devices.
     */
    private val bypassVlcHardware: Boolean = runCatching {
        val videoDecoderNames = MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .asSequence()
            .filterNot { it.isEncoder }
            .mapNotNull { codec ->
                runCatching {
                    codec.name.takeIf {
                        codec.supportedTypes.any { type ->
                            type.startsWith("video/", ignoreCase = true)
                        }
                    }
                }.getOrNull()
            }
            .toList()
        VlcHardwareDevicePolicy.shouldBypassVlcHardware(videoDecoderNames)
    }.getOrDefault(false)

    private var engine: PlayerEngine? = null
    private var currentUrl: String? = null
    private var currentOriginalUrl: String? = null
    private var stage = Stage.EXO
    private val triedStages = mutableSetOf<Stage>()
    private var videoRebindPending = false
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
    private var currentTransportKey: String? = null
    private var currentTransportFormat: StreamFormat? = null
    private var transportFallbackUsed = false
    private var usingRememberedTransport = false
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
    // A SOFTWARE_SLOW VLC stage must never reconnect to itself indefinitely.
    // One controlled hardware revisit is allowed even when that stage was tried
    // before software; the corrected hardware health gate then owns recovery.
    private var softwareSlowHardwareRetryUsed = false
    // A proven AC-3/Media3 sink stall gets exactly one compatibility rescue per
    // channel. The target VLC path decodes to PCM stereo when passthrough is off.
    private var ac3PcmFallbackUsed = false
    // Generic errors before any playback may be transport noise once, but repeated
    // unconfirmed failures mean the preferred engine cannot start this stream.
    // Preserve this across same-stage reconnects and route after the second one.
    private var unconfirmedStartFailures = 0
    private var adaptiveRebuffers = 0
    private var adaptiveBufferingActive = false
    private val devicePlaybackProfile
        get() = PlaybackQoeRuntime.devicePlaybackProfile()
    private val lowRamDevice: Boolean = runCatching {
        val manager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.isLowRamDevice
    }.getOrDefault(false)

    // Recovery is proven by the same clock samples that detect stalls. A separate
    // delayed "stable" callback could survive EOS and cancel its pending retry.
    private val progressPolicy = LivePlaybackProgressPolicy(
        stablePlaybackMs = STABLE_PLAYBACK_MS,
        stallTimeoutMs = STALL_TIMEOUT_MS,
    )
    private var playbackBuffering = true

    private var audioDelayMs = 0L
    private var subtitleDelayMs = 0L
    private var currentPreferredAudioLanguage = TrackLanguage.normalize(preferredAudioLanguage)
    private var currentSubtitlePreference = preferredSubtitlePreference

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

    /** True when the active engine can apply audio/subtitle delay (libVLC). */
    val supportsDelay: Boolean get() = engine?.supportsDelay == true

    val currentAudioDelayMs: Long get() = audioDelayMs
    val currentSubtitleDelayMs: Long get() = subtitleDelayMs
    val subtitlePreference: LiveSubtitlePreference get() = currentSubtitlePreference
    val resolvedTransportFormat: StreamFormat? get() = currentTransportFormat

    fun setAudioDelay(ms: Long) {
        audioDelayMs = ms
        engine?.setAudioDelayMs(ms)
    }

    fun setSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms
        engine?.setSubtitleDelayMs(ms)
    }

    fun audioTracks(): List<PlayerTrack> = engine?.audioTracks().orEmpty()

    fun subtitleTracks(): List<PlayerTrack> = engine?.subtitleTracks().orEmpty()

    fun selectAudioTrack(id: String): Boolean {
        val language = audioTracks().firstOrNull { it.id == id }?.language
        val selected = engine?.selectAudioTrack(id) == true
        if (selected && language != null) {
            currentPreferredAudioLanguage = language
            engine?.setPreferredTrackLanguages(
                currentPreferredAudioLanguage,
                currentSubtitlePreference.languageOrNull(),
            )
            engine?.setSubtitlePreference(currentSubtitlePreference)
        }
        return selected
    }

    fun selectSubtitleTrack(id: String?): Boolean {
        val language = id?.let { selectedId ->
            subtitleTracks().firstOrNull { it.id == selectedId }?.language
        }
        val selected = engine?.selectSubtitleTrack(id) == true
        if (selected) {
            currentSubtitlePreference = when {
                id == null -> LiveSubtitlePreference.Off
                language != null ->
                    LiveSubtitlePreference.Language.from(language)
                        ?: LiveSubtitlePreference.Auto
                else -> currentSubtitlePreference
            }
            engine?.setSubtitlePreference(currentSubtitlePreference)
        }
        return selected
    }

    fun selectSubtitleAuto(): Boolean {
        currentSubtitlePreference = LiveSubtitlePreference.Auto
        return engine?.setSubtitlePreference(currentSubtitlePreference) == true
    }

    fun play(
        url: String,
        routeKey: String? = null,
        transportKey: String? = null,
    ) {
        suspended = false
        recoveryNeededOnResume = false
        // A zap can arrive during preview -> fullscreen's delayed surface move.
        // The callback is about to be cleared, so first finish the host move to
        // the controller's already-updated container. Otherwise playback starts
        // on a parentless or no-longer-visible surface.
        if (videoRebindPending) {
            videoRebindPending = false
            engine?.attachVideo(container)
        }
        // Cancel any pending retry from a previous stream so zapping is clean.
        mainHandler.removeCallbacksAndMessages(null)
        // A new channel cancels any in-flight reconnect so it can't fire against
        // the stale stream or open a second connection.
        resetReconnect()
        // New channel: drop stability evidence and the quick-decode-failure
        // count from the previous stream so they can't bleed into this one.
        progressPolicy.reset()
        playbackBuffering = true
        quickDecodeFailures = 0
        softwareSlowHardwareRetryUsed = false
        ac3PcmFallbackUsed = false
        unconfirmedStartFailures = 0
        adaptiveRebuffers = 0
        adaptiveBufferingActive = false
        // Tear down the previous channel's stall/startup watchdog; the (re)start
        // path below re-arms it for this channel.
        cancelWatchdog()
        currentOriginalUrl = url
        currentTransportKey = transportKey
        transportFallbackUsed = false
        val configuredFormat = LiveStreamUrl.detectFormat(url)
        val rememberedTransport =
            if (transportKey != null && configuredFormat != null) {
                LiveTransportMemory.bestFormat(transportKey)
            } else {
                null
            }
        currentTransportFormat = rememberedTransport ?: configuredFormat
        currentUrl = currentTransportFormat?.let { LiveStreamUrl.applyFormat(url, it) } ?: url
        currentTransportFormat?.let(callback::onTransportResolved)
        usingRememberedTransport =
            rememberedTransport != null && rememberedTransport != configuredFormat
        // A route learned under VLC/Auto must not override a later Exo/Hardware
        // preference (or vice versa). The source/policy fingerprint is already in
        // routeKey; namespace it by the active settings pair as well.
        currentRawRouteKey = routeKey
        val effectiveRouteKey = currentTransportFormat
            ?.let { LiveStreamUrl.routeKeyWithFormat(routeKey, it) }
            ?: routeKey
        currentRouteKey = effectiveRouteKey?.let { "${mode.name}|${decoderMode.name}|$it" }
        playbackConfirmed = false
        videoOutputConfirmed = false
        memoryIgnoredThisPlay = false
        routeLearningAllowed = true
        // Tier 2 self-healing: prefer the stage this channel last proved STABLE on
        // (route memory) over the cold base ladder, so a channel that always needs,
        // say, software decode starts there instead of greening on hardware first.
        val preferredBase = PlaybackRoutingPolicy.initialStage(mode, decoderMode)
        val base = baseInitialStage()
        if (base != preferredBase) {
            PlaybackLog.log(
                context,
                "Controller",
                "VLC hardware bypassed for Amlogic surface compatibility -> $base",
            )
        }
        val remembered = rememberedStage()
        val initial = remembered ?: base
        // Only treat it as a "remembered route" (eligible for the distrust-on-fail
        // restart) when memory actually changes the starting stage.
        usingRememberedRoute = remembered != null && remembered != base

        // Fast zap: on device/engine combinations proven safe for reuse, keep the
        // current engine alive and swap only the stream. Amlogic Exo deliberately
        // does not qualify: real-device evidence shows its stop/prepare codec can
        // render one new frame, then freeze while audio keeps advancing.
        // Only the steady state qualifies: if the current stream had already
        // fallen back to a different stage, drop through to a clean restart so the
        // new channel still gets the full hardware-first fallback ladder.
        val reusable = engine
        val canReuse =
            VlcHardwareDevicePolicy.canReuseEngineForStreamChange(
                stage = initial,
                bypassVlcHardware = bypassVlcHardware,
            )
        if (reusable != null && stage == initial && canReuse) {
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
            // Still far cheaper than a full engine release+recreate: no new
            // LibVLC/layout and no ENGINE_SWAP surface-handoff gap. (VLC creates a
            // fresh native MediaPlayer/Media per channel, so reset is a no-op there.)
            PlaybackLog.log(context, "Controller", "fast-zap reuse stage=$initial reset")
            // Use the controller-resolved URL, not the caller's original URL:
            // transport memory may have selected the alternate TS/HLS container.
            // Replaying [url] here silently discarded that decision only on the
            // fast-zap path and also desynchronised route/transport memory.
            reusable.play(currentUrl ?: url, reset = true)
            return
        }
        if (reusable != null && stage == initial && !canReuse) {
            PlaybackLog.log(
                context,
                "Controller",
                "Amlogic EXO zap -> cold engine restart",
            )
        }

        triedStages.clear()
        stage = initial
        PlaybackLog.log(
            context, "Controller",
            "play mode=$mode decoder=$decoderMode start=$stage" +
                (if (usingRememberedRoute) " (route memory)" else "") +
                (if (usingRememberedTransport) {
                    " transport=$currentTransportFormat (memory)"
                } else {
                    ""
                })
        )
        startStage(stage)
    }

    /**
     * First stage to try from a COLD start, given the engine choice and decoder
     * strategy (no route memory). [play] prefers [rememberedStage] over this.
     */
    private fun baseInitialStage(): Stage =
        VlcHardwareDevicePolicy.compatibleInitialStage(
            preferred = PlaybackRoutingPolicy.initialStage(mode, decoderMode),
            bypassVlcHardware = bypassVlcHardware,
        )

    /**
     * The stage this channel last proved STABLE on, or null when route memory does
     * not apply (ineligible mode, no key, no/expired entry, distrusted this play).
     * A purely in-memory read — never touches disk on this hot path.
     */
    private fun rememberedStage(): Stage? {
        if (memoryIgnoredThisPlay || !routeMemoryEligible) return null
        val name = PlaybackRouteMemory.bestStage(currentRouteKey) ?: return null
        val remembered = runCatching { Stage.valueOf(name) }.getOrNull() ?: return null
        if (bypassVlcHardware && remembered == Stage.VLC_HW) return null
        // A last-resort Exo rescue may save the current VLC-preferred session,
        // but the next channel visit must still begin on the engine the user
        // explicitly selected.
        if (mode == PlayerMode.VLC && remembered == Stage.EXO) return null
        return remembered
    }

    private fun startStage(target: Stage) {
        callback.onPlaybackRestarting()
        // Retire the prior stage's startup/stall watchdog now. The replacement
        // backend re-arms a fresh startup budget through onPlaybackSubmitted()
        // only after its real decoder/native start path is reached.
        cancelWatchdog()
        // A GENUINE stage change (ladder move / escalation) starts a fresh decoder,
        // so reset the quick-decode-failure count. A reconnect replay of the SAME
        // stage keeps it, so a 1.5s-then-fail loop still escalates after a couple.
        if (target != stage) {
            quickDecodeFailures = 0
            unconfirmedStartFailures = 0
        }
        triedStages.add(target)
        stage = target
        // Fresh (re)start: the new attempt must prove its own frame and progress.
        playbackConfirmed = false
        videoOutputConfirmed = false
        progressPolicy.reset()
        playbackBuffering = true
        val useVlc = target != Stage.EXO
        val forceSoftware = target == Stage.VLC_SW
        startEngine(useVlc, forceSoftware)
    }

    private fun startEngine(useVlc: Boolean, forceSoftware: Boolean) {
        videoRebindPending = false
        // True when we are SWAPPING engines (fallback/stage change), as opposed to
        // the very first start where the container is already empty.
        val swapping = engine != null
        // Invalidate any create that is still pending from an earlier call. A stale
        // failure callback from the just-released engine can re-enter this method
        // during the swap gap (that path does NOT clear the handler), so a token
        // check stops a second, overlapping engine from being created.
        val generation = ++startGeneration
        releaseEngine()

        var providerDrainAttempted = false
        lateinit var create: Runnable
        fun failClosedForProviderOwnership() {
            PlaybackLog.log(
                context,
                "Controller",
                "provider connection ownership uncertain -> fail closed",
            )
            callback.onPlaybackFailure(
                PlaybackFailure(
                    category = PlaybackFailure.Category.RESOURCE,
                    code = PlaybackFailure.Code.RESOURCE_EXHAUSTED,
                    phase = PlaybackFailure.Phase.SHUTDOWN,
                    component = PlaybackFailure.Component.TRANSPORT,
                    retryAdvice = PlaybackFailure.RetryAdvice.DO_NOT_RETRY,
                ),
            )
            callback.onFatalError()
        }
        create = Runnable {
            if (generation != startGeneration) return@Runnable
            var safety = ProviderConnectionSafety.snapshot()
            if (!safety.newConnectionAllowed) {
                if (safety.remoteUncertain) {
                    failClosedForProviderOwnership()
                    return@Runnable
                }
                if (
                    safety.localPendingCount > 0 &&
                    !safety.localProcessRecoveryRequired &&
                    !providerDrainAttempted
                ) {
                    // A prior Activity may still be completing an ordinary VLC
                    // release. Wait behind the shared FIFO instead of presenting
                    // an error or opening an overlapping Exo connection.
                    providerDrainAttempted = true
                    VlcOps.post { mainHandler.post(create) }
                    return@Runnable
                }
                if (
                    safety.localPendingCount > 0 &&
                    !safety.localProcessRecoveryRequired
                ) {
                    // The queue drained but the exact token did not: its old JNI
                    // worker is no longer a viable proof path.
                    ProviderConnectionSafety.requireProcessRecoveryForPendingLocalStops()
                    safety = ProviderConnectionSafety.snapshot()
                }
                if (
                    safety.canRecoverLocalProcess &&
                    callback.onLocalProcessRecoveryRequired()
                ) {
                    return@Runnable
                }
                failClosedForProviderOwnership()
                return@Runnable
            }
            var candidate: PlayerEngine? = null
            try {
                val newEngine: PlayerEngine =
                    if (useVlc) {
                        val effectiveBuffer = AdaptiveBufferPolicy.resolve(
                            configured = bufferMode,
                            lowRamDevice = lowRamDevice || devicePlaybackProfile.compatibilityMode,
                            recentRebuffers = adaptiveRebuffers,
                        )
                        VlcPlayerEngine(
                            context,
                            forceSoftware,
                            allowPassthrough,
                            effectiveBuffer.networkCachingMs,
                        )
                    } else {
                        val effectiveBuffer = AdaptiveBufferPolicy.resolve(
                            configured = bufferMode,
                            lowRamDevice = lowRamDevice || devicePlaybackProfile.compatibilityMode,
                            recentRebuffers = adaptiveRebuffers,
                        )
                        ExoPlayerEngine(
                            context = context,
                            allowPassthrough = allowPassthrough,
                            bufferMode = if (bufferMode == BufferMode.ADAPTIVE) {
                                bufferMode
                            } else {
                                effectiveBuffer
                            },
                            initialRebuffers = adaptiveRebuffers,
                            constrainedDevice = lowRamDevice || devicePlaybackProfile.compatibilityMode,
                            expectsVideo = expectsVideo,
                        )
                    }
                candidate = newEngine
                newEngine.bind(container)
                if (generation != startGeneration) {
                    newEngine.release()
                    return@Runnable
                }
                // Capture the concrete engine instance. Native VLC callbacks can
                // arrive after release; the identity gate in engineListener keeps
                // them from advancing the newly-created route.
                newEngine.setListener(engineListener(newEngine))
                newEngine.setAudioDelayMs(audioDelayMs)
                newEngine.setSubtitleDelayMs(subtitleDelayMs)
                newEngine.setPreferredTrackLanguages(
                    currentPreferredAudioLanguage,
                    currentSubtitlePreference.languageOrNull(),
                )
                newEngine.setSubtitlePreference(currentSubtitlePreference)
                engine = newEngine
                callback.onEngineChanged(newEngine.engineName)
                currentUrl?.let { newEngine.play(it) }
            } catch (error: Throwable) {
                if (engine === candidate) engine = null
                runCatching { candidate?.release() }
                PlaybackLog.log(
                    context,
                    "Controller",
                    "engine init failed stage=$stage: ${error.javaClass.simpleName}",
                )
                // Keep engine construction failures inside the same bounded
                // reason-aware ladder instead of crashing the TV UI.
                mainHandler.post {
                    if (generation == startGeneration && engine == null) {
                        handleEngineFailure(Reason.STARTUP)
                    }
                }
            }
        }

        if (swapping) {
            // SurfaceView handoff fix. releaseEngine() delegates view retirement
            // to the backend so native decoder shutdown happens before its
            // SurfaceView is removed. A SurfaceView's underlying surface is then
            // torn down ASYNCHRONOUSLY on the render thread; adding the next
            // SurfaceView in the same synchronous pass can still race that final
            // compositor teardown and produce a green first frame on Amlogic.
            // Deferring the new engine gives that teardown a bounded gap.
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
        override fun onPlaybackSubmitted() = post {
            if (source !== engine || suspended) return@post
            if (playbackConfirmed) return@post
            // A coalesced VLC zap may wait behind a bounded native cleanup before
            // this callback. Start the budget only now, when this URL has reached
            // the actual decoder/native start path.
            armStartupTimeout()
        }

        override fun onBuffering() = post {
            if (source !== engine || suspended) return@post
            playbackBuffering = true
            progressPolicy.onBuffering()
            if (
                bufferMode == BufferMode.ADAPTIVE &&
                playbackConfirmed &&
                !adaptiveBufferingActive
            ) {
                adaptiveRebuffers = (adaptiveRebuffers + 1).coerceAtMost(6)
                adaptiveBufferingActive = true
            }
            callback.onBuffering()
        }

        override fun onPlaying() = post {
            if (source !== engine || suspended) return@post
            playbackBuffering = false
            adaptiveBufferingActive = false
            callback.onPlaying(source.engineName)
            if (!expectsVideo) onPlaybackProgress()
        }

        override fun onVideoOutput() = post {
            if (source !== engine || suspended) return@post
            adaptiveBufferingActive = false
            val firstVideoOutput = !videoOutputConfirmed
            videoOutputConfirmed = true
            unconfirmedStartFailures = 0
            callback.onVideoResumed()
            if (firstVideoOutput && playbackConfirmed) {
                // Playing may precede the first real picture by several seconds.
                // Measure route stability from this verified frame, not from the
                // earlier audio/cache-ready event.
                stageStartMs = SystemClock.elapsedRealtime()
                armStallWatchdog()
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
                notifyPlaybackFailure(
                    PlaybackFailureClassifier.classify(
                        FailureSignal.Network(FailureSignal.NetworkKind.END_OF_STREAM),
                        PlaybackFailure.Phase.PLAYBACK,
                    ),
                )
                PlaybackLog.log(context, "Controller", "live stream ended -> reconnect")
                engageReconnect()
            }
        }

        override fun onError(message: String?) =
            post { if (source === engine) handleEngineFailure(Reason.ERROR) }

        override fun onStartupFailure(message: String?) =
            post { if (source === engine) handleEngineFailure(Reason.STARTUP) }

        override fun onSourceFailure(message: String?, httpStatus: Int?) = post {
            if (source !== engine) return@post
            if (suspended) {
                recoveryNeededOnResume = true
                return@post
            }
            val manifestFailure = message?.contains("manifest", ignoreCase = true) == true
            notifyPlaybackFailure(
                when {
                    httpStatus != null && httpStatus in 400..599 ->
                        PlaybackFailureClassifier.classify(
                            FailureSignal.Http(httpStatus),
                            PlaybackFailure.Phase.OPEN_SOURCE,
                        )
                    manifestFailure -> PlaybackFailureClassifier.classify(
                        FailureSignal.Source(FailureSignal.SourceKind.MALFORMED),
                        PlaybackFailure.Phase.OPEN_SOURCE,
                    )
                    else -> PlaybackFailureClassifier.classify(
                        FailureSignal.Network(FailureSignal.NetworkKind.CONNECT),
                        PlaybackFailure.Phase.OPEN_SOURCE,
                    )
                },
            )
            if (
                !playbackConfirmed &&
                tryTransportFallback(
                    failure = if (manifestFailure) {
                        LiveTransportPolicy.Failure.MANIFEST
                    } else {
                        LiveTransportPolicy.Failure.SOURCE_STARTUP
                    },
                    httpStatus = httpStatus,
                )
            ) {
                return@post
            }
            handleEngineFailure(Reason.ERROR, reportFailure = false)
        }

        override fun onDecodeError(message: String?) =
            post { if (source === engine) handleEngineFailure(Reason.DECODE) }

        override fun onAudioUnavailable() =
            post { if (source === engine) handleEngineFailure(Reason.AUDIO) }

        override fun onAudioStall(evidence: AudioFailureEvidence) = post {
            if (source === engine) handleAudioStall(evidence)
        }

        override fun onVideoInvalid() =
            post { if (source === engine) handleEngineFailure(Reason.VIDEO) }

        override fun onSoftwareTooSlow() =
            post { if (source === engine) handleEngineFailure(Reason.SOFTWARE_SLOW) }
    }

    private fun handleEngineFailure(
        reason: Reason,
        reportFailure: Boolean = true,
    ) {
        if (suspended) {
            recoveryNeededOnResume = true
            return
        }
        if (reportFailure) notifyPlaybackFailure(failureFor(reason))
        handleFailure(reason)
    }

    private fun notifyPlaybackFailure(failure: PlaybackFailure) {
        callback.onPlaybackFailure(failure)
    }

    /**
     * Route a proven Media3 AC-3 sink/clock stall once to VLC's PCM-stereo path.
     * startStage -> startEngine -> releaseEngine retires the old provider socket
     * before creating VLC, preserving the single-connection subscription limit.
     */
    private fun handleAudioStall(evidence: AudioFailureEvidence) {
        if (suspended) {
            recoveryNeededOnResume = true
            return
        }
        notifyPlaybackFailure(
            PlaybackFailureClassifier.classify(
                FailureSignal.AudioStall(evidence),
                PlaybackFailure.Phase.PLAYBACK,
            ),
        )
        PlaybackRouteMemory.forget(currentRouteKey, stage.name)

        val target = LiveAudioStallPolicy.fallbackStage(
            current = stage,
            outputMode = evidence.outputMode,
            alreadyUsed = ac3PcmFallbackUsed,
            triedStages = triedStages,
            bypassVlcHardware = bypassVlcHardware,
        )
        if (target == null) {
            PlaybackLog.log(
                context,
                "Controller",
                "AC3 PCM rescue unavailable/already used -> bounded fatal",
            )
            recordStability("audio_stall", "fatal", "bounded PCM rescue exhausted")
            resetReconnect()
            cancelWatchdog()
            callback.onFatalError()
            return
        }

        ac3PcmFallbackUsed = true
        resetReconnect()
        cancelWatchdog()
        PlaybackLog.log(
            context,
            "Controller",
            "AC3 audio stall -> one bounded ${target.name} PCM stereo rescue",
        )
        recordStability(
            "audio_fallback",
            "warn",
            "EXO AC3 ${evidence.sinkEvent.name} -> ${target.name} PCM",
        )
        startStage(target)
    }

    /** Translate engine/routing evidence without retaining native text or URLs. */
    private fun failureFor(reason: Reason): PlaybackFailure {
        val phase = if (playbackConfirmed) {
            PlaybackFailure.Phase.PLAYBACK
        } else {
            PlaybackFailure.Phase.STARTUP
        }
        val signal: FailureSignal = when (reason) {
            Reason.ERROR -> if (playbackConfirmed) {
                FailureSignal.Network(FailureSignal.NetworkKind.RESET)
            } else {
                FailureSignal.Network(FailureSignal.NetworkKind.CONNECT)
            }
            Reason.STARTUP -> FailureSignal.Timeout(FailureSignal.TimeoutKind.STARTUP)
            Reason.AUDIO -> FailureSignal.Decoder(
                FailureSignal.DecoderKind.INIT,
                PlaybackFailure.Component.AUDIO,
            )
            Reason.VIDEO -> FailureSignal.Output(PlaybackFailure.Component.VIDEO)
            Reason.SOFTWARE_SLOW ->
                FailureSignal.Resource(FailureSignal.ResourceKind.EXHAUSTED)
            Reason.DECODE -> FailureSignal.Decoder(
                FailureSignal.DecoderKind.RUNTIME,
                PlaybackFailure.Component.VIDEO,
            )
        }
        return PlaybackFailureClassifier.classify(signal, phase)
    }

    /** Try the alternate container once without ever changing host/scheme/port. */
    private fun tryTransportFallback(
        failure: LiveTransportPolicy.Failure,
        httpStatus: Int? = null,
    ): Boolean {
        val url = currentUrl ?: return false
        val alternative = LiveTransportPolicy.alternate(
            currentUrl = url,
            currentFormat = currentTransportFormat,
            failure = failure,
            httpStatus = httpStatus,
            alreadyTried = transportFallbackUsed,
        ) ?: return false

        if (usingRememberedTransport) {
            LiveTransportMemory.forget(currentTransportKey, currentTransportFormat)
        }
        transportFallbackUsed = true
        usingRememberedTransport = false
        currentUrl = alternative.url
        currentTransportFormat = alternative.format
        callback.onTransportResolved(alternative.format)
        val effectiveRouteKey = LiveStreamUrl.routeKeyWithFormat(
            currentRawRouteKey,
            alternative.format,
        )
        currentRouteKey = effectiveRouteKey?.let { "${mode.name}|${decoderMode.name}|$it" }
        unconfirmedStartFailures = 0
        resetReconnect()
        // A different container has a different demux/startup profile. Give it a
        // fresh, bounded engine ladder instead of inheriting "already tried"
        // stages from the failed container. Route memory is also namespaced by
        // format, so only a winner learned for this exact alternative may steer
        // the new attempt.
        triedStages.clear()
        memoryIgnoredThisPlay = false
        usingRememberedRoute = false
        val base = baseInitialStage()
        val remembered = rememberedStage()
        val restartStage = remembered ?: base
        usingRememberedRoute = remembered != null && remembered != base
        PlaybackLog.log(
            context,
            "Controller",
            "source startup -> one transport retry ${alternative.format} stage=$restartStage",
        )
        recordStability(
            "transport_fallback",
            "warn",
            "to=${alternative.format} status=${httpStatus ?: "unknown"}",
        )
        startStage(restartStage)
        return true
    }

    /**
     * Called on the first confirmed playback (a verified frame for TV, onPlaying
     * for radio) of the current stage. We begin observing clock progress
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
        // First confirmed progress: swap the startup timeout for the mid-stream
        // stall poll so a later silent freeze (no EndReached/error) still recovers.
        armStallWatchdog()
    }

    /** Called only after uninterrupted ready samples prove sustained progress. */
    private fun onStablePlayback() {
        val stableStage = stage
        val stableKey = currentRouteKey
        if (suspended || !playbackConfirmed || playbackBuffering) return
        PlaybackLog.log(context, "Controller", "sustained playback progress -> recovered")
        quickDecodeFailures = 0
        unconfirmedStartFailures = 0
        resetReconnect()
        // Audio-only streams skip decoder memory; TV requires its verified frame.
        val stageHonorsExplicitEngine =
            mode != PlayerMode.VLC || stableStage != Stage.EXO
        if (
            routeMemoryEligible &&
            routeLearningAllowed &&
            videoOutputConfirmed &&
            stageHonorsExplicitEngine
        ) {
            PlaybackRouteMemory.markStable(stableKey, stableStage.name)
        } else if (routeMemoryEligible) {
            PlaybackLog.log(
                context,
                "Controller",
                "route not learned (realVideo=$videoOutputConfirmed, " +
                    "eligibleFailure=$routeLearningAllowed, " +
                    "honorsEngine=$stageHonorsExplicitEngine)",
            )
        }
        if (isLive && (!expectsVideo || videoOutputConfirmed)) {
            LiveTransportMemory.markStable(currentTransportKey, currentTransportFormat)
            usingRememberedTransport = false
        }
        usingRememberedRoute = false
        callback.onStablePlayback()
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
            notifyPlaybackFailure(
                PlaybackFailureClassifier.classify(
                    FailureSignal.Timeout(FailureSignal.TimeoutKind.STARTUP),
                    PlaybackFailure.Phase.STARTUP,
                ),
            )
            if (
                engine?.supportsPreciseSourceErrors == true &&
                tryTransportFallback(
                    LiveTransportPolicy.Failure.SOURCE_STARTUP,
                )
            ) {
                return@postDelayed
            }
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
        progressPolicy.start(
            nowMs = SystemClock.elapsedRealtime(),
            positionMs = engine?.playbackPositionMs() ?: -1L,
        )
        scheduleStallPoll(watchdogGen)
    }

    private fun scheduleStallPoll(gen: Int) {
        watchdogHandler.postDelayed({
            if (gen != watchdogGen) return@postDelayed
            // A confirmed reconnect still needs stall monitoring before it can
            // earn stability. Only a scheduled replacement has another owner.
            if (reconnectPending || engine == null) {
                scheduleStallPoll(gen)
                return@postDelayed
            }
            val pos = engine?.playbackPositionMs() ?: -1L
            val now = SystemClock.elapsedRealtime()
            val decision = progressPolicy.sample(
                nowMs = now,
                positionMs = pos,
                buffering = playbackBuffering,
            )
            if (decision == LivePlaybackProgressPolicy.Decision.STABLE) {
                onStablePlayback()
            }
            if (decision == LivePlaybackProgressPolicy.Decision.STALLED) {
                PlaybackLog.log(context, "Controller", "live stall (no progress ${STALL_TIMEOUT_MS}ms) -> reconnect")
                recordStability("stall", "warn")
                notifyPlaybackFailure(
                    PlaybackFailureClassifier.classify(
                        FailureSignal.Timeout(FailureSignal.TimeoutKind.STALL),
                        PlaybackFailure.Phase.PLAYBACK,
                    ),
                )
                cancelWatchdog()
                if (tryTransportFallback(LiveTransportPolicy.Failure.PLAYBACK_STALL)) {
                    return@postDelayed
                }
                engageReconnect()
                return@postDelayed
            }
            scheduleStallPoll(gen)
        }, WATCHDOG_POLL_MS)
    }

    private fun handleFailure(reason: Reason) {
        // Never carry readiness/progress evidence into a pending recovery.
        progressPolicy.reset()

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

        // Quality/decode evidence is stronger than the old elapsed-time-only
        // stable mark. If a route starts dropping frames, shows invalid output,
        // loses audio or proves too slow after it was learned, remove it
        // immediately so the next visit cannot start on that degraded path.
        if (
            effectiveReason == Reason.VIDEO ||
            effectiveReason == Reason.DECODE ||
            effectiveReason == Reason.AUDIO ||
            effectiveReason == Reason.SOFTWARE_SLOW
        ) {
            PlaybackRouteMemory.forget(currentRouteKey, stage.name)
        }

        // A single pre-playback network/source error is commonly a transient CDN
        // or socket reset, not decoder evidence. Retry the preferred stage once
        // before changing engine/decoder or distrusting a remembered route. The
        // second unconfirmed error is promoted to STARTUP above and may then walk
        // the bounded compatibility ladder.
        if (
            reason == Reason.ERROR &&
            !playbackConfirmed &&
            effectiveReason == Reason.ERROR
        ) {
            PlaybackLog.log(
                context,
                "Controller",
                "transient unconfirmed source error -> retry $stage once",
            )
            engageReconnect()
            return
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

        // Every Amlogic EXO stream boundary already owns a fresh engine/codec.
        // If that fresh decoder produces one verified frame and then stalls,
        // repeating the identical codec only delays the bounded VLC fallback.
        // Let the compatibility ladder advance immediately.

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
                    effectiveReason == Reason.SOFTWARE_SLOW &&
                    stage == Stage.VLC_SW
                ) {
                    softwareSlowHardwareRetryUsed = true
                }
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

        val lastChanceHardware =
            VlcHardwareDevicePolicy.lastChanceAfterSoftwareOverload(
                current = stage,
                failure = effectiveReason,
                alreadyUsed = softwareSlowHardwareRetryUsed,
                bypassVlcHardware = bypassVlcHardware,
            )
        if (lastChanceHardware != null) {
            softwareSlowHardwareRetryUsed = true
            PlaybackLog.log(
                context,
                "Controller",
                "software overload -> one bounded hardware revisit $lastChanceHardware",
            )
            recordStability(
                "fallback",
                "warn",
                "VLC_SW --SOFTWARE_SLOW--> $lastChanceHardware (bounded revisit)",
            )
            startStage(lastChanceHardware)
            return
        }

        // Reopening the exact VLC_SW instance after it proved too slow recreates
        // the 4K CPU-overload loop from the device log. All viable hardware paths
        // have now been exhausted, so surface a bounded failure instead.
        if (effectiveReason == Reason.SOFTWARE_SLOW && stage == Stage.VLC_SW) {
            PlaybackLog.log(
                context,
                "Controller",
                "software decode remains too slow after hardware recovery -> fatal",
            )
            recordStability(
                "fatal",
                "fatal",
                "software decode too slow and no viable hardware route",
            )
            resetReconnect()
            cancelWatchdog()
            callback.onFatalError()
            return
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
     * window, until sustained playback recovers or the
     * window elapses (then a fatal error with a manual-retry UI). For non-live this
     * degrades to the previous behaviour: a single fatal error, no reconnect loop.
     */
    private fun engageReconnect() {
        // EOS comes here directly, without handleFailure. Retire its stability
        // evidence before scheduling a replacement so no success callback can
        // erase the pending retry near the stability deadline.
        progressPolicy.reset()
        // The reconnect attempt's own startup timeout (armed once its replacement
        // engine actually receives play()) takes over watchdog duty, so drop any
        // current poll/timeout to avoid overlap.
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
        play(
            currentOriginalUrl ?: url,
            currentRawRouteKey,
            currentTransportKey,
        )
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
    private fun nextStage(current: Stage, reason: Reason): Stage? {
        val streamInfo = engine?.getStreamInfo()
        val softwareCodecUnavailable =
            !devicePlaybackProfile.allowSoftwareHevcRescue &&
                isHevcCodec(streamInfo?.codec)
        val unavailableAwareTriedStages =
            triedStages + VlcHardwareDevicePolicy.unavailableStages(
                bypassVlcHardware = bypassVlcHardware,
                width = streamInfo?.width ?: 0,
                height = streamInfo?.height ?: 0,
            ) + if (softwareCodecUnavailable) setOf(Stage.VLC_SW) else emptySet()
        return PlaybackRoutingPolicy.nextStage(
            mode = mode,
            decoderMode = decoderMode,
            current = current,
            failure = reason,
            triedStages = unavailableAwareTriedStages,
        ) ?: VlcHardwareDevicePolicy.fallbackAfterHardwareSubstitution(
            mode = mode,
            current = current,
            failure = reason,
            triedStages = unavailableAwareTriedStages,
            bypassVlcHardware = bypassVlcHardware,
        )
    }

    /** Current video stream info for the diagnostics overlay, or null. */
    fun streamInfo(): StreamInfo? = engine?.getStreamInfo()

    private fun isHevcCodec(codec: String?): Boolean {
        val normalized = codec?.trim()?.lowercase().orEmpty()
        return normalized.contains("hevc") ||
            normalized.contains("h265") ||
            normalized.contains("h.265") ||
            normalized.contains("hev1") ||
            normalized.contains("hvc1")
    }

    fun pause() {
        quiesce { }
    }

    /** Close the active provider socket and notify a Cast preflight on completion. */
    fun quiesce(onStopped: (Boolean) -> Unit) {
        // A paused VLC/Exo instance can keep an IPTV subscription socket occupied.
        // Stop it when the owner leaves the foreground and rebuild the same stage
        // on resume. Also invalidate a delayed engine/surface creation already in
        // flight; its generation guard then prevents a hidden background player.
        suspended = true
        recoveryNeededOnResume = currentUrl != null
        ++startGeneration
        mainHandler.removeCallbacksAndMessages(null)
        progressPolicy.reset()
        resetReconnect()
        cancelWatchdog()
        val target = engine
        if (target == null) {
            onStopped(true)
            return
        }
        target.stopAndThen { stopped ->
            post {
                onStopped(stopped && suspended && engine === target)
            }
        }
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
        progressPolicy.reset()
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
        progressPolicy.reset()
        cancelWatchdog()
        resetReconnect()
        releaseEngine()
    }

    private fun releaseEngine() {
        videoRebindPending = false
        engine?.release()
        engine = null
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    private companion object {
        /**
         * Gap between asking an engine to retire and creating the next one. Each
         * backend removes its SurfaceView only after native decoder shutdown;
         * this extra compositor gap prevents the freshly-added surface from
         * inheriting a green frame on Amlogic. Short enough to stay snappy.
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
