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
    private var callback: Callback
) {

    /** Swap the UI callback (used when a preview controller is adopted by the player). */
    fun setCallback(cb: Callback) { callback = cb }

    /**
     * Re-home the currently running engine's video surface onto [newContainer]
     * WITHOUT restarting playback (no new Media, the stream socket stays open).
     * Used to hand a live preview over to the fullscreen player seamlessly.
     *
     * The detach -> delay -> attach sequence mirrors the engine-swap timing: a
     * SurfaceView's underlying surface is torn down asynchronously, so adding the
     * surface to the new container in the same pass would race that teardown and
     * green the fresh surface on Amlogic. Deferring the re-attach clears it.
     */
    fun rebind(newContainer: ViewGroup) {
        context = newContainer.context
        container = newContainer
        val eng = engine ?: return
        eng.detachVideo()
        mainHandler.postDelayed({ eng.attachVideo(newContainer) }, ENGINE_SWAP_DELAY_MS)
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
    private var retryCount = 0
    // Bumped on every (re)start so a delayed engine creation that has been
    // superseded becomes a no-op. Guards the SurfaceView-swap handoff gap.
    private var startGeneration = 0

    private var audioDelayMs = 0L
    private var subtitleDelayMs = 0L

    private val maxRetries = 4

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
        currentUrl = url
        retryCount = 0
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
            retryCount = 0
            callback.onPlaying(engine?.engineName ?: "")
        }

        override fun onVideoOutput() = post { callback.onVideoResumed() }

        override fun onError(message: String?) = post { handleFailure(Reason.ERROR) }
        override fun onAudioUnavailable() = post { handleFailure(Reason.AUDIO) }
        override fun onVideoInvalid() = post { handleFailure(Reason.VIDEO) }
        override fun onSoftwareTooSlow() = post { handleFailure(Reason.SOFTWARE_SLOW) }
    }

    private fun handleFailure(reason: Reason) {
        val next = nextStage(stage, reason)
        if (next != null && next !in triedStages) {
            PlaybackLog.log(context, "Controller", "fallback $stage --$reason--> $next")
            retryCount = 0
            startStage(next)
            return
        }

        // No further engine/decode path: retry the same stage with backoff
        // (handles transient network errors) before giving up.
        if (retryCount < maxRetries) {
            retryCount++
            callback.onRetrying(retryCount)
            val delay = (1000L * (1 shl (retryCount - 1))).coerceAtMost(8000L)
            mainHandler.postDelayed({
                currentUrl?.let { engine?.play(it) }
            }, delay)
        } else {
            PlaybackLog.log(context, "Controller", "fatal after $stage ($reason)")
            callback.onFatalError()
        }
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

    fun pause() = engine?.pause()
    fun resume() = engine?.resume()
    fun stop() = engine?.stop()

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
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
    }
}
