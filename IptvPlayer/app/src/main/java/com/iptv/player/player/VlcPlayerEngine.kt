/*
 * VlcPlayerEngine.kt
 * Fallback playback backend using libVLC. Chosen when ExoPlayer can't handle a
 * codec/container (e.g. DTS/AC3/EAC3 in TS, AVI).
 *
 * Video output: a single SurfaceView (the VLCVideoLayout default) — no
 * TextureView and no separate subtitle surface. Two surfaces (TextureView +
 * hardware decode, or an extra subtitle surface) were the cause of the green
 * screen and doubled image on low-end Android TV sticks. The video views are
 * attached/detached against the player lifecycle so navigating away never leaves
 * a stale, double-attached surface behind.
 *
 * Decoding: hardware decode auto with a reliable per-stream software fallback.
 * Each stream starts at hardware-auto; if hardware decode errors out or fails to
 * produce any video output (audio-only / green frame), the same stream is
 * restarted in software decode automatically. Only after software also fails is
 * the error surfaced to the controller, so this never fights the Exo->VLC
 * fallback or the controller's retry/backoff.
 */
package com.iptv.player.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import com.iptv.player.util.AppInfo
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VlcPlayerEngine(private val context: Context) : PlayerEngine {

    override val engineName: String = "VLC"

    override val supportsDelay: Boolean = true

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var listener: PlayerListener? = null

    // Stored so they can be re-applied once playback (re)starts.
    private var audioDelayMs = 0L
    private var subtitleDelayMs = 0L

    // Remembered track choices (by libVLC track name / "off"), re-applied on each
    // (re)start once the new stream's tracks are available.
    private var preferredAudioToken: String? = null
    private var preferredSubtitleToken: String? = null

    // Per-stream decode state.
    private var currentUrl: String? = null
    private var softwareDecode = false
    private var sawVout = false
    private var viewsAttached = false

    // Serializes every start/stop/release transition so a fast channel zap or a
    // retry firing mid-switch can never open the new stream before the old one is
    // fully torn down (which would briefly hold two connections to the server).
    private val transitionLock = Any()

    private val watchdog = Handler(Looper.getMainLooper())
    private val voutWatchdog = Runnable {
        // Playback reported Playing but no video surface ever appeared: hardware
        // decode produced audio only (or a blank/green frame). Retry in software.
        if (!sawVout && !softwareDecode) restartSoftware()
    }

    override fun bind(container: ViewGroup) {
        // Known-good v1.0.0 (commit 57e9100) playback set. This is the exact
        // decode configuration that played cleanly on the user's Android TV
        // hardware; the later "no-direct-rendering" / no-frame-drop variants
        // reintroduced the green screen on that box, so we restore these.
        val options = arrayListOf(
            // Bigger live buffer soaks up network hiccups before they freeze.
            "--network-caching=$NETWORK_CACHING_MS",
            "--live-caching=$NETWORK_CACHING_MS",
            // Many IPTV TS streams carry irregular PCR/timestamps; disabling the
            // jitter/synchro guards stops VLC from stalling to "catch up".
            "--clock-jitter=0",
            "--clock-synchro=0",
            // Hardware decode (libVLC degrades to software on its own).
            "--avcodec-hw=any",
            // Cut decode load so cheap TV boxes keep up: skip the deblocking
            // loop filter and allow fast (slightly looser) decoding.
            "--avcodec-skiploopfilter=all",
            "--avcodec-fast",
            // Auto-reconnect when an HTTP segment/stream connection drops.
            "--http-reconnect",
            // Report KULULUPLAY instead of the default "VLC/3.0.x LibVLC/3.0.x".
            "--http-user-agent=${AppInfo.USER_AGENT}"
        )
        val vlc = LibVLC(context, options)
        // Belt-and-braces: also set it on the instance (name + http UA).
        vlc.setUserAgent(AppInfo.USER_AGENT, AppInfo.USER_AGENT)
        val mp = MediaPlayer(vlc)

        val layout = VLCVideoLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(layout)

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    // VLC keeps firing Buffering during normal playback (cache
                    // top-ups), ending each cycle at 100%. Only treat <100% as
                    // actual buffering; 100% means the stream is running, so hide
                    // the spinner — otherwise it stays on screen forever.
                    if (event.buffering < 100f) listener?.onBuffering()
                    else listener?.onPlaying()
                }
                MediaPlayer.Event.Playing -> {
                    // VLC only accepts delays / track changes once the stream is
                    // running and its tracks have been parsed.
                    applyDelays()
                    applyPreferredTracks()
                    listener?.onPlaying()
                    // Start watching for a missing video surface only while on the
                    // hardware-decode attempt.
                    if (!softwareDecode) scheduleVoutWatchdog()
                }
                MediaPlayer.Event.Vout -> onVout(event.voutCount)
                MediaPlayer.Event.EndReached -> listener?.onEnded()
                MediaPlayer.Event.EncounteredError -> {
                    // Hardware decode error: silently retry the same stream in
                    // software before bubbling a real error up to the controller.
                    // Posted off the VLC event thread before touching the player.
                    if (!softwareDecode) watchdog.post { restartSoftware() }
                    else listener?.onError("VLC error")
                }
            }
        }

        libVlc = vlc
        mediaPlayer = mp
        videoLayout = layout
        attachViews()
    }

    override fun play(url: String) {
        currentUrl = url
        softwareDecode = false
        startMedia(url, software = false)
    }

    private fun startMedia(url: String, software: Boolean) {
        synchronized(transitionLock) {
            val vlc = libVlc ?: return
            val mp = mediaPlayer ?: return
            cancelVoutWatchdog()
            sawVout = false
            // Single-connection contract: fully tear down the previous stream
            // before opening the next. stop() closes the prior network socket so
            // the server frees the one allowed slot immediately — without it, a
            // fast zap or the retry path would swap the media URL on a still-
            // connected player and the server would briefly count two active
            // connections ("this account is in use on another device").
            mp.stop()
            // The surface is detached on stop()/background; re-attach before play.
            attachViews()
            val media = Media(vlc, Uri.parse(url)).apply {
                if (software) {
                    // Force pure software decode for this restart.
                    setHWDecoderEnabled(false, false)
                    addOption(":avcodec-hw=none")
                } else {
                    // Prefer hardware decoding; libVLC degrades to software on
                    // failure. Matches the known-good v1.0.0 (57e9100) behavior.
                    setHWDecoderEnabled(true, true)
                }
                // Mirror the instance buffer/jitter tuning at the stream level.
                addOption(":network-caching=$NETWORK_CACHING_MS")
                addOption(":live-caching=$NETWORK_CACHING_MS")
                addOption(":clock-jitter=0")
                addOption(":clock-synchro=0")
                // Auto-reconnect dropped HTTP connections instead of erroring out.
                addOption(":http-reconnect")
                // Per-stream User-Agent override (HTTP(S) playlist + segments).
                addOption(":http-user-agent=${AppInfo.USER_AGENT}")
            }
            mp.media = media
            media.release()
            mp.play()
        }
    }

    private fun restartSoftware() {
        val url = currentUrl ?: return
        softwareDecode = true
        startMedia(url, software = true)
    }

    private fun onVout(count: Int) {
        if (count > 0) {
            sawVout = true
            cancelVoutWatchdog()
        }
    }

    private fun scheduleVoutWatchdog() {
        cancelVoutWatchdog()
        watchdog.postDelayed(voutWatchdog, VOUT_TIMEOUT_MS)
    }

    private fun cancelVoutWatchdog() {
        watchdog.removeCallbacks(voutWatchdog)
    }

    private fun attachViews() {
        if (viewsAttached) return
        val mp = mediaPlayer ?: return
        val layout = videoLayout ?: return
        // 3rd arg false = no separate subtitle surface (subtitles are blended
        // onto the single video surface). 4th arg false = plain SurfaceView, not
        // a TextureView. One surface only -> no doubled/ghosted image.
        mp.attachViews(layout, null, false, false)
        viewsAttached = true
    }

    private fun detachViews() {
        if (!viewsAttached) return
        mediaPlayer?.detachViews()
        viewsAttached = false
    }

    override fun pause() {
        cancelVoutWatchdog()
        mediaPlayer?.pause()
        // Release the surface so backgrounding/navigation can't leave a stale,
        // double-attached surface behind.
        detachViews()
    }

    override fun resume() {
        attachViews()
        mediaPlayer?.play()
    }

    override fun stop() {
        synchronized(transitionLock) {
            cancelVoutWatchdog()
            // stop() closes the network socket so the single connection slot is
            // freed immediately; detach the surface so nothing stale lingers.
            mediaPlayer?.stop()
            detachViews()
        }
    }

    override fun release() {
        synchronized(transitionLock) {
            cancelVoutWatchdog()
            mediaPlayer?.setEventListener(null)
            mediaPlayer?.stop()
            detachViews()
            mediaPlayer?.release()
            libVlc?.release()
            mediaPlayer = null
            libVlc = null
            videoLayout = null
            listener = null
            currentUrl = null
        }
    }

    override fun setListener(listener: PlayerListener?) {
        this.listener = listener
    }

    // VLC delays are expressed in microseconds.
    override fun setAudioDelayMs(ms: Long) {
        audioDelayMs = ms
        mediaPlayer?.audioDelay = ms * 1000
    }

    override fun setSubtitleDelayMs(ms: Long) {
        subtitleDelayMs = ms
        mediaPlayer?.spuDelay = ms * 1000
    }

    private fun applyDelays() {
        mediaPlayer?.let {
            it.audioDelay = audioDelayMs * 1000
            it.spuDelay = subtitleDelayMs * 1000
        }
    }

    // ---- Track selection -------------------------------------------------

    override fun availableAudioTracks(): List<TrackOption> {
        val mp = mediaPlayer ?: return emptyList()
        val current = mp.audioTrack
        return mp.audioTracks
            ?.filter { it.id >= 0 }
            ?.map { TrackOption(token = it.name, label = it.name, selected = it.id == current) }
            ?: emptyList()
    }

    override fun availableSubtitleTracks(): List<TrackOption> {
        val mp = mediaPlayer ?: return emptyList()
        val current = mp.spuTrack
        return mp.spuTracks
            ?.filter { it.id >= 0 }
            ?.map { TrackOption(token = it.name, label = it.name, selected = it.id == current) }
            ?: emptyList()
    }

    override fun selectAudioTrack(token: String) {
        preferredAudioToken = token
        applyPreferredAudio()
    }

    override fun selectSubtitleTrack(token: String) {
        preferredSubtitleToken = token
        applyPreferredSubtitle()
    }

    private fun applyPreferredTracks() {
        applyPreferredAudio()
        applyPreferredSubtitle()
    }

    private fun applyPreferredAudio() {
        val mp = mediaPlayer ?: return
        val token = preferredAudioToken ?: return
        val match = mp.audioTracks?.firstOrNull { it.name == token } ?: return
        mp.audioTrack = match.id
    }

    private fun applyPreferredSubtitle() {
        val mp = mediaPlayer ?: return
        when (val token = preferredSubtitleToken) {
            null -> return
            PlayerEngine.SUBTITLE_OFF -> mp.spuTrack = -1
            else -> mp.spuTracks?.firstOrNull { it.name == token }?.let { mp.spuTrack = it.id }
        }
    }

    companion object {
        /**
         * Live/network buffer in ms. Restored to the known-good v1.0.0 (57e9100)
         * value: a larger buffer trades a little extra zap/startup latency for far
         * fewer stalls and freezes on jittery IPTV connections.
         */
        private const val NETWORK_CACHING_MS = 3000

        /**
         * How long after the Playing event we wait for a video surface before
         * assuming hardware decode failed silently and restarting in software.
         */
        private const val VOUT_TIMEOUT_MS = 6000L
    }
}
