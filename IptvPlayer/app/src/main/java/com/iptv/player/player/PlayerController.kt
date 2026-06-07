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
 * software stage is allowed / forced).
 */
package com.iptv.player.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.util.PlaybackLog

class PlayerController(
    private val context: Context,
    private val container: ViewGroup,
    private val mode: PlayerMode,
    private val decoderMode: DecoderMode = DecoderMode.AUTO,
    private val allowPassthrough: Boolean = false,
    private val callback: Callback
) {

    /** UI-facing events from the controller (already engine-agnostic). */
    interface Callback {
        fun onBuffering()
        fun onPlaying(engineName: String)
        /** Emitted only after all retries + fallback are exhausted. */
        fun onFatalError()
        fun onRetrying(attempt: Int)
    }

    /** Which engine + decode path a stream is currently running on. */
    private enum class Stage { EXO, VLC_HW, VLC_SW }

    /** Why the current stage failed, so we pick the right next stage. */
    private enum class Reason { ERROR, AUDIO, VIDEO }

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
        triedStages.clear()
        retryCount = 0
        stage = initialStage()
        PlaybackLog.log(context, "Controller", "play mode=$mode decoder=$decoderMode start=$stage")
        startStage(stage)
    }

    /** First stage to try, given the engine choice and decoder strategy. */
    private fun initialStage(): Stage = when {
        decoderMode == DecoderMode.SOFTWARE -> Stage.VLC_SW
        // AUTO and VLC both start directly on libVLC hardware. Starting on
        // ExoPlayer and then falling back to libVLC (e.g. for MP2/AC-3 audio Exo
        // can't decode) forces an EXO -> VLC SurfaceView swap, after which the
        // Amlogic HW video decoder paints a GREEN frame on the fresh surface.
        // Software video avoids the green but stutters on 1080p, so the only path
        // that is both green-free AND smooth is to never swap: begin on VLC_HW.
        // ExoPlayer is used only when the user explicitly selects it.
        mode == PlayerMode.EXOPLAYER -> Stage.EXO
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
                if (useVlc) VlcPlayerEngine(context, forceSoftware, allowPassthrough)
                else ExoPlayerEngine(context, allowPassthrough)
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

        override fun onError(message: String?) = post { handleFailure(Reason.ERROR) }
        override fun onAudioUnavailable() = post { handleFailure(Reason.AUDIO) }
        override fun onVideoInvalid() = post { handleFailure(Reason.VIDEO) }
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
        val softwareAllowed = decoderMode != DecoderMode.HARDWARE
        return when (current) {
            Stage.EXO -> when (reason) {
                // Hardware video greened: the hw H.264 decoder is the culprit, so
                // skip VLC's hardware path and go straight to software.
                Reason.VIDEO -> if (softwareAllowed) Stage.VLC_SW else Stage.VLC_HW
                // Video is fine; let libVLC HARDWARE-decode the video and software-
                // decode the audio codec. (Software video here removes the green on
                // the EXO -> VLC swap but stutters on 1080p, so we keep hardware and
                // recommend PlayerMode.VLC for the affected channels instead.)
                Reason.AUDIO, Reason.ERROR -> Stage.VLC_HW
            }
            Stage.VLC_HW -> if (softwareAllowed) Stage.VLC_SW else null
            Stage.VLC_SW -> null
        }
    }

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
