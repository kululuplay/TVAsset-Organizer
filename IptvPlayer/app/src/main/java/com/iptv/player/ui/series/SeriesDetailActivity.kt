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
import android.view.KeyEvent
import android.view.View
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
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.RatingStars
import com.iptv.player.ui.common.SimilarAdapter
import com.iptv.player.ui.common.SimilarCard
import com.iptv.player.ui.common.requestFocusAt
import com.iptv.player.ui.player.VodPlayerActivity
import com.iptv.player.ui.trailer.TrailerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SeriesDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "extra_series_id"
        private const val PLOT_COLLAPSED = 3
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
    private var seasonsJob: Job? = null
    private var castJob: Job? = null
    private var similarJob: Job? = null
    private var seasonsLoading = true
    private var resumeLoaded = false
    private var plotExpanded = false
    private var restoreFocusAfterSeasonRetry = false
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
        binding.episodeRetryButton.setOnClickListener {
            restoreFocusAfterSeasonRetry = true
            loadSeasons(id)
        }

        lifecycleScope.launch {
            similarAdapter.adultLocked =
                settings.lockAdult.first() && settings.hasPin()
            loadHeader(id)
            loadFavorite(id)
            loadSeasons(id)
        }

        // Open at the top with Play focused; otherwise the ScrollView can auto-scroll
        // to whichever rail grabs focus first as the lists populate.
        binding.scrollBody.post {
            binding.scrollBody.scrollTo(0, 0)
            binding.playButton.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        seriesId?.let(::loadResumeState)
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
            it.nextFocusDownId =
                if (currentSeason?.episodes?.isNotEmpty() == true) {
                    R.id.episodeList
                } else {
                    R.id.episodeRetryButton
                }
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
                resumeLoaded = true
                renderPlayState()
                return@launch
            }
            episodeAdapter.setWatchState(progress, watched)
            latestResume = latest
            resumeLoaded = true
            renderPlayState()
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
        castJob?.cancel()
        castJob = lifecycleScope.launch {
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
        similarJob?.cancel()
        similarJob = lifecycleScope.launch {
            val similar = try {
                repo.similarSeries(series)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emptyList()
            }
            val cards = similar.map {
                SimilarCard(
                    it.id,
                    it.name,
                    it.posterUrl,
                    it.rating,
                    it.releaseDate,
                    it.categoryName,
                )
            }
            similarAdapter.submitList(cards)
            val show = cards.isNotEmpty()
            binding.similarLabel.text = series.categoryName?.takeIf { it.isNotBlank() }?.let {
                getString(R.string.detail_more_in_category, it)
            } ?: getString(R.string.detail_more_to_watch)
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
        val adult = PinLockHelper.looksAdult(card.name) ||
            PinLockHelper.looksAdult(card.categoryName)
        PinLockHelper.guard(this, isAdult = adult) {
            startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                putExtra(EXTRA_SERIES_ID, card.id)
            })
        }
    }

    private fun bindHeader(series: Series) {
        seriesName = series.name
        seriesPoster = series.posterUrl
        seriesTrailer = series.trailerUrl
        episodeAdapter.fallbackPoster = series.posterUrl

        binding.detailTitle.text = series.name

        val placeholder = LogoPlaceholder.forName(this, series.name)
        val backdrop = series.backdropUrl ?: series.posterUrl
        if (backdrop.isNullOrBlank()) {
            binding.detailBackdrop.setImageDrawable(placeholder)
        } else {
            binding.detailBackdrop.load(backdrop) {
                placeholder(placeholder); error(placeholder)
            }
        }
        if (series.posterUrl.isNullOrBlank()) {
            binding.detailPoster.setImageDrawable(placeholder)
        } else {
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
        plotExpanded = false
        binding.detailPlot.maxLines = PLOT_COLLAPSED
        binding.detailMore.setText(R.string.detail_more)
        binding.detailMore.visibility = View.GONE
        if (hasPlot) {
            binding.detailPlot.post {
                val layout = binding.detailPlot.layout
                val lastLine = (layout?.lineCount ?: 0) - 1
                val ellipsized =
                    lastLine >= 0 && (layout?.getEllipsisCount(lastLine) ?: 0) > 0
                binding.detailMore.visibility = if (ellipsized) View.VISIBLE else View.GONE
            }
        }
        binding.detailMore.setOnClickListener {
            plotExpanded = !plotExpanded
            binding.detailPlot.maxLines =
                if (plotExpanded) Int.MAX_VALUE else PLOT_COLLAPSED
            binding.detailMore.setText(
                if (plotExpanded) R.string.detail_less else R.string.detail_more
            )
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
        seasonsJob?.cancel()
        seasonsLoading = true
        binding.episodeEmptyContainer.visibility = View.GONE
        renderPlayState()
        seasonsJob = lifecycleScope.launch {
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
                seasonAdapter.submitList(cached) {
                    showSeason(cached.first())
                    restoreSeasonRetryFocus()
                }
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

            repo.getSeriesCached(id)?.let { refreshed ->
                bindHeader(refreshed)
                loadSimilar(refreshed)
                loadCast(refreshed)
            }
            if (seasons.isEmpty()) {
                // getSeasons already falls back to cache on network failure. An
                // empty result here is therefore authoritative (or no cache ever
                // existed): clear stale cards that may have been rendered by the
                // instant cached pass.
                currentSeason = null
                seasonsLoading = false
                binding.episodeEmptyContainer.visibility = View.VISIBLE
                seasonAdapter.submitList(emptyList())
                episodeAdapter.submitList(emptyList())
                updateSeasonFocusLinks()
                renderPlayState()
                if (restoreFocusAfterSeasonRetry) {
                    restoreFocusAfterSeasonRetry = false
                    binding.episodeRetryButton.post {
                        binding.episodeRetryButton.requestFocus()
                    }
                }
                return@launch
            }
            // Apply refreshed metadata and episodes while preserving the season
            // the viewer was browsing.
            val selectedSeason = currentSeason?.seasonNumber
            val target = seasons.firstOrNull { it.seasonNumber == selectedSeason }
                ?: seasons.first()
            seasonsLoading = false
            seasonAdapter.submitList(seasons) {
                showSeason(target)
                restoreSeasonRetryFocus()
            }
        }
    }

    private fun restoreSeasonRetryFocus() {
        if (!restoreFocusAfterSeasonRetry) return
        restoreFocusAfterSeasonRetry = false
        focusCurrentSeason()
    }

    private fun showSeason(season: Season) {
        currentSeason = season
        seasonAdapter.setSelected(season.seasonNumber)
        episodeAdapter.submitList(season.episodes) { updateSeasonFocusLinks() }
        binding.episodeEmptyContainer.visibility =
            if (season.episodes.isEmpty()) View.VISIBLE else View.GONE
        updateSeasonFocusLinks()
        renderPlayState()
    }

    private fun updateSeasonFocusLinks() {
        val downId =
            if (currentSeason?.episodes?.isNotEmpty() == true) {
                R.id.episodeList
            } else {
                R.id.episodeRetryButton
            }
        for (i in 0 until binding.seasonList.childCount) {
            binding.seasonList.getChildAt(i).nextFocusDownId = downId
        }
    }

    private fun renderPlayState() {
        val latest = latestResume
        val playable = latest != null || currentSeason?.episodes?.isNotEmpty() == true
        binding.playButton.isClickable = playable
        binding.playButton.alpha = if (playable) 1f else 0.48f
        binding.playLabel.text = when {
            latest != null -> getString(R.string.resume_continue) + "  " +
                getString(
                    R.string.episode_se_format,
                    latest.seasonNumber,
                    latest.episodeNumber,
                )
            playable -> getString(R.string.detail_play)
            seasonsLoading || !resumeLoaded -> getString(R.string.loading)
            else -> getString(R.string.detail_play)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> when {
                    binding.playButton.hasFocus() ||
                        binding.trailerButton.hasFocus() ||
                        binding.favoriteButton.hasFocus() -> {
                        if (seasonAdapter.itemCount > 0) {
                            focusCurrentSeason()
                        } else if (binding.episodeEmptyContainer.visibility == View.VISIBLE) {
                            binding.episodeRetryButton.requestFocus()
                        }
                        return true
                    }
                    binding.seasonList.hasFocus() -> {
                        if (episodeAdapter.itemCount > 0) {
                            binding.episodeList.requestFocusAt(0)
                        } else if (binding.episodeEmptyContainer.visibility == View.VISIBLE) {
                            binding.episodeRetryButton.requestFocus()
                        }
                        return true
                    }
                    binding.episodeList.hasFocus() &&
                        binding.similarList.visibility == View.VISIBLE -> {
                        binding.similarList.requestFocusAt(0)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> when {
                    binding.episodeRetryButton.hasFocus() -> {
                        if (seasonAdapter.itemCount > 0) focusCurrentSeason()
                        else binding.playButton.requestFocus()
                        return true
                    }
                    binding.episodeList.hasFocus() -> {
                        focusCurrentSeason()
                        return true
                    }
                    binding.similarList.hasFocus() && episodeAdapter.itemCount > 0 -> {
                        binding.episodeList.requestFocusAt(0)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun focusCurrentSeason() {
        val number = currentSeason?.seasonNumber
        val position = seasonAdapter.currentList.indexOfFirst { it.seasonNumber == number }
            .takeIf { it >= 0 } ?: 0
        binding.seasonList.requestFocusAt(position)
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
