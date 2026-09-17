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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.view.ViewGroup
import com.iptv.player.cast.ProviderConnectionSafety
import com.iptv.player.BuildConfig
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.playback.android.DisplayModeSwitcher
import com.iptv.player.playback.android.PlaybackQoeRuntime
import com.iptv.player.playback.core.AudioFailureEvidence
import com.iptv.player.playback.core.FailureSignal
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackFailureClassifier
import com.iptv.player.player.PlaybackRoutingPolicy.Failure as Reason
import com.iptv.player.player.PlaybackRoutingPolicy.Stage
import com.iptv.player.util.PlaybackLog
import com.iptv.player.util.PlaybackRemotePolicy
import com.iptv.player.util.PlaybackRouteMemory
import com.iptv.player.util.LiveTransportMemory
import com.iptv.player.util.StabilityTelemetry

class PlayerController(
    // The callback is swapped via setCallback() when a preview controller is
    // adopted by the fullscreen player. (The old rebind() re-homing path was
    // removed: it had no callers and dropped engine events during its 250 ms gap.)
    private val context: Context,
    private val container: ViewGroup,
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
     * Automatic frame-rate matching is only sensible for fullscreen playback: a
     * display-mode switch blanks the whole screen, which is unacceptable behind
     * an inline preview thumbnail. Defaults to true; the Home preview owner must
     * set it false (and back to true when the controller is adopted fullscreen).
     * Turning it off undoes a switch already applied.
     */
    fun setDisplayModeSwitchingAllowed(allowed: Boolean) {
        val wasAllowed = displayModeSwitchingAllowed
        displayModeSwitchingAllowed = allowed
        displayModeSwitcher?.allowed = allowed
        // Home's inline preview becomes the fullscreen player without a new
        // engine; the frame is already verified, so match now instead of waiting
        // for the next channel.
        if (allowed && !wasAllowed) matchDisplayMode(0)
    }

    /** Override the persisted AFR setting (tests / a live settings change). */
    fun setFrameRateMatchMode(mode: AfrMode) {
        frameRateMatchOverride = mode
        displayModeSwitcher?.afrMode = mode
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
        /** Effective MPEG-TS transport for every live playback surface. */
        fun onTransportResolved(format: StreamFormat) {}
        /** Structured, URL/message-free failure suitable for bounded QoE. */
        fun onPlaybackFailure(failure: PlaybackFailure) {}
        /**
         * A vendor-native owner cannot prove socket closure. Return true only
         * when the UI launched controlled main-process recovery.
         */
        fun onLocalProcessRecoveryRequired(): Boolean = false
        /**
         * The decode ladder ran dry only because software HD was withheld on
         * this constrained device (SoftwareHdFallbackPolicy). Fired at most
         * once per channel, before the ordinary reconnect/terminal failure, so
         * the UI can tell the user the stream is too heavy for the box.
         */
        fun onStreamTooHeavyForDevice(width: Int, height: Int, codec: String?) {}
        /** Emitted only after all retries + fallback are exhausted. */
        fun onFatalError()
        fun onRetrying(attempt: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The supplied Xiaomi/Amlogic logs prove that libVLC's direct-rendering
     * MediaCodec surface is corrupt on this decoder family while Media3's
     * hardware SurfaceView path is healthy. The decoder-name probe is memoized
     * process-wide (see [DeviceVideoDecoders]): controllers are built on the
     * main thread and MediaCodecList enumeration is slow on vendor builds.
     */
    private val bypassVlcHardware: Boolean = DeviceVideoDecoders.bypassVlcHardware

    private var engine: PlayerEngine? = null
    private var currentUrl: String? = null
    private var currentOriginalUrl: String? = null
    private var stage = Stage.EXO
    private val triedStages = mutableSetOf<Stage>()
    private var videoRebindPending = false

    // ---- Automatic frame-rate matching ------------------------------------
    // Built lazily from the container's Activity window on the first verified
    // frame; null when the container is not hosted by an Activity. The switcher
    // itself dedupes per content family and debounces, so calling it on every
    // zap/re-check is safe: only a family change costs a (blanking) switch.
    private var displayModeSwitcher: DisplayModeSwitcher? = null
    private var displayModeSwitchingAllowed = true
    private var frameRateMatchOverride: AfrMode? = null
    // Guards the delayed fps re-polls (libVLC reports fps=0 on the first frame
    // of a live TS and fills it in a moment later); bumped on play/quiesce/release.
    private var frameRateMatchGen = 0
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
    // Video tunneling is a remote per-device opt-in (never on Amlogic). A
    // tunneled decoder that reaches READY without a frame gets one untunneled
    // replay of the same stage before the ordinary ladder runs.
    private var tunnelingActive = false
    private var tunnelingRetryUsed = false
    // The "too heavy for this device" UI notice fires at most once per channel.
    private var streamTooHeavyReported = false
    private val devicePlaybackProfile
        get() = PlaybackQoeRuntime.devicePlaybackProfile()
    private val remoteOverrides: PlaybackRemotePolicy.DeviceOverrides
        get() = PlaybackRemotePolicy.deviceOverrides()
    // A remote per-device buffer mode replaces only the user's ADAPTIVE choice.
    private val effectiveBufferMode: BufferMode
        get() = AdaptiveBufferPolicy.configuredWithOverride(bufferMode, remoteOverrides.bufferMode)
    private val constrainedDevice: Boolean
        get() = lowRamDevice || devicePlaybackProfile.compatibilityMode
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
    // Earlier stall signal: BUFFERING with a buffer that is not being fed at all
    // for EMPTY_BUFFER_STALL_MS. Armed/retired together with progressPolicy.
    private val starvationPolicy = LiveBufferStarvationPolicy()
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
    //     within the absolute request deadline (a connection that opens but delivers no data),
    //     treat it as an error so the normal ladder/reconnect sequencing runs.
    // Dedicated handler so the controller's other removeCallbacksAndMessages(null)
    // calls can't clobber it. Generation-guarded against an already-dequeued post.
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogGen = 0
    private val deadlineHandler = Handler(Looper.getMainLooper())
    private var attemptEpoch = 0L
    private var attemptTrace: PlaybackAttemptTrace? = null
    private val deadline = LivePlaybackDeadline(
        nowMs = SystemClock::elapsedRealtime,
        schedule = { delay, action ->
            deadlineHandler.removeCallbacksAndMessages(null)
            deadlineHandler.postDelayed({ action() }, delay)
        },
        onTimeout = { terminal ->
            traceAttempt(PlaybackAttemptTrace.Phase.TIMEOUT)
            recordStability("start_timeout", "warn", "request deadline terminal=$terminal")
            notifyPlaybackFailure(failureFor(Reason.STARTUP))
            if (terminal) failPlayback() else handleEngineFailure(Reason.STARTUP, reportFailure = false)
        },
    )

    private fun beginAttempt() {
        traceAttempt(PlaybackAttemptTrace.Phase.CANCELLED)
        ++attemptEpoch
        attemptTrace = PlaybackAttemptTrace(SystemClock.elapsedRealtime())
        traceAttempt(PlaybackAttemptTrace.Phase.REQUESTED)
        if (isLive) deadline.beginAttempt()
    }

    private fun resetDeadline() {
        deadline.reset()
        deadlineHandler.removeCallbacksAndMessages(null)
    }

    private fun traceAttempt(phase: PlaybackAttemptTrace.Phase, httpStatus: Int? = null) {
        val event = attemptTrace?.event(phase, SystemClock.elapsedRealtime(), httpStatus) ?: return
        PlaybackLog.log(context, "LiveAttempt", "$event engine=$stage")
        if (phase == PlaybackAttemptTrace.Phase.REQUESTED) {
            val device = "${Build.MANUFACTURER}/${Build.MODEL}".replace(Regex("[^A-Za-z0-9._ /-]"), "_").take(80)
            PlaybackLog.log(context, "LiveAttempt", "attempt=${attemptTrace?.id} version=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} device=$device sdk=${Build.VERSION.SDK_INT}")
        }
        if (phase in setOf(PlaybackAttemptTrace.Phase.STABLE, PlaybackAttemptTrace.Phase.TIMEOUT, PlaybackAttemptTrace.Phase.TERMINAL)) {
            recordStability("playback_attempt", if (phase == PlaybackAttemptTrace.Phase.STABLE) "info" else "warn", event)
        }
    }

    /** A fatal outcome owns cleanup too; no queued callback can reopen this session. */
    private fun failPlayback() {
        traceAttempt(PlaybackAttemptTrace.Phase.TERMINAL)
        release()
        callback.onFatalError()
    }

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
        resetDeadline()
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
        starvationPolicy.reset()
        playbackBuffering = true
        quickDecodeFailures = 0
        softwareSlowHardwareRetryUsed = false
        ac3PcmFallbackUsed = false
        unconfirmedStartFailures = 0
        adaptiveRebuffers = 0
        adaptiveBufferingActive = false
        tunnelingRetryUsed = false
        streamTooHeavyReported = false
        // Tear down the previous channel's stall/startup watchdog; the (re)start
        // path below re-arms it for this channel.
        cancelWatchdog()
        // Pending fps re-polls belong to the previous channel. The display mode
        // itself is kept: the next channel's first frame re-evaluates it and
        // only a changed frame-rate family costs another switch.
        ++frameRateMatchGen
        currentOriginalUrl = url
        currentTransportKey = transportKey
        currentTransportFormat = if (isLive) StreamFormat.TS else null
        currentUrl = if (isLive) LiveStreamUrl.applyFormat(url, StreamFormat.TS) else url
        if (MediaTransportPolicy.isHlsUrl(currentUrl.orEmpty())) {
            failPlayback()
            return
        }
        currentTransportFormat?.let(callback::onTransportResolved)
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
        // Per-channel learning: a channel that rebuffered in its recent sessions
        // starts on the larger adaptive reserve immediately instead of earning
        // it again through the same stalls. The record is then decayed so the
        // seed fades within a few clean visits.
        val channelRecord = PlaybackRouteMemory.record(currentRouteKey)
        adaptiveRebuffers = PlaybackRouteMemory.seedRebuffers(channelRecord)
        PlaybackRouteMemory.beginSession(currentRouteKey)
        if (adaptiveRebuffers > 0) {
            PlaybackLog.log(
                context,
                "Controller",
                "channel record rebuffers=${channelRecord?.rebufferCount} " +
                    "breaches=${channelRecord?.droppedFrameBreaches} -> seed adaptive $adaptiveRebuffers",
            )
        }
        // Tier 2 self-healing: prefer the stage this channel last proved STABLE on
        // (route memory) over the cold base ladder, so a channel that always needs,
        // say, software decode starts there instead of greening on hardware first.
        val preferredBase = PlaybackRoutingPolicy.initialStage(
            mode,
            decoderMode,
            startEngineOverride = remoteOverrides.startEngine,
        )
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
            // the live URL has already been canonicalized to MPEG-TS.
            // Replaying [url] here silently discarded that decision only on the
            // fast-zap path and also desynchronised route/transport memory.
            beginAttempt()
            reusable.setListener(engineListener(reusable))
            reusable.setPlaybackAttemptId(attemptTrace?.id)
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
                (if (usingRememberedRoute) " (route memory)" else ""),
        )
        startStage(stage)
    }

    /**
     * First stage to try from a COLD start, given the engine choice and decoder
     * strategy (no route memory). [play] prefers [rememberedStage] over this.
     */
    private fun baseInitialStage(): Stage =
        VlcHardwareDevicePolicy.compatibleInitialStage(
            // A remote per-device start engine applies only while the user's
            // engine is AUTO; the Amlogic VLC_HW -> EXO substitution still wins.
            preferred = PlaybackRoutingPolicy.initialStage(
                mode,
                decoderMode,
                startEngineOverride = remoteOverrides.startEngine,
            ),
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
        // Keep the request deadline independent of backend submission and health polls.
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
        beginAttempt()
        // Fresh (re)start: the new attempt must prove its own frame and progress.
        playbackConfirmed = false
        videoOutputConfirmed = false
        progressPolicy.reset()
        starvationPolicy.reset()
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
            failPlayback()
        }
        create = Runnable {
            if (generation != startGeneration) return@Runnable
            traceAttempt(PlaybackAttemptTrace.Phase.WAITING_OWNER)
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
                    VlcOps.awaitProviderDrain { mainHandler.post(create) }
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
                // Remote per-device overrides resolved once per engine build.
                val overrides = remoteOverrides
                val configuredBuffer =
                    AdaptiveBufferPolicy.configuredWithOverride(bufferMode, overrides.bufferMode)
                val constrained = constrainedDevice
                val effectiveBuffer = AdaptiveBufferPolicy.resolve(
                    configured = configuredBuffer,
                    lowRamDevice = constrained,
                    recentRebuffers = adaptiveRebuffers,
                )
                val newEngine: PlayerEngine =
                    if (useVlc) {
                        tunnelingActive = false
                        VlcPlayerEngine(
                            context,
                            forceSoftware,
                            allowPassthrough,
                            effectiveBuffer.networkCachingMs,
                        )
                    } else {
                        tunnelingActive = ExoTunnelingPolicy.shouldEnable(
                            remoteOptIn = overrides.tunneling,
                            videoDecoderNames = DeviceVideoDecoders.names,
                            retryWithoutTunnelingUsed = tunnelingRetryUsed,
                        )
                        ExoPlayerEngine(
                            context = context,
                            allowPassthrough = allowPassthrough,
                            bufferMode = if (configuredBuffer == BufferMode.ADAPTIVE) {
                                configuredBuffer
                            } else {
                                effectiveBuffer
                            },
                            initialRebuffers = adaptiveRebuffers,
                            constrainedDevice = constrained,
                            preferSoftwareAudio = constrained || bypassVlcHardware,
                            expectsVideo = expectsVideo,
                            tunnelingEnabled = tunnelingActive,
                        )
                    }
                candidate = newEngine
                newEngine.setPlaybackAttemptId(attemptTrace?.id)
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
            // Single-connection contract: the retired engine reports when its
            // provider socket has really closed (Media3 only cancels the Loader;
            // a fixed 250 ms gap used to let the next engine open a second
            // connection while the old read was still blocked). Then trampoline
            // through the shared VLC ops thread: the old libVLC stop/release runs
            // there asynchronously (ANR fix), so FIFO ordering guarantees it has
            // released its network connection before the next engine is created
            // — without ever blocking the main thread. With an idle queue (e.g.
            // old engine was ExoPlayer) the hop is immediate.
            val retireStartedMs = SystemClock.elapsedRealtime()
            releaseEngine { released ->
                if (generation != startGeneration) return@releaseEngine
                if (!released) {
                    PlaybackLog.log(
                        context,
                        "Controller",
                        "retired engine socket close unproven -> continue swap",
                    )
                }
                val elapsed = SystemClock.elapsedRealtime() - retireStartedMs
                val gap = (ENGINE_SWAP_DELAY_MS - elapsed).coerceIn(0L, ENGINE_SWAP_DELAY_MS)
                mainHandler.postDelayed({
                    VlcOps.awaitProviderDrain { mainHandler.post(create) }
                }, gap)
            }
        } else {
            releaseEngine()
            create.run()
        }
    }

    private fun engineListener(source: PlayerEngine, epoch: Long = attemptEpoch) = object : PlayerListener {
        private fun dispatch(action: () -> Unit) = post {
            if (source === engine && epoch == attemptEpoch) action()
        }

        override fun onTransportConnecting() = dispatch {
            traceAttempt(PlaybackAttemptTrace.Phase.CONNECTING)
        }
        override fun onTransportBytes() = dispatch {
            traceAttempt(PlaybackAttemptTrace.Phase.FIRST_BYTES)
        }

        override fun onPlaybackSubmitted() = dispatch {
            if (source !== engine || suspended) return@dispatch
            if (playbackConfirmed) return@dispatch
            // Diagnostic only: callbacks cannot extend the request deadline.
            traceAttempt(PlaybackAttemptTrace.Phase.SUBMITTED)
        }

        override fun onBuffering() = dispatch {
            if (source !== engine || suspended) return@dispatch
            playbackBuffering = true
            progressPolicy.onBuffering()
            if (playbackConfirmed && !adaptiveBufferingActive) {
                adaptiveBufferingActive = true
                // Every mid-stream rebuffer feeds the channel record (any buffer
                // mode); only ADAPTIVE also grows this session's reserve.
                PlaybackRouteMemory.recordRebuffer(currentRouteKey)
                if (effectiveBufferMode == BufferMode.ADAPTIVE) {
                    adaptiveRebuffers = (adaptiveRebuffers + 1).coerceAtMost(6)
                }
            }
            callback.onBuffering()
        }

        override fun onDroppedFrameBreach() = dispatch {
            if (source !== engine || suspended) return@dispatch
            PlaybackRouteMemory.recordDroppedFrameBreach(currentRouteKey)
        }

        override fun onTunnelingNoFrame() = dispatch {
            if (source !== engine) return@dispatch
            if (suspended) {
                recoveryNeededOnResume = true
                return@dispatch
            }
            if (ExoTunnelingPolicy.shouldRetryWithoutTunneling(tunnelingActive, tunnelingRetryUsed)) {
                tunnelingRetryUsed = true
                PlaybackLog.log(context, "Controller", "tunneled decoder rendered no frame -> retry $stage untunneled")
                recordStability("tunneling_retry", "warn", "stage=$stage")
                // Same stage, fresh engine: startEngine now resolves tunneling off.
                startStage(stage)
                return@dispatch
            }
            handleEngineFailure(Reason.VIDEO)
        }

        override fun onPlaying() = dispatch {
            if (source !== engine || suspended) return@dispatch
            playbackBuffering = false
            adaptiveBufferingActive = false
            traceAttempt(PlaybackAttemptTrace.Phase.READY)
            callback.onPlaying(source.engineName)
            if (!expectsVideo) onPlaybackProgress()
        }

        override fun onVideoOutput() = dispatch {
            if (source !== engine || suspended) return@dispatch
            adaptiveBufferingActive = false
            val firstVideoOutput = !videoOutputConfirmed
            videoOutputConfirmed = true
            unconfirmedStartFailures = 0
            traceAttempt(PlaybackAttemptTrace.Phase.FRAME)
            callback.onVideoResumed()
            // Match the TV refresh rate as early as the format is known: right
            // after the first real frame (the no-first-frame watchdogs are already
            // satisfied, so the brief HDMI re-sync blank cannot be misread).
            if (firstVideoOutput) matchDisplayMode(recheckIndex = 0)
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

        override fun onEnded() = dispatch {
            if (source !== engine) return@dispatch
            if (suspended) {
                recoveryNeededOnResume = true
                return@dispatch
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
            dispatch { if (source === engine) handleEngineFailure(Reason.ERROR) }

        override fun onStartupFailure(message: String?) =
            dispatch { if (source === engine) handleEngineFailure(Reason.STARTUP) }

        override fun onSourceFailure(message: String?, httpStatus: Int?) = dispatch {
            if (source !== engine) return@dispatch
            if (suspended) {
                recoveryNeededOnResume = true
                return@dispatch
            }
            traceAttempt(PlaybackAttemptTrace.Phase.FAILURE, httpStatus)
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
            if (LiveTransportPolicy.isAuthoritativeHttpFailure(httpStatus)) {
                // Authorization/resource rejection cannot be repaired by a decoder swap.
                PlaybackLog.log(context, "Controller", "terminal source HTTP $httpStatus")
                failPlayback()
                return@dispatch
            }
            handleEngineFailure(Reason.ERROR, reportFailure = false)
        }

        override fun onDecodeError(message: String?) =
            dispatch { if (source === engine) handleEngineFailure(Reason.DECODE) }

        override fun onAudioUnavailable() =
            dispatch { if (source === engine) handleEngineFailure(Reason.AUDIO) }

        override fun onAudioStall(evidence: AudioFailureEvidence) = dispatch {
            if (source === engine) handleAudioStall(evidence)
        }

        override fun onVideoInvalid() =
            dispatch { if (source === engine) handleEngineFailure(Reason.VIDEO) }

        override fun onSoftwareTooSlow() =
            dispatch { if (source === engine) handleEngineFailure(Reason.SOFTWARE_SLOW) }
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
        PlaybackLog.log(context, "LiveAttempt", "attempt=${attemptTrace?.id} engine=$stage failure=${failure.code} phase=${failure.phase}")
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
            failPlayback()
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
        // Start the stall poll alongside the request deadline. A single frame
        // cannot cancel the deadline; sustained output must earn that reset.
        armStallWatchdog()
    }

    /** Called only after uninterrupted ready samples prove sustained progress. */
    private fun onStablePlayback() {
        val stableStage = stage
        val stableKey = currentRouteKey
        if (suspended || !playbackConfirmed || playbackBuffering) return
        PlaybackLog.log(context, "Controller", "sustained playback progress -> recovered")
        resetDeadline()
        traceAttempt(PlaybackAttemptTrace.Phase.STABLE)
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
        }
        usingRememberedRoute = false
        // Long-tail fps population (libVLC live TS): one more look once stable.
        matchDisplayMode(recheckIndex = null)
        callback.onStablePlayback()
    }

    /**
     * Feed the engine's current video format to the display-mode switcher. With
     * [recheckIndex] a bounded re-poll chain is scheduled while the engine still
     * reports an unknown fps; null = single evaluation. Cheap when nothing
     * changed: the switcher ignores a family it already handled.
     */
    private fun matchDisplayMode(recheckIndex: Int?) {
        // Never before a verified frame: Exo knows the Format (and fps) at track
        // selection, so an early fullscreen entry would otherwise switch modes
        // inside the no-first-frame / pixel-validation windows.
        if (!expectsVideo || !displayModeSwitchingAllowed || !videoOutputConfirmed) return
        val switcher = displayModeSwitcher ?: DisplayModeSwitcher.from(container)?.also {
            it.allowed = displayModeSwitchingAllowed
            frameRateMatchOverride?.let { mode -> it.afrMode = mode }
            displayModeSwitcher = it
        } ?: return
        if (switcher.afrMode == AfrMode.OFF) return
        val info = engine?.getStreamInfo()
        if (info != null && info.fps > 0f) {
            switcher.apply(info.fps, info.width, info.height)
            return
        }
        val next = recheckIndex ?: return
        if (next >= FRAME_RATE_RECHECK_DELAYS_MS.size) return
        val gen = frameRateMatchGen
        mainHandler.postDelayed({
            if (gen == frameRateMatchGen && engine != null && !suspended) {
                matchDisplayMode(recheckIndex = next + 1)
            }
        }, FRAME_RATE_RECHECK_DELAYS_MS[next])
    }

    // ---- Watchdog ---------------------------------------------------------

    /** Stop the stall poll without touching the request deadline. The generation
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
        starvationPolicy.start()
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
                outputHealthy = engine?.hasRecentOutputProgress() == true,
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
                engageReconnect()
                return@postDelayed
            }
            // Earlier signal: BUFFERING with a buffer nobody is feeding. Cannot
            // fire during a healthy top-up (the fill marker keeps growing).
            val starved = starvationPolicy.sample(
                nowMs = now,
                buffering = playbackBuffering,
                bufferMarker = engine?.bufferFillMarker() ?: -1L,
            )
            if (starved) {
                PlaybackLog.log(
                    context,
                    "Controller",
                    "buffer starvation (buffering, no data ${LiveBufferStarvationPolicy.EMPTY_BUFFER_STALL_MS}ms) -> reconnect",
                )
                recordStability("stall", "warn", "buffer starvation")
                notifyPlaybackFailure(
                    PlaybackFailureClassifier.classify(
                        FailureSignal.Timeout(FailureSignal.TimeoutKind.STALL),
                        PlaybackFailure.Phase.PLAYBACK,
                    ),
                )
                cancelWatchdog()
                engageReconnect()
                return@postDelayed
            }
            scheduleStallPoll(gen)
        }, WATCHDOG_POLL_MS)
    }

    private fun handleFailure(reason: Reason) {
        // Never carry readiness/progress evidence into a pending recovery.
        progressPolicy.reset()
        starvationPolicy.reset()

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
            failPlayback()
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
        starvationPolicy.reset()
        // Retire the old output poll. The separate episode deadline continues
        // through retry backoff, cleanup and replacement engine submission.
        cancelWatchdog()
        if (suspended) {
            recoveryNeededOnResume = true
            return
        }
        if (!isLive) {
            PlaybackLog.log(context, "Controller", "fatal after $stage (non-live, no reconnect)")
            recordStability("fatal", "fatal", "non-live, no further decode path (stage=$stage)")
            failPlayback()
            return
        }
        // An attempt is already scheduled/in-flight: don't stack a second one
        // (EncounteredError + EndReached can both fire for the same drop).
        if (reconnectPending) return
        deadline.waitingForRetry()

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
            failPlayback()
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
        val width = streamInfo?.width ?: 0
        val height = streamInfo?.height ?: 0
        val codec = streamInfo?.codec
        val softwareCodecUnavailable =
            !devicePlaybackProfile.allowSoftwareHevcRescue &&
                isHevcCodec(codec)
        // Weak sticks must not be pushed onto software HD/HEVC: it trades a
        // green picture for a CPU-bound slideshow. Remote override re-enables it.
        val softwareHdExcluded = SoftwareHdFallbackPolicy.excludedStages(
            constrainedDevice = constrainedDevice,
            width = width,
            height = height,
            codec = codec,
            allowSoftwareHdFallback = remoteOverrides.allowSoftwareHdFallback == true,
        )
        val unavailableAwareTriedStages =
            triedStages + VlcHardwareDevicePolicy.unavailableStages(
                bypassVlcHardware = bypassVlcHardware,
                width = width,
                height = height,
            ) + (if (softwareCodecUnavailable) setOf(Stage.VLC_SW) else emptySet()) +
                softwareHdExcluded
        val next = PlaybackRoutingPolicy.nextStage(
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
        if (
            !streamTooHeavyReported &&
            SoftwareHdFallbackPolicy.shouldReportTooHeavy(next, softwareHdExcluded, reason)
        ) {
            streamTooHeavyReported = true
            PlaybackLog.log(
                context,
                "Controller",
                "ladder exhausted with software HD withheld (${width}x$height $codec) -> too heavy for device",
            )
            recordStability("stream_too_heavy", "warn", "${width}x$height $codec stage=$current")
            callback.onStreamTooHeavyForDevice(width, height, codec)
        }
        return next
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
        ++attemptEpoch
        resetDeadline()
        recoveryNeededOnResume = currentUrl != null
        ++startGeneration
        mainHandler.removeCallbacksAndMessages(null)
        progressPolicy.reset()
        starvationPolicy.reset()
        resetReconnect()
        cancelWatchdog()
        // Background/Cast quiesce: hand the TV back its own refresh rate. The
        // resumed stream re-matches from its first verified frame.
        ++frameRateMatchGen
        displayModeSwitcher?.restore()
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

    fun release() {
        // Invalidate any pending engine-create. The swap path trampolines the
        // create through the VLC ops thread, where it can sit for seconds behind
        // a stalled stop — removeCallbacksAndMessages() cannot reach it there, so
        // without this bump a queued create would later pass its generation
        // check, build a ghost engine and play into a destroyed screen.
        suspended = true
        ++attemptEpoch
        resetDeadline()
        recoveryNeededOnResume = false
        ++startGeneration
        mainHandler.removeCallbacksAndMessages(null)
        progressPolicy.reset()
        starvationPolicy.reset()
        cancelWatchdog()
        resetReconnect()
        ++frameRateMatchGen
        // Leaving playback: give the launcher/Home its original display mode back.
        displayModeSwitcher?.release()
        releaseEngine()
    }

    /**
     * Retire the active engine. With [onReleased] the caller is told (on main)
     * when the engine's provider socket has actually closed, so a replacement
     * engine never opens a second connection; false means the bound elapsed.
     */
    private fun releaseEngine(onReleased: ((Boolean) -> Unit)? = null) {
        videoRebindPending = false
        val retiring = engine
        engine = null
        retiring?.setListener(null)
        when {
            retiring == null -> onReleased?.invoke(true)
            onReleased == null -> retiring.release()
            else -> retiring.releaseAndThen { released -> post { onReleased(released) } }
        }
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
         * inheriting a green frame on Amlogic. Short enough to stay snappy. The
         * retired engine's socket-close boundary is awaited first; this gap is
         * the minimum that still elapses after retirement was requested.
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
         * Re-poll schedule for the stream fps after the first frame. libVLC
         * reports 0 on the first Vout of a live TS and fills it in shortly
         * after; ExoPlayer knows it at the first frame, so the chain ends early.
         */
        private val FRAME_RATE_RECHECK_DELAYS_MS = longArrayOf(1_000L, 2_500L, 5_000L)
    }
}
