/*
 * PlayerController.kt
 * Orchestrates the two engines according to PlayerMode and handles:
 *   - AUTO mode: start on ExoPlayer, fall back to libVLC on fatal error.
 *   - Retry with exponential backoff (capped) before giving up.
 * Owns the engine lifecycle so PlayerActivity stays thin.
 */
package com.iptv.player.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import com.iptv.player.data.model.PlayerMode

class PlayerController(
    private val context: Context,
    private val container: ViewGroup,
    private val mode: PlayerMode,
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: PlayerEngine? = null
    private var currentUrl: String? = null
    private var triedVlcFallback = false
    private var retryCount = 0

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
        triedVlcFallback = false
        retryCount = 0
        // Pick the initial engine based on the user's chosen mode.
        startEngine(useVlc = mode == PlayerMode.VLC)
    }

    private fun startEngine(useVlc: Boolean) {
        releaseEngine()
        val newEngine: PlayerEngine =
            if (useVlc) VlcPlayerEngine(context) else ExoPlayerEngine(context)
        newEngine.bind(container)
        newEngine.setListener(engineListener)
        engine = newEngine
        // Re-apply any user delay offsets to the (new) engine.
        newEngine.setAudioDelayMs(audioDelayMs)
        newEngine.setSubtitleDelayMs(subtitleDelayMs)
        currentUrl?.let { newEngine.play(it) }
    }

    private val engineListener = object : PlayerListener {
        override fun onBuffering() = post { callback.onBuffering() }

        override fun onPlaying() = post {
            retryCount = 0
            callback.onPlaying(engine?.engineName ?: "")
        }

        override fun onError(message: String?) = post { handleError() }
    }

    private fun handleError() {
        // AUTO: if ExoPlayer failed and we haven't tried VLC, switch engines.
        val onExo = engine?.engineName == "ExoPlayer"
        if (mode == PlayerMode.AUTO && onExo && !triedVlcFallback) {
            triedVlcFallback = true
            retryCount = 0
            startEngine(useVlc = true)
            return
        }

        // Otherwise retry the same engine with exponential backoff.
        if (retryCount < maxRetries) {
            retryCount++
            callback.onRetrying(retryCount)
            val delay = (1000L * (1 shl (retryCount - 1))).coerceAtMost(8000L)
            mainHandler.postDelayed({
                currentUrl?.let { engine?.play(it) }
            }, delay)
        } else {
            callback.onFatalError()
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
}
