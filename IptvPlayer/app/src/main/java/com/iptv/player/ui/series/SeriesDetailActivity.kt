/*
 * SeriesDetailActivity.kt
 * Series detail screen. Loads seasons + episodes on demand, shows a horizontal
 * season selector and the episode list for the selected season. Clicking an
 * episode starts on-demand playback with a per-episode resume id.
 */
package com.iptv.player.ui.series

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.Episode
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.Season
import com.iptv.player.databinding.ActivitySeriesDetailBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.player.VodPlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SeriesDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "extra_series_id"
    }

    private lateinit var binding: ActivitySeriesDetailBinding
    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private lateinit var seasonAdapter: SeasonAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    private var seriesId: String? = null
    private var seriesName: String = ""
    private var seriesPoster: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_SERIES_ID)
        if (id == null) {
            finish()
            return
        }
        seriesId = id

        setupLists()
        loadHeader(id)
        loadSeasons(id)
        loadResumeState(id)
    }

    private fun setupLists() {
        seasonAdapter = SeasonAdapter(onSelected = { showSeason(it) })
        binding.seasonList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.seasonList.adapter = seasonAdapter

        episodeAdapter = EpisodeAdapter(onClicked = { playEpisode(it) })
        binding.episodeList.layoutManager = LinearLayoutManager(this)
        binding.episodeList.adapter = episodeAdapter
    }

    /** Per-episode progress + a one-tap continue for the last-watched episode. */
    private fun loadResumeState(id: String) {
        binding.continueButton.visibility = View.GONE
        lifecycleScope.launch {
            // Resume metadata is a non-essential overlay; a lookup failure (e.g. a
            // stale local cache) must never stop the series page from opening.
            val progress: Map<String, Long>
            val latest: ContinueItem?
            try {
                progress = repo.episodeProgress(id)
                latest = repo.latestSeriesResume(id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return@launch
            }
            episodeAdapter.setProgress(progress)
            if (latest != null) {
                binding.continueButton.text = getString(R.string.resume_continue) + "  " +
                    getString(R.string.episode_se_format, latest.seasonNumber, latest.episodeNumber)
                binding.continueButton.visibility = View.VISIBLE
                binding.continueButton.setOnClickListener { resumeLatest(latest) }
            }
        }
    }

    private fun resumeLatest(item: ContinueItem) {
        val intent = Intent(this, VodPlayerActivity::class.java).apply {
            putExtra(VodPlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
            putExtra(VodPlayerActivity.EXTRA_TITLE, item.title)
            putExtra(VodPlayerActivity.EXTRA_RESUME_ID, item.contentId)
            putExtra(VodPlayerActivity.EXTRA_RESUME_TYPE, item.kind.raw)
            putExtra(VodPlayerActivity.EXTRA_RESUME_TITLE, item.title)
            putExtra(VodPlayerActivity.EXTRA_POSTER_URL, item.posterUrl)
            putExtra(VodPlayerActivity.EXTRA_SERIES_ID, item.seriesId)
            putExtra(VodPlayerActivity.EXTRA_SEASON, item.seasonNumber)
            putExtra(VodPlayerActivity.EXTRA_EPISODE, item.episodeNumber)
            putExtra(VodPlayerActivity.EXTRA_AUTO_RESUME, true)
        }
        startActivity(intent)
    }

    private fun loadHeader(id: String) {
        lifecycleScope.launch {
            val series = try {
                repo.observeSeries().first().firstOrNull { it.id == id }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                null
            }
            if (series != null) {
                seriesName = series.name
                seriesPoster = series.posterUrl
                binding.detailTitle.text = series.name
                val placeholder = LogoPlaceholder.forName(this@SeriesDetailActivity, series.name)
                if (series.posterUrl.isNullOrBlank()) {
                    binding.detailPoster.setImageDrawable(placeholder)
                } else {
                    binding.detailPoster.load(series.posterUrl) {
                        placeholder(placeholder); error(placeholder)
                    }
                }
                val year = series.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
                val rating = series.rating?.takeIf { it > 0 }?.let { String.format("%.1f", it) }
                val meta = buildList {
                    if (year != null) add(getString(R.string.detail_year) + ": " + year)
                    if (rating != null) add(getString(R.string.detail_rating) + ": " + rating)
                }.joinToString("   ")
                binding.detailMeta.text = meta
                binding.detailMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
                binding.detailPlot.text = series.plot.orEmpty()
                binding.detailPlot.visibility =
                    if (series.plot.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun loadSeasons(id: String) {
        binding.loading.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        lifecycleScope.launch {
            val seasons = try {
                val config = settings.getSourceConfig()
                if (config != null) repo.getSeasons(config, id) else emptyList()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emptyList<Season>()
            }
            binding.loading.visibility = View.GONE
            if (seasons.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                seasonAdapter.submitList(emptyList())
                episodeAdapter.submitList(emptyList())
                return@launch
            }
            seasonAdapter.submitList(seasons)
            showSeason(seasons.first())
        }
    }

    private fun showSeason(season: Season) {
        episodeAdapter.submitList(season.episodes)
        binding.emptyState.visibility =
            if (season.episodes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playEpisode(episode: Episode) {
        val intent = Intent(this, VodPlayerActivity::class.java).apply {
            putExtra(VodPlayerActivity.EXTRA_STREAM_URL, episode.streamUrl)
            putExtra(VodPlayerActivity.EXTRA_TITLE, episode.title)
            putExtra(VodPlayerActivity.EXTRA_RESUME_ID, "ep_" + episode.id)
            putExtra(VodPlayerActivity.EXTRA_RESUME_TYPE, ResumeKind.EPISODE.raw)
            // Use the series name as the rail label; episode title stays the player bar.
            putExtra(VodPlayerActivity.EXTRA_RESUME_TITLE, seriesName.ifBlank { episode.title })
            putExtra(VodPlayerActivity.EXTRA_POSTER_URL, episode.posterUrl ?: seriesPoster)
            putExtra(VodPlayerActivity.EXTRA_SERIES_ID, episode.seriesId)
            putExtra(VodPlayerActivity.EXTRA_SEASON, episode.seasonNumber)
            putExtra(VodPlayerActivity.EXTRA_EPISODE, episode.episodeNumber)
        }
        startActivity(intent)
    }
}
