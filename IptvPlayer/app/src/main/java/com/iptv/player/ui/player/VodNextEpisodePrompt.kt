/*
 * VodNextEpisodePrompt.kt
 *
 * Architecture note (VOD player decomposition):
 *   Owns the "next episode" overlay of VodPlayerActivity: the one-shot
 *   [VodNextEpisodeGate], the pending episode, the countdown runnable and the
 *   show/dismiss view choreography. Playback continuation (startEpisode) and the
 *   completion/foreground guards stay in the Activity, which reaches back through
 *   [Host]. [VodNextEpisodeFinder] is the pure "which episode comes next" rule.
 *
 * Moved verbatim from VodPlayerActivity.
 */
package com.iptv.player.ui.player

import android.os.Handler
import android.view.View
import com.iptv.player.data.model.Episode
import com.iptv.player.data.model.Season
import com.iptv.player.databinding.ActivityVodPlayerBinding

internal object VodNextEpisodeFinder {
    /** Next episode after (season, episode), rolling into the next season's first. */
    fun findNext(
        seasons: List<Season>,
        season: Int,
        episode: Int,
    ): Episode? {
        // Providers sometimes include placeholder episodes with no stream URL.
        // Walk forward in episode order and skip those rows instead of launching
        // an empty Uri into the retry/fallback ladder.
        for (seasonEntry in seasons.sortedBy { it.seasonNumber }) {
            if (seasonEntry.seasonNumber < season) continue
            val next = seasonEntry.episodes
                .asSequence()
                .filter { candidate ->
                    candidate.streamUrl.isNotBlank() &&
                        (seasonEntry.seasonNumber > season ||
                            candidate.episodeNumber > episode)
                }
                .minByOrNull { it.episodeNumber }
            if (next != null) return next
        }
        return null
    }
}

internal class VodNextEpisodePrompt(
    private val binding: ActivityVodPlayerBinding,
    private val handler: Handler,
    private val host: Host,
) {
    interface Host {
        val completionGeneration: Long
        /** `isFinishing || isDestroyed` of the owning Activity. */
        val finishingOrDestroyed: Boolean
        fun setPlayerChromeFocusable(enabled: Boolean)
        fun countdownLabel(secondsRemaining: Int): String
        /** Countdown reached zero: play the pending episode now. */
        fun onCountdownElapsed()
        /** Dismissed with focus restore: show controls + focus play/pause. */
        fun onDismissedRestoreFocus()
    }

    private val gate = VodNextEpisodeGate()
    var pending: Episode? = null
        private set
    private var secondsRemaining = 0

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (
                binding.nextEpisodeOverlay.visibility != View.VISIBLE ||
                !gate.isPending(host.completionGeneration)
            ) {
                return
            }
            secondsRemaining--
            if (secondsRemaining <= 0) {
                host.onCountdownElapsed()
            } else {
                updateCountdown()
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    val isShowing: Boolean
        get() = binding.nextEpisodeOverlay.visibility == View.VISIBLE

    /** Arms the gate for [completionToken]; false when this completion already prompted. */
    fun arm(next: Episode, completionToken: Long, countdownSeconds: Int): Boolean {
        if (!gate.arm(completionToken)) return false
        pending = next
        secondsRemaining = countdownSeconds
        return true
    }

    /** Shows the overlay for the episode armed by [arm]. */
    fun present(next: Episode) {
        binding.nextEpisodeName.text = next.title
        updateCountdown()
        handler.removeCallbacks(countdownRunnable)
        binding.nextEpisodeOverlay.visibility = View.VISIBLE
        binding.nextEpisodeOverlay.bringToFront()
        host.setPlayerChromeFocusable(false)
        binding.nextEpisodePlayNow.requestFocus()
        handler.postDelayed(countdownRunnable, 1_000L)
    }

    private fun updateCountdown() {
        binding.nextEpisodeCountdown.text = host.countdownLabel(secondsRemaining)
    }

    /** Consumes the one-shot for the current completion generation. */
    fun consume(): Boolean = gate.consume(host.completionGeneration)

    fun cancel() {
        if (binding.nextEpisodeOverlay.visibility != View.VISIBLE) return
        dismiss(restoreFocus = true)
    }

    fun dismiss(restoreFocus: Boolean) {
        handler.removeCallbacks(countdownRunnable)
        gate.cancel()
        binding.nextEpisodeOverlay.visibility = View.GONE
        pending = null
        secondsRemaining = 0
        host.setPlayerChromeFocusable(true)
        if (restoreFocus && !host.finishingOrDestroyed) {
            host.onDismissedRestoreFocus()
        }
    }
}
