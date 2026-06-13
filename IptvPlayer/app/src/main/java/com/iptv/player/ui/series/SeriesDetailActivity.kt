/*
 * SeriesDetailActivity.kt
 * Series detail screen: a full-bleed backdrop hero with title, star rating,
 * year, genres, an expandable plot and Play / Trailer / favorite actions. Below
 * sit the Seasons chips and a horizontal episode rail. Play resumes the last
 * watched episode when available, otherwise starts the season's first episode.
 */
package com.iptv.player.ui.series

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.Episode
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.Season
import com.iptv.player.data.model.Series
import com.iptv.player.databinding.ActivitySeriesDetailBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.CastAdapter
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.RatingStars
import com.iptv.player.ui.common.SimilarAdapter
import com.iptv.player.ui.common.SimilarCard
import com.iptv.player.ui.player.VodPlayerActivity
import com.iptv.player.ui.trailer.TrailerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SeriesDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "extra_series_id"
        private const val PLOT_COLLAPSED = 3
        private const val PLOT_EXPANDED = 6
    }

    private lateinit var binding: ActivitySeriesDetailBinding
    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private lateinit var seasonAdapter: SeasonAdapter
    private lateinit var episodeAdapter: EpisodeAdapter
    private val similarAdapter = SimilarAdapter(onClicked = { openSimilar(it) })
    private val castAdapter = CastAdapter()

    private var seriesId: String? = null
    private var seriesName: String = ""
    private var seriesPoster: String? = null
    private var seriesTrailer: String? = null
    private var currentSeason: Season? = null
    private var latestResume: ContinueItem? = null
    private var isFavorite = false
    // The "Similar" rail is the last focusable rail; it only exists once data
    // arrives, so episode cards must learn to drop down into it only when it shows.
    private var similarVisible = false

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
        binding.playButton.setOnClickListener { onPlay() }
        binding.trailerButton.setOnClickListener { openTrailer() }
        binding.favoriteButton.setOnClickListener { toggleFavorite(id) }

        loadHeader(id)
        loadFavorite(id)
        loadSeasons(id)
        loadResumeState(id)

        // Open at the top with Play focused; otherwise the ScrollView can auto-scroll
        // to whichever rail grabs focus first as the lists populate.
        binding.scrollBody.post {
            binding.scrollBody.scrollTo(0, 0)
            binding.playButton.requestFocus()
        }
    }

    private fun setupLists() {
        seasonAdapter = SeasonAdapter(onSelected = { showSeason(it) })
        binding.seasonList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.seasonList.adapter = seasonAdapter
        // The season chip recolors itself via notifyItemChanged from its own focus
        // listener. With change animations on, that notify detaches the focused
        // chip (cross-fade), focus is lost, RecyclerView re-grabs it, the focus
        // listener fires again -> an infinite focus ping-pong that floods the IME
        // (onStartInput) and ANRs the screen, kicking the user back to Dashboard.
        // Rebinding in place (animations off) keeps D-pad focus and breaks the loop.
        (binding.seasonList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        episodeAdapter = EpisodeAdapter(onClicked = { playEpisode(it) })
        binding.episodeList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.episodeList.adapter = episodeAdapter
        // Same guard: setWatchState() rebinds visible episode cards via
        // notifyItemRangeChanged; without this it could steal focus from the card
        // the user is on when resume progress lands.
        (binding.episodeList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        binding.similarList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.similarList.adapter = similarAdapter
        // Same focus-stability guard the season/episode rails use.
        (binding.similarList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        binding.castList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.castList.adapter = castAdapter

        setupFocusLinks()
    }

    /**
     * Vertical D-pad traversal between the stacked horizontal rails. Focus lands on
     * the rails' item views, not the RecyclerViews, so a plain geometry search can
     * stall when a card is scrolled off-axis. We stamp nextFocusUp/Down onto each
     * card as it attaches so Up/Down always hop to the neighbouring rail. The cast
     * rail is display-only and is skipped entirely (episodes -> similar).
     */
    private fun setupFocusLinks() {
        attachVerticalFocus(binding.seasonList) {
            it.nextFocusUpId = R.id.playButton
            it.nextFocusDownId = R.id.episodeList
        }
        attachVerticalFocus(binding.episodeList) {
            it.nextFocusUpId = R.id.seasonList
            it.nextFocusDownId = if (similarVisible) R.id.similarList else View.NO_ID
        }
        attachVerticalFocus(binding.similarList) {
            it.nextFocusUpId = R.id.episodeList
            it.nextFocusDownId = View.NO_ID
        }
    }

    private fun attachVerticalFocus(list: RecyclerView, stamp: (View) -> Unit) {
        list.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) = stamp(view)
                override fun onChildViewDetachedFromWindow(view: View) {}
            }
        )
    }

    /** Per-episode progress + the last-watched episode for a one-tap Play resume. */
    private fun loadResumeState(id: String) {
        lifecycleScope.launch {
            // Resume metadata is a non-essential overlay; a lookup failure (e.g. a
            // stale local cache) must never stop the series page from opening.
            val progress: Map<String, Long>
            val latest: ContinueItem?
            val watched: Set<String>
            try {
                progress = repo.episodeProgress(id)
                latest = repo.latestSeriesResume(id)
                watched = repo.watchedEpisodeIds(id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return@launch
            }
            episodeAdapter.setWatchState(progress, watched)
            latestResume = latest
            if (latest != null) {
                binding.playLabel.text = getString(R.string.resume_continue) + "  " +
                    getString(R.string.episode_se_format, latest.seasonNumber, latest.episodeNumber)
            }
        }
    }

    private fun onPlay() {
        val latest = latestResume
        if (latest != null) {
            resumeLatest(latest)
            return
        }
        currentSeason?.episodes?.firstOrNull()?.let { playEpisode(it) }
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
            val series: Series? = try {
                repo.getSeriesCached(id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                null
            }
            if (series != null) {
                bindHeader(series)
                loadSimilar(series)
                loadCast(series)
            }
        }
    }

    /**
     * Renders the cast row: instantly with the source's names, then upgrades to
     * TMDB head-shots in the background. A failure just leaves the names visible.
     */
    private fun loadCast(series: Series) {
        lifecycleScope.launch {
            val cast = try {
                repo.castFor(series.name, series.tmdbId, series.cast, isMovie = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emptyList()
            }
            castAdapter.submitList(cast)
            val show = cast.isNotEmpty()
            binding.castLabel.visibility = if (show) View.VISIBLE else View.GONE
            binding.castList.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    /** Loads other series from the same category into the "Similar" rail. */
    private fun loadSimilar(series: Series) {
        lifecycleScope.launch {
            val similar = try {
                repo.similarSeries(series)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emptyList()
            }
            val cards = similar.map {
                SimilarCard(it.id, it.name, it.posterUrl, it.rating, it.releaseDate)
            }
            similarAdapter.submitList(cards)
            val show = cards.isNotEmpty()
            binding.similarLabel.visibility = if (show) View.VISIBLE else View.GONE
            binding.similarList.visibility = if (show) View.VISIBLE else View.GONE

            // Newly attached episode cards pick this up via setupFocusLinks(); also
            // re-point the cards that are already on screen so Down reaches Similar.
            similarVisible = show
            val downId = if (show) R.id.similarList else View.NO_ID
            for (i in 0 until binding.episodeList.childCount) {
                binding.episodeList.getChildAt(i).nextFocusDownId = downId
            }
        }
    }

    /** Reopens the detail screen for a tapped "Similar" poster. */
    private fun openSimilar(card: SimilarCard) {
        startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra(EXTRA_SERIES_ID, card.id)
        })
    }

    private fun bindHeader(series: Series) {
        seriesName = series.name
        seriesPoster = series.posterUrl
        seriesTrailer = series.trailerUrl
        episodeAdapter.fallbackPoster = series.posterUrl

        binding.detailTitle.text = series.name

        val placeholder = LogoPlaceholder.forName(this, series.name)
        if (series.posterUrl.isNullOrBlank()) {
            binding.detailBackdrop.setImageDrawable(placeholder)
            binding.detailPoster.setImageDrawable(placeholder)
        } else {
            binding.detailBackdrop.load(series.posterUrl) {
                placeholder(placeholder); error(placeholder)
            }
            binding.detailPoster.load(series.posterUrl) {
                placeholder(placeholder); error(placeholder)
            }
        }

        RatingStars.apply(binding.ratingStars, series.rating)

        val year = series.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        binding.detailYear.text = year ?: ""
        binding.detailYear.visibility = if (year != null) View.VISIBLE else View.GONE

        binding.detailGenre.text = series.genre.orEmpty()
        binding.detailGenre.visibility = if (series.genre.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.detailPlot.text = series.plot.orEmpty()
        val hasPlot = !series.plot.isNullOrBlank()
        binding.detailPlot.visibility = if (hasPlot) View.VISIBLE else View.GONE
        binding.detailPlot.maxLines = PLOT_COLLAPSED
        binding.detailMore.visibility = if (hasPlot) View.VISIBLE else View.GONE
        binding.detailMore.setOnClickListener {
            val expanded = binding.detailPlot.maxLines > PLOT_COLLAPSED
            binding.detailPlot.maxLines = if (expanded) PLOT_COLLAPSED else PLOT_EXPANDED
        }

        binding.trailerButton.visibility =
            if (series.trailerUrl.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun loadFavorite(id: String) {
        lifecycleScope.launch {
            isFavorite = try {
                repo.isContentFavorite("series_" + id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                false
            }
            renderFavorite()
        }
    }

    private fun toggleFavorite(id: String) {
        lifecycleScope.launch {
            isFavorite = try {
                repo.toggleFavorite("series_" + id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return@launch
            }
            renderFavorite()
        }
    }

    private fun renderFavorite() {
        binding.favoriteButton.setImageResource(
            if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
    }

    private fun loadSeasons(id: String) {
        binding.emptyState.visibility = View.GONE
        lifecycleScope.launch {
            // 1) Show cached episodes instantly on a repeat visit so the list
            //    appears at once instead of waiting on the series-info API.
            val cached = try {
                repo.getCachedSeasons(id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emptyList()
            }
            val hasCache = cached.isNotEmpty()
            if (hasCache) {
                seasonAdapter.submitList(cached) { showSeason(cached.first()) }
            }
            // Spinner only on a cold open with nothing cached to display yet.
            binding.loading.visibility = if (hasCache) View.GONE else View.VISIBLE

            // 2) Refresh from the network (also re-caches episodes for next time).
            val seasons = try {
                val config = settings.getSourceConfig()
                if (config != null) repo.getSeasons(config, id) else emptyList()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emptyList<Season>()
            }
            binding.loading.visibility = View.GONE

            // Cache was already on screen; the refresh just updated the DB so we
            // leave the user's view undisturbed.
            if (hasCache) return@launch

            if (seasons.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                seasonAdapter.submitList(emptyList())
                episodeAdapter.submitList(emptyList())
                return@launch
            }
            seasonAdapter.submitList(seasons) { showSeason(seasons.first()) }
        }
    }

    private fun showSeason(season: Season) {
        currentSeason = season
        seasonAdapter.setSelected(season.seasonNumber)
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

    private fun openTrailer() {
        val url = seriesTrailer ?: return
        startActivity(Intent(this, TrailerActivity::class.java).apply {
            putExtra(TrailerActivity.EXTRA_TRAILER_URL, url)
            putExtra(TrailerActivity.EXTRA_TITLE, seriesName)
        })
    }
}
