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
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import coil.load
import com.iptv.player.R
import com.iptv.player.cast.CastController
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.databinding.ActivityPlayerBinding
import com.iptv.player.player.PlayerController
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.SleepTimer
import com.iptv.player.ui.guide.NowNextBinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerActivity : BaseActivity(), PlayerController.Callback {

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        private const val OVERLAY_TIMEOUT = 4000L
        private const val LONG_PRESS_MS = 600L
    }

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(this)[PlayerViewModel::class.java]
    }

    private lateinit var controller: PlayerController
    private var currentChannel: Channel? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var okDownTime = 0L
    private var epgJob: Job? = null

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

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        if (channelId == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            val mode: PlayerMode = viewModel.playerMode()
            controller = PlayerController(
                context = this@PlayerActivity,
                container = binding.videoContainer,
                mode = mode,
                callback = this@PlayerActivity
            )
            val channel = viewModel.resolveChannel(channelId)
            if (channel == null) {
                Toast.makeText(this@PlayerActivity, R.string.error_unknown, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            startChannel(channel)
        }
    }

    private fun startChannel(channel: Channel) {
        currentChannel = channel
        controller.play(channel.streamUrl)
        showOverlay(channel)
    }

    // ---- D-pad handling -------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                // CH+ / UP -> next channel.
                viewModel.next()?.let { startChannel(it) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                // CH- / DOWN -> previous channel.
                viewModel.previous()?.let { startChannel(it) }
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
        // First press closes the overlay; second exits.
        if (binding.infoOverlay.visibility == View.VISIBLE) {
            hideOverlay()
        } else {
            super.onBackPressed()
        }
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

        AlertDialog.Builder(this)
            .setTitle(R.string.player_menu)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun showSleepDialog() {
        val options = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = options.map { min ->
            if (min == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_minutes, min)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(labels) { _, which ->
                val min = options[which]
                sleepTimer.set(min)
                val msg = if (min == 0) getString(R.string.sleep_timer_off)
                else getString(R.string.sleep_timer_set, min)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDelayDialog(audio: Boolean) {
        if (!::controller.isInitialized) return
        val steps = (-2000..2000 step 250).toList()
        val current = if (audio) controller.currentAudioDelayMs else controller.currentSubtitleDelayMs
        val labels = steps.map { getString(R.string.delay_ms, it) }.toTypedArray()
        val checked = steps.indexOf(current.toInt()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(if (audio) R.string.audio_delay else R.string.subtitle_delay)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val ms = steps[which].toLong()
                if (audio) controller.setAudioDelay(ms) else controller.setSubtitleDelay(ms)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
     * Fills the now/next rows for the current channel. Cancels any in-flight load
     * first so rapid zapping can't leave a previous channel's EPG on screen.
     */
    private fun loadEpg(channel: Channel) {
        epgJob?.cancel()
        // Clear + hide immediately so a late callback from the previous channel
        // can never flash stale program text while the new load is in flight.
        binding.overlayNow.text = ""
        binding.overlayNext.text = ""
        binding.overlayNow.visibility = View.GONE
        binding.overlayNext.visibility = View.GONE
        val job = NowNextBinder.bind(
            scope = lifecycleScope,
            channel = channel,
            nowView = binding.overlayNow,
            nextView = binding.overlayNext
        )
        epgJob = job
        // Reveal each row only once it actually has text (binder fills async).
        // Gate on job identity + channel so a stale completion can't re-show.
        job.invokeOnCompletion {
            binding.overlayNow.post {
                if (epgJob !== job || currentChannel?.id != channel.id) return@post
                binding.overlayNow.visibility =
                    if (binding.overlayNow.text.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.overlayNext.visibility =
                    if (binding.overlayNext.text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun hideOverlay() {
        binding.infoOverlay.visibility = View.GONE
        overlayHandler.removeCallbacksAndMessages(null)
    }

    // ---- Controller callbacks ------------------------------------------

    override fun onBuffering() {
        binding.bufferingIndicator.visibility = View.VISIBLE
    }

    override fun onPlaying(engineName: String) {
        binding.bufferingIndicator.visibility = View.GONE
    }

    override fun onRetrying(attempt: Int) {
        binding.bufferingIndicator.visibility = View.VISIBLE
        Toast.makeText(this, R.string.error_stream_not_responding, Toast.LENGTH_SHORT).show()
    }

    override fun onFatalError() {
        binding.bufferingIndicator.visibility = View.GONE
        Toast.makeText(this, R.string.error_cannot_connect, Toast.LENGTH_LONG).show()
    }

    // ---- Lifecycle ------------------------------------------------------

    override fun onStop() {
        super.onStop()
        if (::controller.isInitialized) controller.pause()
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
        overlayHandler.removeCallbacksAndMessages(null)
        if (::controller.isInitialized) controller.release()
    }
}
