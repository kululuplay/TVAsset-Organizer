/*
 * PlayerEngine.kt
 * Common abstraction over the two playback backends (ExoPlayer + libVLC) so the
 * UI never touches a specific player API. New capabilities (audio/subtitle track
 * selection, aspect ratio) will be added to this interface as features land.
 */
package com.iptv.player.player

import android.view.ViewGroup

interface PlayerEngine {

    /** Attach the engine's video surface into the given container. */
    fun bind(container: ViewGroup)

    /** Start (or restart) playback of a stream URL. */
    fun play(url: String)

    fun pause()
    fun resume()
    fun stop()

    /** Release all native resources. Engine is unusable afterwards. */
    fun release()

    fun setListener(listener: PlayerListener?)

    /** Identifies which backend this is (for UI badges / logging). */
    val engineName: String
}

/** Playback state callbacks routed to the UI. */
interface PlayerListener {
    fun onBuffering() {}
    fun onPlaying() {}
    fun onEnded() {}
    /** A fatal playback error. The controller may trigger fallback/retry. */
    fun onError(message: String?) {}
}
