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

    /**
     * Audio sync offset in milliseconds (positive = delay audio). Only libVLC
     * supports this; the ExoPlayer backend ignores it (no public offset API).
     */
    fun setAudioDelayMs(ms: Long) {}

    /** Subtitle sync offset in milliseconds. libVLC only; ExoPlayer ignores it. */
    fun setSubtitleDelayMs(ms: Long) {}

    /** True when this backend can actually apply audio/subtitle delay. */
    val supportsDelay: Boolean get() = false

    // ---- Track selection -------------------------------------------------

    /**
     * Audio tracks currently exposed by the active stream. Empty until playback
     * has started and the engine has parsed the stream's tracks.
     */
    fun availableAudioTracks(): List<TrackOption> = emptyList()

    /** Subtitle tracks currently exposed by the active stream (excludes "off"). */
    fun availableSubtitleTracks(): List<TrackOption> = emptyList()

    /**
     * Apply a chosen audio track. The [token] is a value from a previously
     * returned [TrackOption] and is remembered by the engine so it can be
     * re-applied automatically when the next stream starts (zapping / reconnect).
     */
    fun selectAudioTrack(token: String) {}

    /**
     * Apply a chosen subtitle track. [SUBTITLE_OFF] disables subtitles. As with
     * audio, the choice is remembered and re-applied on later streams.
     */
    fun selectSubtitleTrack(token: String) {}

    /** Identifies which backend this is (for UI badges / logging). */
    val engineName: String

    companion object {
        /** Sentinel token meaning "no subtitles". */
        const val SUBTITLE_OFF = "__off__"
    }
}

/**
 * A selectable audio/subtitle track surfaced to the UI.
 * @param token engine-specific value used to re-apply the choice (a language tag
 *   for ExoPlayer, a track name for libVLC).
 * @param label human-readable text shown in the picker.
 * @param selected whether this track is the one currently playing.
 */
data class TrackOption(
    val token: String,
    val label: String,
    val selected: Boolean = false
)

/** Playback state callbacks routed to the UI. */
interface PlayerListener {
    fun onBuffering() {}
    fun onPlaying() {}
    fun onEnded() {}
    /** A fatal playback error. The controller may trigger fallback/retry. */
    fun onError(message: String?) {}
}
