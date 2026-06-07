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
        // Conservative, known-good live playback set. An earlier "tuning" pass
        // added decode shortcuts (--avcodec-skiploopfilter=all / --avcodec-fast)
        // and disabled the clock jitter/synchro guards; together they produced
        // green/blocky frames and stuttering on weaker Android TV hardware, so
        // they are intentionally NOT used here. Re-tune one option at a time.
        val options = arrayListOf(
            // Low-latency-but-stable live buffer: small enough to keep zapping
            // responsive, big enough to ride out normal network jitter.
            "--network-caching=$NETWORK_CACHING_MS",
            "--live-caching=$NETWORK_CACHING_MS",
            // Decode and render every frame in order — no frame dropping/skipping
            // and full deblocking. This is the pre-regression behavior that
            // played cleanly, without green artifacts or visible corruption.
            "--no-drop-late-frames",
            "--no-skip-frames",
            // Hardware decode, automatic: libVLC picks MediaCodec when usable and
            // degrades to software on its own; our watchdog adds a per-stream
            // software restart on top for panels that fail silently.
            "--avcodec-hw=any",
            // Disable hardware "direct rendering": several Android TV panels show
            // a solid green picture (audio fine) when the MediaCodec/OMX decoder
            // pushes frames straight to the surface. Copying frames out first
            // keeps hardware decode but renders correctly on a plain SurfaceView.
            "--no-mediacodec-dr",
            "--no-omxil-dr",
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
                    // Hardware decode, automatic (libVLC may fall back internally).
                    setHWDecoderEnabled(true, false)
                }
                // Mirror the instance buffer settings at the stream level.
                addOption(":network-caching=$NETWORK_CACHING_MS")
                addOption(":live-caching=$NETWORK_CACHING_MS")
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
         * Live/network buffer in ms. Kept low (~1.5 s) so channel zapping stays
         * responsive while still riding out normal IPTV jitter; combined with
         * --http-reconnect this auto-recovers from brief drops without stalling.
         */
        private const val NETWORK_CACHING_MS = 1500

        /**
         * How long after the Playing event we wait for a video surface before
         * assuming hardware decode failed silently and restarting in software.
         */
        private const val VOUT_TIMEOUT_MS = 6000L
    }
}
