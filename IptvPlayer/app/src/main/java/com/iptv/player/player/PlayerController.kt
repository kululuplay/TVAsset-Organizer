/*
 * PlayerController.kt
 * Orchestrates the two engines and the automatic software fallback. A stream is
 * tried hardware-first; if the hardware path greens out, can't decode the audio,
 * or errors, it is automatically restarted on a software path — one connection
 * at a time (full stop -> release -> restart).
 *
 * Fallback ladder (per stream):
 *   EXO  ──green──▶ VLC_SW            (hardware video failed: go straight to SW)
 *   EXO  ──audio/error──▶ VLC_HW      (video ok, let libVLC software-decode audio)
 *   VLC_HW ──green/audio/error──▶ VLC_SW
 *   VLC_SW ──error──▶ retry w/ backoff ──▶ fatal
 *
 * The ladder is shaped by PlayerMode (start engine) and DecoderMode (whether the
 * software stage is allowed / forced). One override: a GREEN video failure always
 * drops to the software stage even in HARDWARE mode — a green frame is unusable, so
 * software is the only remaining way to show a picture. (On Amlogic boxes the
 * libVLC hardware underlay greens on every non-UHD profile, so VlcPlayerEngine
 * flags the whole non-UHD hardware path as green-prone there.)
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
import com.iptv.player.util.DeviceCaps
import com.iptv.player.util.PlaybackLog

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
        eng.detachVideo()
        mainHandler.postDelayed({
            if (generation != startGeneration || eng !== engine) return@postDelayed
            eng.attachVideo(newContainer)
            // reset=true: hand-off needs a deterministic decoder/renderer reset
            // onto the re-attached surface (bare re-add can stall video).
            if (url != null) eng.play(url, reset = true)
        }, ENGINE_SWAP_DELAY_MS)
    }

    /** UI-facing events from the controller (already engine-agnostic). */
    interface Callback {
        fun onBuffering()
        fun onPlaying(engineName: String)
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

    /** Which engine + decode path a stream is currently running on. */
    private enum class Stage { EXO, VLC_HW, VLC_SW }

    /** Why the current stage failed, so we pick the right next stage. */
    private enum class Reason { ERROR, AUDIO, VIDEO, SOFTWARE_SLOW }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: PlayerEngine? = null
    private var currentUrl: String? = null
    private var stage = Stage.EXO
    private val triedStages = mutableSetOf<Stage>()
    // Bumped on every (re)start so a delayed engine creation that has been
    // superseded becomes a no-op. Guards the SurfaceView-swap handoff gap.
    private var startGeneration = 0

    // True once the CURRENT stage produced confirmed playback (onPlaying /
    // onVideoOutput). A plain ERROR after this is a mid-stream DROP, not a
    // startup decode problem, so it reconnects the same stage instead of walking
    // the decode-fallback ladder onto another engine. Reset on every (re)start.
    private var playbackConfirmed = false

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

    fun play(url: String) {
        // Cancel any pending retry from a previous stream so zapping is clean.
        mainHandler.removeCallbacksAndMessages(null)
        // A new channel cancels any in-flight reconnect so it can't fire against
        // the stale stream or open a second connection.
        resetReconnect()
        currentUrl = url
        playbackConfirmed = false
        val initial = initialStage()

        // Fast zap: when the next stream would start on the very same stage the
        // current engine is already running, keep that engine alive and just swap
        // the stream on it. This skips the full release + re-create of a fresh
        // LibVLC/ExoPlayer instance AND the ENGINE_SWAP_DELAY_MS surface-handoff
        // guard, so channel changes are near-instant — and because the SurfaceView
        // is retained (no teardown), it sidesteps the swap-gap green frame too.
        // Only the steady state qualifies: if the current stream had already
        // fallen back to a different stage, drop through to a clean restart so the
        // new channel still gets the full hardware-first fallback ladder.
        val reusable = engine
        if (reusable != null && stage == initial) {
            triedStages.clear()
            triedStages.add(initial)
            PlaybackLog.log(context, "Controller", "fast-zap reuse stage=$initial")
            reusable.play(url)
            return
        }

        triedStages.clear()
        stage = initial
        PlaybackLog.log(context, "Controller", "play mode=$mode decoder=$decoderMode start=$stage")
        startStage(stage)
    }

    /** First stage to try, given the engine choice and decoder strategy. */
    private fun initialStage(): Stage = when {
        // An explicit ExoPlayer choice wins over EVERYTHING — including the
        // SOFTWARE decoder default — so the user can actually reach Media3's
        // native MediaCodec -> SurfaceView path. On Amlogic boxes (e.g. Xiaomi
        // Stick) libVLC's android_display vout greens the hardware underlay;
        // ExoPlayer hands the SurfaceView straight to MediaCodec, which is the
        // box's native hardware-video pipeline and often shows real video.
        mode == PlayerMode.EXOPLAYER -> Stage.EXO
        // Software decoder choice always forces libVLC software decode.
        decoderMode == DecoderMode.SOFTWARE -> Stage.VLC_SW
        // Amlogic boxes: libVLC's hardware underlay greens out on EVERY non-UHD
        // profile (confirmed in the field — VLC logs "output: 17 unknown") and the
        // runtime green detection is unreliable there (currentVideoTrack often reads
        // 0x0/null, so the reactive software fallback never fires). So skip the green
        // VLC hardware path entirely: AUTO starts on ExoPlayer — the box's working
        // native MediaCodec -> SurfaceView hardware-video path (hardware quality, no
        // green; audio-incompatible channels drop to VLC_SW in nextStage). An explicit
        // VLC choice honours libVLC but starts software, the only non-green VLC path.
        DeviceCaps.isAmlogic -> if (mode == PlayerMode.VLC) Stage.VLC_SW else Stage.EXO
        // Other boxes: AUTO/Hardware (and PlayerMode.VLC) start on libVLC hardware.
        else -> Stage.VLC_HW
    }

    private fun startStage(target: Stage) {
        triedStages.add(target)
        stage = target
        // Fresh (re)start: this stage has not confirmed playback yet.
        playbackConfirmed = false
        val useVlc = target != Stage.EXO
        val forceSoftware = target == Stage.VLC_SW
        startEngine(useVlc, forceSoftware)
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
                else ExoPlayerEngine(context, allowPassthrough, bufferMode)
            newEngine.bind(container)
            newEngine.setListener(engineListener)
            engine = newEngine
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
            mainHandler.postDelayed(create, ENGINE_SWAP_DELAY_MS)
        } else {
            create.run()
        }
    }

    private val engineListener = object : PlayerListener {
        override fun onBuffering() = post { callback.onBuffering() }

        override fun onPlaying() = post {
            // Stream is running again: a confirmed-playback signal clears any
            // in-flight reconnect and resets the window so a later, separate drop
            // gets a fresh full set of attempts.
            playbackConfirmed = true
            resetReconnect()
            callback.onPlaying(engine?.engineName ?: "")
        }

        override fun onVideoOutput() = post {
            // Real frame on screen: the most reliable "playback genuinely resumed"
            // signal — clear the reconnect window/overlay here too.
            playbackConfirmed = true
            resetReconnect()
            callback.onVideoResumed()
        }

        override fun onEnded() = post {
            // A live stream should not end; the server closed/restarted it. Treat
            // as a drop and reconnect the same channel. (Non-live ignores it.)
            if (isLive) {
                PlaybackLog.log(context, "Controller", "live stream ended -> reconnect")
                engageReconnect()
            }
        }

        override fun onError(message: String?) = post { handleFailure(Reason.ERROR) }
        override fun onAudioUnavailable() = post { handleFailure(Reason.AUDIO) }
        override fun onVideoInvalid() = post { handleFailure(Reason.VIDEO) }
        override fun onSoftwareTooSlow() = post { handleFailure(Reason.SOFTWARE_SLOW) }
    }

    private fun handleFailure(reason: Reason) {
        // A plain ERROR after the stream already played — or while a reconnect
        // episode is already under way — is a stream DROP, not a startup decode
        // problem: skip the decode-fallback ladder (which would needlessly switch
        // a working stream onto another engine, or fight the reconnect by hopping
        // engines on each failed attempt) and just reconnect the same stage.
        // VIDEO (green) / AUDIO (silent) / SOFTWARE_SLOW (4K) are decode-quality
        // problems the ladder must still handle even after the first frame, since
        // their detection fires only once playback has begun.
        val dropAfterPlay = reason == Reason.ERROR && (playbackConfirmed || reconnecting)
        if (!dropAfterPlay) {
            val next = nextStage(stage, reason)
            if (next != null && next !in triedStages) {
                PlaybackLog.log(context, "Controller", "fallback $stage --$reason--> $next")
                startStage(next)
                return
            }
        }

        // No further engine/decode path (or a confirmed-playback drop): hand off
        // to the bounded live-TV reconnect loop.
        engageReconnect()
    }

    /**
     * A live stream dropped/ended with no further decode path to try. Re-open the
     * SAME channel on the current stage with a backoff schedule bounded by a total
     * window, until it recovers (onPlaying / onVideoOutput resets the state) or the
     * window elapses (then a fatal error with a manual-retry UI). For non-live this
     * degrades to the previous behaviour: a single fatal error, no reconnect loop.
     */
    private fun engageReconnect() {
        if (!isLive) {
            PlaybackLog.log(context, "Controller", "fatal after $stage (non-live, no reconnect)")
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
        }
        if (now - reconnectWindowStartMs >= RECONNECT_WINDOW_MS) {
            PlaybackLog.log(context, "Controller", "reconnect window elapsed -> fatal")
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
        play(url)
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
        // UHD/4K on software can't decode in real time -> escalate to the hardware
        // decoder (the only viable 4K path). This is an UPGRADE, the opposite of the
        // green/software downgrade ladder below, so it runs regardless of decoderMode.
        if (reason == Reason.SOFTWARE_SLOW) {
            return if (current == Stage.VLC_SW) Stage.VLC_HW else null
        }
        val softwareAllowed = decoderMode != DecoderMode.HARDWARE
        return when (current) {
            Stage.EXO -> when (reason) {
                // Hardware video greened: the hw H.264 decoder is the culprit, so
                // skip VLC's hardware path and go straight to software. Green means
                // hardware cannot produce a picture AT ALL, so this drop is allowed
                // even in HARDWARE decoder mode — otherwise the user just stares at a
                // green screen with no way out.
                Reason.VIDEO -> Stage.VLC_SW
                // Video is fine; let libVLC software-decode the audio codec. On
                // Amlogic the libVLC HARDWARE path greens out, so go straight to
                // VLC_SW there (a green picture is worse than software-decoded video).
                // Elsewhere keep libVLC HARDWARE video + software audio: software video
                // would stutter on 1080p, so we recommend PlayerMode.VLC for those.
                Reason.AUDIO, Reason.ERROR ->
                    if (DeviceCaps.isAmlogic) Stage.VLC_SW else Stage.VLC_HW
                Reason.SOFTWARE_SLOW -> null
            }
            // Green on the libVLC hardware path (Amlogic underlay etc.) always drops
            // to software, regardless of decoder mode — a green frame is unusable, so
            // software is the only remaining way to show a picture. Plain errors
            // still honour the HARDWARE "no software" preference.
            Stage.VLC_HW -> when (reason) {
                Reason.VIDEO -> Stage.VLC_SW
                else -> if (softwareAllowed) Stage.VLC_SW else null
            }
            Stage.VLC_SW -> null
        }
    }

    /** Current video stream info for the diagnostics overlay, or null. */
    fun streamInfo(): StreamInfo? = engine?.getStreamInfo()

    fun pause() {
        // Backgrounded: cancel pending reconnects so we don't open a connection
        // off-screen. A drop while backgrounded is recovered by a manual retry /
        // zap on return, never by a hidden background reconnect.
        resetReconnect()
        engine?.pause()
    }
    fun resume() = engine?.resume()
    fun stop() {
        resetReconnect()
        engine?.stop()
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
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
    }
}
