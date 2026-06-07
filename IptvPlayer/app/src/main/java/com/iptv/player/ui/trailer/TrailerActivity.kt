/*
 * TrailerActivity.kt
 * Plays a movie/series trailer INSIDE the app instead of bouncing the user out
 * to the YouTube app or a browser:
 *   - YouTube links/ids  -> a full-screen WebView IFrame player (no Play-services
 *     or YouTube app required, so it works on plain Android TV boxes). Autoplays;
 *     Back exits.
 *   - direct video URLs  -> handed to the in-app VodPlayerActivity (libVLC/Media3).
 * If the WebView is missing/disabled (inflation throws) or the IFrame player
 * errors out, it falls back once to opening the canonical YouTube link
 * externally so the user is never left staring at a black screen.
 */
package com.iptv.player.ui.trailer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.iptv.player.R
import com.iptv.player.databinding.ActivityTrailerBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.player.VodPlayerActivity
import com.iptv.player.util.YoutubeUrl
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener

class TrailerActivity : BaseActivity() {

    companion object {
        const val EXTRA_TRAILER_URL = "extra_trailer_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private var binding: ActivityTrailerBinding? = null

    /** Guards the external fallback so an error storm can't fire it repeatedly. */
    private var fellBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_TRAILER_URL)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val videoId = YoutubeUrl.extractId(url)

        if (videoId == null) {
            // Not a YouTube reference: play the direct media in the in-app player.
            if (!url.isNullOrBlank()) {
                startActivity(Intent(this, VodPlayerActivity::class.java).apply {
                    putExtra(VodPlayerActivity.EXTRA_STREAM_URL, url)
                    putExtra(VodPlayerActivity.EXTRA_TITLE, title)
                })
            }
            finish()
            return
        }

        // Inflating the IFrame player constructs a WebView; on devices where the
        // WebView provider is missing/disabled that throws, so guard it and fall
        // back to the external app rather than crashing.
        val ready = runCatching {
            val b = ActivityTrailerBinding.inflate(layoutInflater)
            binding = b
            setContentView(b.root)
            lifecycle.addObserver(b.youtubePlayerView)
            b.youtubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) {
                    youTubePlayer.loadVideo(videoId, 0f)
                }

                override fun onError(
                    youTubePlayer: YouTubePlayer,
                    error: PlayerConstants.PlayerError
                ) {
                    openExternallyOnce(YoutubeUrl.watchUrl(videoId))
                }
            })
        }.isSuccess

        if (!ready) openExternallyOnce(YoutubeUrl.watchUrl(videoId))
    }

    private fun openExternallyOnce(url: String) {
        if (fellBack) return
        fellBack = true
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
