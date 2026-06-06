/*
 * VodDetailActivity.kt
 * Movie detail screen. Loads the full (optionally TMDB-enriched) VOD detail on
 * demand and shows poster, plot, cast, director, genre, year and rating with
 * Play and Trailer actions. Play opens the on-demand player with a resume id.
 */
package com.iptv.player.ui.vod

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import coil.load
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.VodItem
import com.iptv.player.databinding.ActivityVodDetailBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.player.VodPlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class VodDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_VOD_ID = "extra_vod_id"
    }

    private lateinit var binding: ActivityVodDetailBinding
    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private var current: VodItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_VOD_ID)
        if (id == null) {
            finish()
            return
        }

        binding.playButton.setOnClickListener { launchPlayer(autoResume = false) }
        binding.resumeButton.setOnClickListener { launchPlayer(autoResume = true) }
        binding.trailerButton.setOnClickListener { openTrailer() }

        load(id)
    }

    private fun load(id: String) {
        lifecycleScope.launch {
            // 1) Render the cached record instantly so the poster, title and a
            //    working Play button appear at once instead of waiting on the
            //    (sometimes slow) detail API. The stream URL is already cached.
            val cached = repo.getVodCached(id)
            if (cached != null) showItem(cached)

            // 2) Enrich (plot, cast, director, trailer, better poster) in the
            //    background and refresh the screen when it arrives. Network
            //    hiccups must never block or close an already-shown page.
            val config = settings.getSourceConfig()
            val detailed = if (config != null) {
                try {
                    repo.getVodDetail(config, id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    null
                }
            } else null

            when {
                detailed != null -> showItem(detailed)
                cached == null -> {
                    Toast.makeText(this@VodDetailActivity, R.string.error_unknown, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private suspend fun showItem(item: VodItem) {
        current = item
        bind(item)
        // Offer a one-tap "continue" when a resume position already exists.
        val resumed = repo.getResume("vod_" + item.id) > 0L
        binding.resumeButton.visibility = if (resumed) View.VISIBLE else View.GONE
    }

    private fun bind(item: VodItem) {
        binding.detailTitle.text = item.name

        val placeholder = LogoPlaceholder.forName(this, item.name)
        if (item.posterUrl.isNullOrBlank()) {
            binding.detailPoster.setImageDrawable(placeholder)
        } else {
            binding.detailPoster.load(item.posterUrl) {
                placeholder(placeholder); error(placeholder)
            }
        }

        val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        val rating = item.rating?.takeIf { it > 0 }?.let { String.format("%.1f", it) }
        val meta = buildList {
            if (year != null) add(getString(R.string.detail_year) + ": " + year)
            if (rating != null) add(getString(R.string.detail_rating) + ": " + rating)
        }.joinToString("   ")
        binding.detailMeta.text = meta
        binding.detailMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        binding.detailPlot.text = item.plot.orEmpty()
        binding.detailPlot.visibility = if (item.plot.isNullOrBlank()) View.GONE else View.VISIBLE

        bindLabeled(binding.detailCast, R.string.detail_cast, item.cast)
        bindLabeled(binding.detailDirector, R.string.detail_director, item.director)
        bindLabeled(binding.detailGenre, R.string.detail_genre, item.genre)

        binding.trailerButton.visibility =
            if (item.trailerUrl.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun bindLabeled(view: android.widget.TextView, labelRes: Int, value: String?) {
        if (value.isNullOrBlank()) {
            view.visibility = View.GONE
        } else {
            view.visibility = View.VISIBLE
            view.text = getString(labelRes) + ": " + value
        }
    }

    private fun launchPlayer(autoResume: Boolean) {
        val item = current ?: return
        val intent = Intent(this, VodPlayerActivity::class.java).apply {
            putExtra(VodPlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
            putExtra(VodPlayerActivity.EXTRA_TITLE, item.name)
            putExtra(VodPlayerActivity.EXTRA_RESUME_ID, "vod_" + item.id)
            putExtra(VodPlayerActivity.EXTRA_RESUME_TYPE, ResumeKind.MOVIE.raw)
            putExtra(VodPlayerActivity.EXTRA_POSTER_URL, item.posterUrl)
            putExtra(VodPlayerActivity.EXTRA_VOD_ID, item.id)
            putExtra(VodPlayerActivity.EXTRA_AUTO_RESUME, autoResume)
        }
        startActivity(intent)
    }

    private fun openTrailer() {
        val url = current?.trailerUrl ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show()
        }
    }
}
