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

    /**
     * Detach the engine's video surface from its current container WITHOUT
     * stopping playback (the stream socket stays open). Paired with [attachVideo]
     * to hand the running engine over to a different container (preview ->
     * fullscreen). No-op by default.
     */
    fun detachVideo() {}

    /**
     * Re-attach the engine's (already created) video surface into [container]
     * without restarting playback. Must be called after [detachVideo]. No-op by
     * default.
     */
    fun attachVideo(container: ViewGroup) {}

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

    /**
     * Current video stream technical info for the diagnostics overlay, or null
     * when nothing is playing yet / the backend can't report it.
     */
    fun getStreamInfo(): StreamInfo? = null

    /** Identifies which backend this is (for UI badges / logging). */
    val engineName: String
}

/**
 * Snapshot of the playing video stream for the on-screen diagnostics overlay.
 * Fields are best-effort: codec/bitrate may be null when a backend can't expose
 * them (e.g. libVLC across versions); 0 / null render as "—" in the UI.
 */
data class StreamInfo(
    val width: Int,
    val height: Int,
    val fps: Float,
    val codec: String?,
    val bitrateKbps: Int?,
    val engine: String
)

/** Playback state callbacks routed to the UI. */
interface PlayerListener {
    fun onBuffering() {}
    fun onPlaying() {}
    /**
     * The engine has produced a real video frame on the current surface (libVLC
     * Vout / ExoPlayer first rendered frame). Distinct from [onPlaying], which can
     * fire on audio/cache state before any picture is visible — used to clear the
     * black gap while a preview->fullscreen surface hand-off warms up.
     */
    fun onVideoOutput() {}
    fun onEnded() {}
    /** A fatal playback error. The controller may trigger fallback/retry. */
    fun onError(message: String?) {}

    /**
     * The media has an audio track but this engine can't decode/output it, so
     * playback is silent even though video may be fine (e.g. AC-3/E-AC-3/MP2 with
     * no MediaCodec audio decoder and passthrough disabled). The controller falls
     * back to libVLC, which decodes these in software to PCM.
     */
    fun onAudioUnavailable() {}

    /**
     * Video is reporting "playing" but the surface is a green/blank/invalid frame
     * (the classic 1080p@50fps high-bitrate H.264 hardware-decoder failure). The
     * controller falls back to a software decode path.
     */
    fun onVideoInvalid() {}

    /**
     * We are software-decoding but the stream is UHD/4K (≥1440p). No TV box CPU can
     * software-decode 4K at 80–100 Mbps in real time, so playback stutters badly.
     * The controller escalates this one stream to the hardware decoder (the only
     * viable 4K path) — the opposite direction of the green fallback.
     */
    fun onSoftwareTooSlow() {}
}
