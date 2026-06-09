/*
 * PlayerActivity.kt
 * Full-screen playback with D-pad controls:
 *   UP / DOWN   -> next / previous channel (zapping)
 *   OK (center) -> show channel info overlay briefly
 *   LONG-press OK -> toggle favorite
 *   BACK        -> close overlay first, then exit
 * Uses PlayerController which runs ExoPlayer first and falls back to libVLC.
 */
package com.iptv.player.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.iptv.player.R
import com.iptv.player.cast.CastController
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.NowNext
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.databinding.ActivityPlayerBinding
import com.iptv.player.player.PlayerController
import com.iptv.player.player.StreamInfo
import com.iptv.player.util.DebugOverlayBinder
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.SleepTimer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class PlayerActivity : BaseActivity(), PlayerController.Callback {

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        // Kept for caller compatibility (Home/Favorites pass a category to scope
        // channel zapping); this restored player zaps the full live list and
        // simply ignores the extra.
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        /** When true, up/down zapping stays within the radio list, not Live TV. */
        const val EXTRA_RADIO_MODE = "extra_radio_mode"
        /**
         * When true, adopt the live preview's already-playing PlayerController
         * (parked on ServiceLocator) instead of creating a fresh one — going
         * fullscreen then continues the picture with no reconnect/re-buffer.
         */
        const val EXTRA_ADOPT_PREVIEW = "extra_adopt_preview"
        private const val OVERLAY_TIMEOUT = 4000L
        private const val LONG_PRESS_MS = 600L
        /**
         * Settle window for CH+/CH- zapping. Each press advances the channel and
         * updates the overlay instantly, but the actual stream only starts after the
         * user pauses this long — so rapid taps don't churn mp.stop()/mp.play() on
         * the engine (the libVLC/Amlogic stop-restart overlap that froze the picture).
         */
        private const val ZAP_DEBOUNCE_MS = 280L
        // Safety cap for the preview->fullscreen black-gap cover, in case the
        // engine never emits a fresh video-output event after the surface swap.
        private const val HANDOFF_COVER_TIMEOUT_MS = 6000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(this)[PlayerViewModel::class.java]
    }

    private lateinit var controller: PlayerController
    private var debugBinder: DebugOverlayBinder? = null
    private var currentChannel: Channel? = null
    // Debounced CH+/CH- zapping: the target channel the user is scrolling toward,
    // started only once they settle (see requestZap / ZAP_DEBOUNCE_MS).
    private val zapHandler = Handler(Looper.getMainLooper())
    private var pendingZapChannel: Channel? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val handoffCoverHandler = Handler(Looper.getMainLooper())
    private var okDownTime = 0L
    private var epgJob: Job? = null

    /** True when this player adopted the live preview's controller (came from Home). */
    private var adoptedPreview = false

    /**
     * True once we've parked the controller for Home to re-adopt on BACK (the
     * reverse hand-off), so onStop/onDestroy must NOT pause or release it — Home
     * now owns the single live connection and keeps the picture going.
     */
    private var handingBack = false

    /** User's preferred live container; applied to the URL at play time. */
    private var streamFormat: StreamFormat = StreamFormat.TS

    /** Diagnostics overlay state (toggled via the menu / INFO key). */
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsVisible = false

    private val sleepTimer = SleepTimer { finish() }
    private val castController by lazy {
        CastController(this) {
            val channel = currentChannel ?: return@CastController null
            CastController.CastMedia(channel.streamUrl, channel.name, channel.logoUrl, isLive = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        castController.attach()

        // Manual retry after the reconnect window expired: re-open the same
        // channel and reset the reconnect state.
        binding.retryButton.setOnClickListener {
            binding.errorOverlay.visibility = View.GONE
            binding.bufferingLabel.setText(R.string.buffering)
            binding.bufferingIndicator.visibility = View.VISIBLE
            if (::controller.isInitialized) controller.retry()
        }

        // On-screen Debug overlay (engine/stage + live PlaybackLog tail), gated by
        // the Debug setting; separate from the MENU-toggled stats overlay.
        debugBinder = DebugOverlayBinder(binding.debugOverlay) {
            if (::controller.isInitialized) controller.streamInfo() else null
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    ServiceLocator.settings.debugOverlay.collectLatest { debugBinder?.setEnabled(it) }
                } finally {
                    // Leaving STARTED: drop the listener + timer so a backgrounded
                    // player doesn't keep rendering the overlay off-screen.
                    debugBinder?.setEnabled(false)
                }
            }
        }

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        if (channelId == null) {
            finish()
            return
        }
        viewModel.radioMode = intent.getBooleanExtra(EXTRA_RADIO_MODE, false)

        // Take the handed-over preview controller (if any) immediately so it is
        // never left parked. Null when launched normally (Favorites/number-zap).
        val adopted: PlayerController? =
            if (intent.getBooleanExtra(EXTRA_ADOPT_PREVIEW, false))
                ServiceLocator.consumePendingLiveController() else null

        lifecycleScope.launch {
            streamFormat = viewModel.streamFormat()
            val channel = viewModel.resolveChannel(channelId)
            if (channel == null) {
                // Release the adopted connection so the hand-off can't orphan it.
                adopted?.release()
                Toast.makeText(this@PlayerActivity, R.string.error_unknown, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            if (adopted != null) {
                // Seamless hand-off: rebind the still-playing engine to this
                // screen's surface and take ownership — no new play()/reconnect.
                adoptedPreview = true
                controller = adopted
                controller.setCallback(this@PlayerActivity)
                // The hand-off tears down and recreates the video surface, so the
                // picture goes black for a beat (audio keeps playing) until the new
                // surface gets its first frame. Cover that gap with the buffering
                // indicator; onVideoResumed() clears it, with a safety timeout.
                binding.bufferingIndicator.visibility = View.VISIBLE
                handoffCoverHandler.postDelayed(
                    { binding.bufferingIndicator.visibility = View.GONE },
                    HANDOFF_COVER_TIMEOUT_MS
                )
                controller.rebind(binding.videoContainer)
                currentChannel = channel
                showOverlay(channel)
                if (statsVisible) refreshStats()
            } else {
                val mode: PlayerMode = viewModel.playerMode()
                controller = PlayerController(
                    context = this@PlayerActivity,
                    container = binding.videoContainer,
                    mode = mode,
                    decoderMode = viewModel.decoderMode(),
                    allowPassthrough = viewModel.audioPassthrough(),
                    bufferMode = viewModel.bufferMode(),
                    callback = this@PlayerActivity
                )
                startChannel(channel)
            }
        }
    }

    /**
     * Handle one CH+/CH- press: advance the channel pointer and update the overlay
     * instantly so scrolling feels responsive, but defer the actual stream start
     * until the user settles for [ZAP_DEBOUNCE_MS]. This collapses a burst of taps
     * into a single [startChannel], avoiding the rapid stop/restart that intermittently
     * froze the libVLC decoder on Amlogic boxes.
     */
    private fun requestZap(delta: Int) {
        val target = (if (delta > 0) viewModel.next() else viewModel.previous()) ?: return
        pendingZapChannel = target
        previewZapOverlay(target)
        zapHandler.removeCallbacksAndMessages(null)
        zapHandler.postDelayed({
            // Guard the lifecycle: don't start a stream if we've left the foreground
            // or are tearing down between the press and this settle.
            if (isFinishing || isDestroyed || handingBack) return@postDelayed
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@postDelayed
            val ch = pendingZapChannel ?: return@postDelayed
            pendingZapChannel = null
            if (ch.id != currentChannel?.id) startChannel(ch)
        }, ZAP_DEBOUNCE_MS)
    }

    /**
     * Lightweight overlay update shown while the user is still scrolling channels:
     * the name/number/category and a placeholder logo, with EPG cleared so no stale
     * programme text lingers. The real logo + EPG load only on settle via [showOverlay].
     */
    private fun previewZapOverlay(channel: Channel) {
        binding.overlayName.text = channel.name
        binding.overlayNumber.text = channel.number?.toString() ?: ""
        binding.overlayCategory.text = channel.categoryName ?: ""
        binding.overlayLogo.setImageDrawable(LogoPlaceholder.forName(this, channel.name))
        epgJob?.cancel()
        clearEpg()
        binding.infoOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({ hideOverlay() }, OVERLAY_TIMEOUT)
    }

    private fun startChannel(channel: Channel) {
        currentChannel = channel
        // A new channel cancels any reconnect-failed state from the previous one.
        binding.errorOverlay.visibility = View.GONE
        controller.play(applyStreamFormat(channel.streamUrl))
        showOverlay(channel)
        if (statsVisible) refreshStats()
    }

    /**
     * Rewrite a live channel URL to the user's preferred container. Xtream live
     * URLs are baked with `.ts` at sync time; swap only that known live extension
     * (or an existing `.m3u8`) on the path so HLS users hit the `.m3u8` path. Any
     * `?query`/`#fragment` (CDN tokens etc.) is split off first so it's preserved,
     * and anything that isn't a recognised live URL (VOD/series real containers)
     * is left untouched.
     */
    private fun applyStreamFormat(url: String): String {
        val ext = streamFormat.extension
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        val path = if (cut >= 0) url.substring(0, cut) else url
        val suffix = if (cut >= 0) url.substring(cut) else ""
        val lower = path.lowercase(Locale.US)
        val base = when {
            lower.endsWith(".ts") -> path.dropLast(3)
            lower.endsWith(".m3u8") -> path.dropLast(5)
            else -> return url
        }
        return "$base.$ext$suffix"
    }

    // ---- D-pad handling -------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                // CH+ / UP -> next channel. Ignore auto-repeat (held key) so a long
                // press doesn't runaway-zap; discrete taps are collapsed by requestZap.
                if (event.repeatCount == 0) requestZap(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                // CH- / DOWN -> previous channel (same auto-repeat/debounce handling).
                if (event.repeatCount == 0) requestZap(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (okDownTime == 0L) okDownTime = event.eventTime
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                showPlayerMenu()
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                toggleStats()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val held = event.eventTime - okDownTime
            okDownTime = 0L
            if (held >= LONG_PRESS_MS) {
                // Long-press OK -> toggle favorite.
                currentChannel?.let {
                    viewModel.toggleFavorite(it.id)
                    val newState = !it.isFavorite
                    currentChannel = it.copy(isFavorite = newState)
                    Toast.makeText(
                        this,
                        if (newState) R.string.added_to_favorites else R.string.removed_from_favorites,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                // Short OK -> toggle info overlay.
                if (binding.infoOverlay.visibility == View.VISIBLE) hideOverlay()
                else currentChannel?.let { showOverlay(it) }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onBackPressed() {
        // Back peels overlays one at a time: stats first, then info, then exit.
        when {
            statsVisible -> hideStats()
            binding.infoOverlay.visibility == View.VISIBLE -> hideOverlay()
            else -> {
                handBackPreviewIfPossible()
                super.onBackPressed()
            }
        }
    }

    /**
     * Reverse hand-off: when leaving a player that adopted the live preview, park
     * the still-playing controller (plus the current channel id) so Home re-adopts
     * it into the preview surface instead of a dead/closed preview. onStop/onDestroy
     * then skip pause/release because Home now owns the single live connection.
     * Only the BACK exit hands back; sleep-timer/error exits fall through to release.
     */
    private fun handBackPreviewIfPossible() {
        if (!adoptedPreview || !::controller.isInitialized) return
        // Drop any pending debounced zap so it can't start a stream on the
        // controller after we've handed it back to Home.
        zapHandler.removeCallbacksAndMessages(null)
        pendingZapChannel = null
        handingBack = true
        ServiceLocator.handOverLiveController(controller, currentChannel?.id)
    }

    // ---- Player menu (MENU key) ----------------------------------------

    private fun showPlayerMenu() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add(getString(R.string.sleep_timer))
        actions.add { showSleepDialog() }

        if (::controller.isInitialized && controller.supportsDelay) {
            labels.add(getString(R.string.audio_delay))
            actions.add { showDelayDialog(audio = true) }
            labels.add(getString(R.string.subtitle_delay))
            actions.add { showDelayDialog(audio = false) }
        }

        if (castController.isAvailable) {
            labels.add(getString(R.string.cast))
            actions.add { castController.onCastButtonClicked() }
        }

        labels.add(getString(R.string.stream_info))
        actions.add { toggleStats() }

        PlayerDialogs.showOptions(
            this,
            getString(R.string.player_menu),
            labels.map { PlayerDialogs.Option(it) },
        ) { which -> actions[which].invoke() }
    }

    private fun showSleepDialog() {
        val options = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = options.map { min ->
            if (min == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_minutes, min)
        }
        PlayerDialogs.showOptions(
            this,
            getString(R.string.sleep_timer),
            labels.map { PlayerDialogs.Option(it) },
        ) { which ->
            val min = options[which]
            sleepTimer.set(min)
            val msg = if (min == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_set, min)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDelayDialog(audio: Boolean) {
        if (!::controller.isInitialized) return
        val steps = (-2000..2000 step 250).toList()
        val current = if (audio) controller.currentAudioDelayMs else controller.currentSubtitleDelayMs
        val checked = steps.indexOf(current.toInt()).coerceAtLeast(0)
        val titleRes = if (audio) R.string.audio_delay else R.string.subtitle_delay
        val options = steps.mapIndexed { i, step ->
            PlayerDialogs.Option(getString(R.string.delay_ms, step), i == checked)
        }
        PlayerDialogs.showOptions(this, getString(titleRes), options) { which ->
            val ms = steps[which].toLong()
            if (audio) controller.setAudioDelay(ms) else controller.setSubtitleDelay(ms)
        }
    }

    // ---- Overlay --------------------------------------------------------

    private fun showOverlay(channel: Channel) {
        binding.overlayName.text = channel.name
        binding.overlayNumber.text = channel.number?.toString() ?: ""
        binding.overlayCategory.text = channel.categoryName ?: ""
        loadEpg(channel)
        val placeholder = LogoPlaceholder.forName(this, channel.name)
        if (channel.logoUrl.isNullOrBlank()) {
            binding.overlayLogo.setImageDrawable(placeholder)
        } else {
            binding.overlayLogo.load(channel.logoUrl) {
                placeholder(placeholder); error(placeholder)
            }
        }
        binding.infoOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({ hideOverlay() }, OVERLAY_TIMEOUT)
    }

    /**
     * Loads and renders a professional EPG block for the current channel: the now
     * playing title with a progress bar (how far the programme has aired), its
     * start / end times and minutes left, plus the up-next title with its start
     * time. Cancels any in-flight load first so rapid zapping can't leave a
     * previous channel's EPG on screen.
     */
    private fun loadEpg(channel: Channel) {
        epgJob?.cancel()
        // Clear + hide immediately so a late callback can never flash stale text.
        clearEpg()
        epgJob = lifecycleScope.launch {
            val nn = ServiceLocator.repository.getNowNext(channel)
            // Ignore a result that arrived after the user already zapped away.
            if (currentChannel?.id != channel.id) return@launch
            bindEpg(nn)
        }
    }

    /** Renders the now/next pair into the overlay. Runs on the main thread. */
    private fun bindEpg(nn: NowNext) {
        val now = nn.now
        if (now != null) {
            val clock = now.startMs..now.stopMs
            val current = System.currentTimeMillis().coerceIn(clock)
            binding.overlayNow.text =
                getString(R.string.now_playing) + ": " + now.title
            binding.overlayStart.text = formatClock(now.startMs)
            binding.overlayEnd.text = formatClock(now.stopMs)
            binding.epgProgress.progress = (now.progressAt(current) * 100).toInt()

            val minsLeft = ((now.stopMs - current) / 60_000L).toInt()
            binding.overlayRemain.text =
                if (minsLeft <= 0) getString(R.string.epg_ending)
                else getString(R.string.epg_min_left, minsLeft)

            binding.overlayNow.visibility = View.VISIBLE
            binding.epgProgress.visibility = View.VISIBLE
            binding.epgTimes.visibility = View.VISIBLE
        }

        val next = nn.next
        if (next != null) {
            binding.overlayNext.text = getString(R.string.up_next) +
                ": " + next.title + "  (" + formatClock(next.startMs) + ")"
            binding.overlayNext.visibility = View.VISIBLE
        }
    }

    /** Hides + clears every EPG view so no stale program text can show. */
    private fun clearEpg() {
        binding.overlayNow.text = ""
        binding.overlayNext.text = ""
        binding.overlayNow.visibility = View.GONE
        binding.overlayNext.visibility = View.GONE
        binding.epgProgress.visibility = View.GONE
        binding.epgTimes.visibility = View.GONE
    }

    /** Locale-aware, 12/24h-respecting clock time (e.g. 20:00 / 8:00 PM). */
    private fun formatClock(ms: Long): String =
        android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(ms))

    private fun hideOverlay() {
        binding.infoOverlay.visibility = View.GONE
        overlayHandler.removeCallbacksAndMessages(null)
    }

    // ---- Controller callbacks ------------------------------------------

    override fun onBuffering() {
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingLabel.setText(R.string.buffering)
        binding.bufferingIndicator.visibility = View.VISIBLE
    }

    override fun onPlaying(engineName: String) {
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingIndicator.visibility = View.GONE
    }

    override fun onVideoResumed() {
        // First real frame on the (re)attached surface — clear the hand-off cover
        // and any reconnect indicator the moment playback genuinely resumes.
        handoffCoverHandler.removeCallbacksAndMessages(null)
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingIndicator.visibility = View.GONE
    }

    override fun onRetrying(attempt: Int) {
        // Non-blocking "stream not responding, retrying…" indicator over the
        // picture while the controller re-opens the same channel. Cleared
        // automatically by onPlaying / onVideoResumed once playback resumes.
        binding.errorOverlay.visibility = View.GONE
        binding.bufferingLabel.setText(R.string.error_stream_not_responding)
        binding.bufferingIndicator.visibility = View.VISIBLE
    }

    override fun onFatalError() {
        // Reconnect window elapsed with no recovery: replace the spinner with a
        // clear message and a focusable manual-retry button.
        binding.bufferingIndicator.visibility = View.GONE
        binding.errorMessage.setText(R.string.error_cannot_connect)
        binding.errorOverlay.visibility = View.VISIBLE
        binding.retryButton.requestFocus()
    }

    // ---- Lifecycle ------------------------------------------------------

    override fun onStop() {
        super.onStop()
        // Drop any pending debounced zap so it can't start a stream after we've
        // backgrounded (it would re-open playback while not visible).
        zapHandler.removeCallbacksAndMessages(null)
        pendingZapChannel = null
        // Don't pause when handing the controller back to Home — it must keep
        // playing into the preview surface that Home re-adopted.
        if (!handingBack && ::controller.isInitialized) controller.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::controller.isInitialized) controller.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        epgJob?.cancel()
        sleepTimer.release()
        castController.detach()
        zapHandler.removeCallbacksAndMessages(null)
        overlayHandler.removeCallbacksAndMessages(null)
        handoffCoverHandler.removeCallbacksAndMessages(null)
        statsHandler.removeCallbacksAndMessages(null)
        debugBinder?.release()
        // Don't release a controller we've handed back to Home — it owns it now.
        if (!handingBack && ::controller.isInitialized) controller.release()
    }

    // ---- Diagnostics overlay -------------------------------------------

    private fun toggleStats() {
        if (statsVisible) hideStats() else showStats()
    }

    private fun showStats() {
        statsVisible = true
        binding.statsOverlay.visibility = View.VISIBLE
        refreshStats()
    }

    private fun hideStats() {
        statsVisible = false
        statsHandler.removeCallbacksAndMessages(null)
        binding.statsOverlay.visibility = View.GONE
    }

    /** Repaint the overlay every second so resolution/bitrate stay current. */
    private fun refreshStats() {
        if (!statsVisible || !::controller.isInitialized) return
        binding.statsOverlay.text = formatStats(controller.streamInfo())
        statsHandler.removeCallbacksAndMessages(null)
        statsHandler.postDelayed({ refreshStats() }, STATS_REFRESH_MS)
    }

    private fun formatStats(info: StreamInfo?): String {
        if (info == null) return getString(R.string.stream_info_unavailable)
        val res = if (info.width > 0 && info.height > 0) "${info.width}×${info.height}" else DASH
        val fps = if (info.fps > 0f) String.format(Locale.US, "%.0f", info.fps) else DASH
        val bitrate = info.bitrateKbps?.let { "$it kbps" } ?: DASH
        return buildString {
            append(getString(R.string.stream_info)).append('\n')
            append(getString(R.string.stream_info_engine, info.engine)).append('\n')
            append(getString(R.string.stream_info_resolution, res)).append('\n')
            append(getString(R.string.stream_info_fps, fps)).append('\n')
            append(getString(R.string.stream_info_codec, info.codec ?: DASH)).append('\n')
            append(getString(R.string.stream_info_bitrate, bitrate))
        }
    }
}

private const val STATS_REFRESH_MS = 1000L
private const val DASH = "—"
