/*
 * VodTrackHintBanner.kt
 *
 * Architecture note (VOD player decomposition):
 *   The one-per-session "languages / subtitles available" banner of
 *   VodPlayerActivity: debounced evaluation after track-detection events, the
 *   fade in/out animation and the auto-hide timer. Track counts come from the
 *   Activity through [Host] because only it knows which backend is active.
 *   [VodTrackHintPolicy] is the pure icon/message choice.
 *
 * Moved verbatim from VodPlayerActivity.
 */
package com.iptv.player.ui.player

import android.os.Handler
import android.view.View
import com.iptv.player.R
import com.iptv.player.databinding.ActivityVodPlayerBinding

internal object VodTrackHintPolicy {
    data class Hint(val iconRes: Int, val messageRes: Int)

    /** null when neither several audio languages nor any subtitle track exists. */
    fun pick(audioCount: Int, subtitleCount: Int): Hint? {
        val multiAudio = audioCount >= 2
        val hasSubtitles = subtitleCount >= 1
        if (!multiAudio && !hasSubtitles) return null
        return when {
            multiAudio && hasSubtitles ->
                Hint(R.drawable.ic_subtitles, R.string.track_hint_audio_subtitle)
            multiAudio -> Hint(R.drawable.ic_audiotrack, R.string.track_hint_audio)
            else -> Hint(R.drawable.ic_subtitles, R.string.track_hint_subtitle)
        }
    }
}

internal class VodTrackHintBanner(
    private val binding: ActivityVodPlayerBinding,
    private val handler: Handler,
    private val host: Host,
) {
    interface Host {
        /** (audio, subtitle) selectable track counts, or null when no player is idle/built. */
        fun trackCounts(): Pair<Int, Int>?
    }

    companion object {
        // How long the "languages / subtitles available" banner stays visible.
        const val TRACK_HINT_MS = 4500L
    }

    // Show the multi-language / subtitle hint at most once per playback session.
    var shown = false
    private val showRunnable = Runnable { showIfAvailable() }
    private val hideRunnable = Runnable {
        val banner = binding.trackHintBanner
        banner.animate().cancel()
        banner.animate().alpha(0f).setDuration(180L)
            .withEndAction { banner.visibility = View.GONE }
            .start()
    }

    /** Debounced evaluation; a no-op once the hint was shown this session. */
    fun schedule(delayMs: Long) {
        if (!shown) {
            handler.removeCallbacks(showRunnable)
            handler.postDelayed(showRunnable, delayMs)
        }
    }

    fun cancelPending() {
        handler.removeCallbacks(showRunnable)
        handler.removeCallbacks(hideRunnable)
    }

    /** Background: drop a visible banner so it can show again on return. */
    fun hideForBackground() {
        if (binding.trackHintBanner.visibility == View.VISIBLE) {
            shown = false
            binding.trackHintBanner.animate().cancel()
            binding.trackHintBanner.visibility = View.GONE
        }
    }

    /**
     * If the content offers more than one audio language and/or any subtitle
     * track, surface a short informational banner so the viewer knows they can
     * switch languages or enable subtitles from the player controls.
     */
    private fun showIfAvailable() {
        if (shown) return
        val (audioCount, subtitleCount) = host.trackCounts() ?: return
        val hint = VodTrackHintPolicy.pick(audioCount, subtitleCount) ?: return

        shown = true
        binding.trackHintIcon.setImageResource(hint.iconRes)
        binding.trackHintMessage.setText(hint.messageRes)
        showBanner()
    }

    private fun showBanner() {
        val banner = binding.trackHintBanner
        handler.removeCallbacks(hideRunnable)
        banner.animate().cancel()
        banner.alpha = 0f
        banner.visibility = View.VISIBLE
        banner.animate().alpha(1f).setDuration(250L).start()
        handler.postDelayed(hideRunnable, TRACK_HINT_MS)
    }
}
