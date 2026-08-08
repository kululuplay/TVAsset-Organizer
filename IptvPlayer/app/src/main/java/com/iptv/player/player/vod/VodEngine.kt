package com.iptv.player.player.vod

import android.view.ViewGroup
import androidx.annotation.MainThread
import com.iptv.player.playback.core.PlaybackFailure

/**
 * VOD-specific engine contract used by the future Media3-first/VLC-fallback
 * coordinator. It is intentionally independent from the live player-engine
 * lifecycle and does not require either backend at the UI boundary.
 */
interface VodEngine {
    val engineName: String

    /** Attach or re-home the engine-owned PlayerView without restarting playback. */
    @MainThread
    fun bind(container: ViewGroup)

    /** Submit a new item and return its monotonically increasing generation. */
    @MainThread
    fun play(url: String, resumeMs: Long = 0L): Long

    @MainThread
    fun pause()

    @MainThread
    fun resume()

    @MainThread
    fun seekTo(positionMs: Long)

    @MainThread
    fun stop()

    @MainThread
    fun durationMs(): Long

    @MainThread
    fun positionMs(): Long

    @MainThread
    fun isPlaying(): Boolean

    /**
     * Backend-native proof that video frames are still reaching the renderer.
     * A progressing media clock is not sufficient because audio can continue
     * after video decode/output freezes. Engines without a trustworthy per-frame
     * signal return null rather than guessing from pixel equality.
     */
    @MainThread
    fun videoLiveness(): VodVideoLiveness? = null

    @MainThread
    fun streamInfo(): VodStreamInfo?

    @MainThread
    fun audioTracks(): List<VodTrack>

    @MainThread
    fun subtitleTracks(): List<VodTrack>

    /** null clears the explicit override and returns audio selection to automatic. */
    @MainThread
    fun selectAudioTrack(id: String?): Boolean

    /** null explicitly disables subtitles. */
    @MainThread
    fun selectSubtitleTrack(id: String?): Boolean

    /** Apply normalized BCP-47 preferences to future and currently known tracks. */
    @MainThread
    fun setPreferredLanguages(audio: String?, subtitle: String?)

    @MainThread
    fun setAspectMode(mode: VodAspectMode)

    @MainThread
    fun setListener(listener: Listener?)

    /** Idempotent. No callback may escape after this method returns. */
    @MainThread
    fun release()

    interface Listener {
        fun onSubmitted(generation: Long) {}
        fun onBuffering(generation: Long) {}
        fun onReady(generation: Long) {}
        /** A usable video frame has passed the backend's bounded output validation. */
        fun onFirstFrame(generation: Long) {}
        fun onEnded(generation: Long) {}

        fun onTracksChanged(
            generation: Long,
            audio: List<VodTrack>,
            subtitles: List<VodTrack>,
        ) {}

        fun onFailure(generation: Long, failure: PlaybackFailure) {}
    }

    companion object {
        const val NO_GENERATION = -1L
        const val TIME_UNSET_MS = -1L
    }
}

data class VodStreamInfo(
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val videoMimeType: String?,
    val videoCodecs: String?,
    val audioMimeType: String?,
    val audioCodecs: String?,
    val bitrateKbps: Int?,
    val durationMs: Long?,
    val engine: String,
)

data class VodVideoLiveness(
    /** Playback generation that owns this renderer heartbeat. */
    val generation: Long,
    /** Monotonic elapsed-realtime timestamp of the latest rendered frame. */
    val lastFrameRealtimeMs: Long,
    /** Monotonically increasing frame token for diagnostics. */
    val frameSequence: Long,
)

data class VodTrack(
    val id: String,
    val type: VodTrackType,
    val label: String,
    val language: String?,
    val roleFlags: Int,
    val selected: Boolean,
    val supported: Boolean,
)

enum class VodTrackType {
    AUDIO,
    SUBTITLE,
}

enum class VodAspectMode {
    FIT,
    FILL,
    ZOOM,
    FIXED_WIDTH,
    FIXED_HEIGHT,
}
