/*
 * VodPlayerActivity.kt
 * On-demand player for movies and series episodes, built directly on Media3
 * ExoPlayer (matching the app's media3 1.4.1 deps). Provides:
 *   - play/pause, a seekbar with current/total time
 *   - +10s / -10s skipping
 *   - audio-track and subtitle-track selection menus
 *   - aspect-ratio cycling (AspectRatio.next())
 *   - resume support: prompts to continue from a saved position and periodically
 *     persists progress via the repository.
 * Receives EXTRA_STREAM_URL, EXTRA_TITLE and EXTRA_RESUME_ID.
 */
package com.iptv.player.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.AspectRatio
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.databinding.ActivityVodPlayerBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VodPlayerActivity : BaseActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_RESUME_ID = "extra_resume_id"
        private const val SKIP_MS = 10_000L
        private const val SAVE_INTERVAL_MS = 10_000L
    }

    private lateinit var binding: ActivityVodPlayerBinding
    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var streamUrl: String? = null
    private var resumeId: String? = null

    private var aspect: AspectRatio = AspectRatio.ORIGINAL
    private var userSeeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }
    private val saveRunnable = object : Runnable {
        override fun run() {
            persistResume()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        resumeId = intent.getStringExtra(EXTRA_RESUME_ID)
        binding.titleText.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        if (streamUrl.isNullOrBlank()) {
            finish()
            return
        }

        setupControls()
        initPlayer()
    }

    private fun setupControls() {
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.skipForwardButton.setOnClickListener { seekBy(SKIP_MS) }
        binding.skipBackButton.setOnClickListener { seekBy(-SKIP_MS) }
        binding.audioButton.setOnClickListener { showTrackMenu(C.TRACK_TYPE_AUDIO) }
        binding.subtitleButton.setOnClickListener { showTrackMenu(C.TRACK_TYPE_TEXT) }
        binding.aspectButton.setOnClickListener { cycleAspect() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                player?.seekTo(sb?.progress?.toLong() ?: 0L)
            }
        })
    }

    private fun initPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        val view = PlayerView(this).apply {
            useController = false
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            this.player = exo
        }
        binding.videoContainer.addView(view)
        player = exo
        playerView = view

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.bufferingIndicator.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) updateDuration()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.playPauseButton.setText(
                    if (isPlaying) R.string.player_pause else R.string.detail_play
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.bufferingIndicator.visibility = View.GONE
            }
        })

        lifecycleScope.launch {
            aspect = settings.aspectRatio.first()
            applyAspect()
            val saved = resumeId?.let { repo.getResume(it) } ?: 0L
            if (saved > 0L) promptResume(saved) else preparePlayback(0L)
        }
    }

    private fun promptResume(positionMs: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.resume_prompt_title)
            .setMessage(R.string.resume_prompt_message)
            .setPositiveButton(R.string.resume_continue) { _, _ -> preparePlayback(positionMs) }
            .setNegativeButton(R.string.resume_from_start) { _, _ -> preparePlayback(0L) }
            .setCancelable(false)
            .show()
    }

    private fun preparePlayback(positionMs: Long) {
        val exo = player ?: return
        val url = streamUrl ?: return
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        if (positionMs > 0L) exo.seekTo(positionMs)
        exo.playWhenReady = true
        startTimers()
    }

    private fun startTimers() {
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.post(progressRunnable)
        handler.postDelayed(saveRunnable, SAVE_INTERVAL_MS)
    }

    // ---- Controls -------------------------------------------------------

    private fun togglePlayPause() {
        val exo = player ?: return
        exo.playWhenReady = !exo.playWhenReady
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, exo.duration.coerceAtLeast(0L))
        exo.seekTo(target)
    }

    private fun cycleAspect() {
        aspect = aspect.next()
        applyAspect()
        lifecycleScope.launch { settings.setAspectRatio(aspect) }
    }

    private fun applyAspect() {
        val view = playerView ?: return
        view.resizeMode = when (aspect) {
            AspectRatio.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatio.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatio.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            AspectRatio.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatio.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    // ---- Track selection ------------------------------------------------

    private fun showTrackMenu(trackType: Int) {
        val exo = player ?: return
        val tracks = exo.currentTracks
        val groups = tracks.groups.filter { it.type == trackType && it.isSupported }

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add(getString(R.string.subtitles_off))
            actions.add {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }

        groups.forEachIndexed { gIndex, group ->
            val mediaGroup: TrackGroup = group.mediaTrackGroup
            for (i in 0 until mediaGroup.length) {
                val format = mediaGroup.getFormat(i)
                val lang = format.language ?: format.label ?: "${gIndex + 1}.${i + 1}"
                labels.add(lang)
                actions.add {
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(trackType, false)
                        .setOverrideForType(TrackSelectionOverride(mediaGroup, i))
                        .build()
                }
            }
        }

        if (labels.isEmpty()) return

        val titleRes = if (trackType == C.TRACK_TYPE_AUDIO) R.string.audio_track else R.string.subtitle_track
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    // ---- Progress / resume ---------------------------------------------

    private fun updateDuration() {
        val exo = player ?: return
        val duration = exo.duration.coerceAtLeast(0L)
        binding.seekBar.max = duration.toInt()
        binding.totalTime.text = formatTime(duration)
    }

    private fun updateProgress() {
        val exo = player ?: return
        if (userSeeking) return
        val position = exo.currentPosition.coerceAtLeast(0L)
        binding.seekBar.progress = position.toInt()
        binding.currentTime.text = formatTime(position)
    }

    private fun persistResume() {
        val exo = player ?: return
        val id = resumeId ?: return
        val position = exo.currentPosition
        val duration = exo.duration.coerceAtLeast(0L)
        if (duration <= 0L) return
        lifecycleScope.launch { repo.saveResume(id, position, duration) }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    // ---- Lifecycle ------------------------------------------------------

    override fun onStop() {
        super.onStop()
        persistResume()
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        playerView?.player = null
        playerView = null
    }
}
