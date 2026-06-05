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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.databinding.ActivityPlayerBinding
import com.iptv.player.player.PlayerController
import com.iptv.player.ui.common.LogoPlaceholder
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity(), PlayerController.Callback {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            KeyEvent.KEYCODE_DPAD_UP -> {
                viewModel.previous()?.let { startChannel(it) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                viewModel.next()?.let { startChannel(it) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (okDownTime == 0L) okDownTime = event.eventTime
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

    // ---- Overlay --------------------------------------------------------

    private fun showOverlay(channel: Channel) {
        binding.overlayName.text = channel.name
        binding.overlayNumber.text = channel.number?.toString() ?: ""
        binding.overlayCategory.text = channel.categoryName ?: ""
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
        binding.engineBadge.text = engineName
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
        overlayHandler.removeCallbacksAndMessages(null)
        if (::controller.isInitialized) controller.release()
    }
}
