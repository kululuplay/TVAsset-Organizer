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
import android.widget.Toast
import androidx.core.view.ViewCompat
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
import java.util.Locale

class SeriesDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "extra_series_id"
        private const val PLOT_COLLAPSED = 3

        private const val STATE_SEASON = "series_detail_season"
        private const val STATE_EPISODE_POSITION = "series_detail_episode_position"
        private const val STATE_FOCUS_ZONE = "series_detail_focus_zone"
        private const val STATE_SCROLL_Y = "series_detail_scroll_y"
        private const val STATE_PLOT_EXPANDED = "series_detail_plot_expanded"

        private const val FOCUS_PLAY = 0
        private const val FOCUS_SEASON = 1
        private const val FOCUS_EPISODE = 2
        private const val FOCUS_RETRY = 3
        private const val FOCUS_SIMILAR = 4
        private const val FOCUS_TRAILER = 5
        private const val FOCUS_FAVORITE = 6
        private const val FOCUS_MORE = 7
        private const val FOCUS_HEADER_RETRY = 8
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
    private var headerJob: Job? = null
    private var favoriteJob: Job? = null
    private var resumeJob: Job? = null
    private var castJob: Job? = null
    private var similarJob: Job? = null
    private var seasonsLoading = true
    private var resumeLoaded = false
    private var plotExpanded = false
    private var restoreFocusAfterSeasonRetry = false
    // The "Similar" rail is the last focusable rail; it only exists once data
    // arrives, so episode cards must learn to drop down into it only when it shows.
    private var similarVisible = false
    private var similarLoaded = false
    private val episodePositionBySeason = mutableMapOf<Int, Int>()
    private var episodesReadyForSeason: Int? = null
    private var focusEpisodeAfterCommit = false
    private var restoredSeasonNumber: Int? = null
    private var restoredEpisodePosition = 0
    private var restoredFocusZone = FOCUS_PLAY
    private var restoredScrollY = 0
    private var initialFocusRestored = false

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
        restoredSeasonNumber =
            savedInstanceState?.takeIf { it.containsKey(STATE_SEASON) }?.getInt(STATE_SEASON)
        restoredEpisodePosition =
            savedInstanceState?.getInt(STATE_EPISODE_POSITION, 0) ?: 0
        restoredSeasonNumber?.let {
            episodePositionBySeason[it] = restoredEpisodePosition
        }
        restoredFocusZone =
            savedInstanceState?.getInt(STATE_FOCUS_ZONE, FOCUS_PLAY) ?: FOCUS_PLAY
        restoredScrollY = savedInstanceState?.getInt(STATE_SCROLL_Y, 0) ?: 0
        plotExpanded = savedInstanceState?.getBoolean(STATE_PLOT_EXPANDED, false) ?: false

        setupLists()
        ViewCompat.setAccessibilityHeading(binding.detailTitle, true)
        ViewCompat.setAccessibilityHeading(binding.seasonsLabel, true)
        ViewCompat.setAccessibilityHeading(binding.episodesLabel, true)
        ViewCompat.setAccessibilityHeading(binding.castLabel, true)
        ViewCompat.setAccessibilityHeading(binding.similarLabel, true)
        binding.playButton.setOnClickListener { onPlay() }
        binding.trailerButton.setOnClickListener { openTrailer() }
        binding.favoriteButton.setOnClickListener { toggleFavorite(id) }
        binding.episodeRetryButton.setOnClickListener {
            restoreFocusAfterSeasonRetry = true
            loadSeasons(id)
        }
        binding.headerRetryButton.setOnClickListener {
            loadHeader(id)
            loadSeasons(id)
        }

        lifecycleScope.launch {
            similarAdapter.adultLocked =
                settings.lockAdult.first() && settings.hasPin()
            // Do not start any path that can populate Similar until the adult
            // mask is known; otherwise protected artwork can flash for one frame.
            loadHeader(id)
            loadFavorite(id)
            loadSeasons(id)
        }

        // Keep a stable fallback target while async detail/episode data arrives.
        // The exact saved focus zone is restored as soon as its rail is attached.
        binding.scrollBody.post {
            binding.scrollBody.scrollTo(0, restoredScrollY)
            binding.favoriteButton.requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentSeason?.seasonNumber?.let { outState.putInt(STATE_SEASON, it) }
        val episodePosition = currentSeason?.seasonNumber
            ?.let { episodePositionBySeason[it] }
            ?: restoredEpisodePosition
        outState.putInt(STATE_EPISODE_POSITION, episodePosition)
        outState.putInt(STATE_SCROLL_Y, binding.scrollBody.scrollY)
        outState.putBoolean(STATE_PLOT_EXPANDED, plotExpanded)
        outState.putInt(
            STATE_FOCUS_ZONE,
            when {
                binding.seasonList.hasFocus() -> FOCUS_SEASON
                binding.episodeList.hasFocus() -> FOCUS_EPISODE
                binding.episodeRetryButton.hasFocus() -> FOCUS_RETRY
                binding.similarList.hasFocus() -> FOCUS_SIMILAR
                binding.trailerButton.hasFocus() -> FOCUS_TRAILER
                binding.favoriteButton.hasFocus() -> FOCUS_FAVORITE
                binding.detailMore.hasFocus() -> FOCUS_MORE
                binding.headerRetryButton.hasFocus() -> FOCUS_HEADER_RETRY
                else -> FOCUS_PLAY
            },
        )
        super.onSaveInstanceState(outState)
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

        episodeAdapter = EpisodeAdapter(
            onFocused = { episode, position ->
                episodePositionBySeason[episode.seasonNumber] = position
            },
            onClicked = { playEpisode(it) },
        )
        binding.episodeList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.episodeList.adapter = episodeAdapter
        // Same guard: setWatchState() rebinds visible episode cards via
        // notifyItemRangeChanged; without this it could steal focus from the card
        // the user is on when resume progress lands.
        (binding.episodeList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        binding.episodeList.setHasFixedSize(true)
        binding.episodeList.setItemViewCacheSize(6)

        binding.similarList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.similarList.adapter = similarAdapter
        // Same focus-stability guard the season/episode rails use.
        (binding.similarList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        binding.similarList.setHasFixedSize(true)

        binding.castList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.castList.adapter = castAdapter
        binding.castList.setHasFixedSize(true)
        binding.seasonList.setHasFixedSize(true)

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
            it.nextFocusUpId = focusPrimaryActionId()
            it.nextFocusDownId =
                if (currentSeasonHasPlayableEpisodes()) {
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
            it.nextFocusUpId = focusTargetAboveSimilar()
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
        resumeJob?.cancel()
        resumeJob = lifecycleScope.launch {
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
        val latest = latestResume?.takeIf { it.streamUrl.isNotBlank() }
        if (latest != null) {
            resumeLatest(latest)
            return
        }
        currentSeason?.episodes
            ?.firstOrNull { it.streamUrl.isNotBlank() }
            ?.let { playEpisode(it) }
    }

    private fun resumeLatest(item: ContinueItem) {
        val episodeTitle = seasonAdapter.currentList
            .asSequence()
            .flatMap { it.episodes.asSequence() }
            .firstOrNull {
                it.id == item.contentId.removePrefix("ep_") ||
                    (
                        it.seasonNumber == item.seasonNumber &&
                            it.episodeNumber == item.episodeNumber
                    )
            }
            ?.title
            ?.takeIf { it.isNotBlank() }
            ?: getString(
                R.string.episode_se_format,
                item.seasonNumber,
                item.episodeNumber,
            )
        val intent = Intent(this, VodPlayerActivity::class.java).apply {
            putExtra(VodPlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
            putExtra(VodPlayerActivity.EXTRA_TITLE, episodeTitle)
            putExtra(VodPlayerActivity.EXTRA_RESUME_ID, item.contentId)
            putExtra(VodPlayerActivity.EXTRA_RESUME_TYPE, item.kind.raw)
            // Continue Watching keeps the series title while the player chrome
            // presents the exact episode title resolved above.
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
        headerJob?.cancel()
        val retryInPlace =
            binding.headerErrorContainer.visibility == View.VISIBLE &&
                binding.headerRetryButton.hasFocus()
        if (seriesName.isBlank()) {
            binding.heroContent.visibility = View.INVISIBLE
            binding.headerLoadingContainer.visibility =
                if (retryInPlace) View.GONE else View.VISIBLE
            binding.headerErrorContainer.visibility =
                if (retryInPlace) View.VISIBLE else View.GONE
            if (retryInPlace) {
                binding.headerRetryButton.isClickable = false
                binding.headerRetryButton.alpha = 0.58f
            }
        }
        headerJob = lifecycleScope.launch {
            val series: Series? = try {
                repo.getSeriesCached(id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                null
            }
            binding.headerLoadingContainer.visibility = View.GONE
            if (series != null) {
                binding.headerErrorContainer.visibility = View.GONE
                bindHeader(series)
                loadSimilar(series)
                loadCast(series)
            } else if (seriesName.isBlank()) {
                binding.headerErrorContainer.visibility = View.VISIBLE
                binding.headerRetryButton.isClickable = true
                binding.headerRetryButton.alpha = 1f
                restoreDetailFocusIfReady()
                if (!initialFocusRestored) {
                    binding.headerRetryButton.post {
                        if (!initialFocusRestored) binding.headerRetryButton.requestFocus()
                    }
                }
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
        similarLoaded = false
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
            similarLoaded = true
            val downId = if (show) R.id.similarList else View.NO_ID
            for (i in 0 until binding.episodeList.childCount) {
                binding.episodeList.getChildAt(i).nextFocusDownId = downId
            }
            updateSeasonFocusLinks()
            restoreDetailFocusIfReady()
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
        val retryHadFocus = binding.headerRetryButton.hasFocus()
        binding.headerRetryButton.isClickable = true
        binding.headerRetryButton.alpha = 1f
        binding.headerLoadingContainer.visibility = View.GONE
        binding.headerErrorContainer.visibility = View.GONE
        binding.heroContent.visibility = View.VISIBLE
        seriesName = series.name
        seriesPoster = series.posterUrl
        seriesTrailer = series.trailerUrl
        episodeAdapter.fallbackPoster = series.posterUrl

        binding.detailTitle.text = series.name
        binding.detailPoster.contentDescription = null

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
        val visibleRating = series.rating?.takeIf { it > 0.0 }
        binding.ratingContainer.visibility =
            if (visibleRating != null) View.VISIBLE else View.GONE
        binding.ratingContainer.contentDescription = visibleRating?.let {
                getString(R.string.detail_rating) + " " +
                    String.format(Locale.getDefault(), "%.1f", it)
            }
            ?: ""

        val year = series.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        binding.detailYear.text = year ?: ""
        binding.detailYear.visibility = if (year != null) View.VISIBLE else View.GONE

        binding.detailGenre.text = series.genre.orEmpty()
        binding.detailGenre.visibility = if (series.genre.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.detailPlot.text = series.plot.orEmpty()
        val hasPlot = !series.plot.isNullOrBlank()
        binding.detailPlot.visibility = if (hasPlot) View.VISIBLE else View.GONE
        binding.detailPlot.maxLines = if (plotExpanded) Int.MAX_VALUE else PLOT_COLLAPSED
        binding.detailMore.setText(
            if (plotExpanded) R.string.detail_less else R.string.detail_more,
        )
        binding.detailMore.visibility = View.GONE
        if (hasPlot) {
            binding.detailPlot.post {
                if (plotExpanded) {
                    binding.detailMore.visibility = View.VISIBLE
                } else {
                    val layout = binding.detailPlot.layout
                    val lastLine = (layout?.lineCount ?: 0) - 1
                    val ellipsized =
                        lastLine >= 0 && (layout?.getEllipsisCount(lastLine) ?: 0) > 0
                    binding.detailMore.visibility =
                        if (ellipsized) View.VISIBLE else View.GONE
                }
            }
        }
        binding.detailMore.setOnClickListener {
            plotExpanded = !plotExpanded
            binding.detailPlot.maxLines =
                if (plotExpanded) Int.MAX_VALUE else PLOT_COLLAPSED
            binding.detailMore.setText(
                if (plotExpanded) R.string.detail_less else R.string.detail_more
            )
            // Expanding reflows the ScrollView; keep the toggle itself visible so
            // the viewer can collapse the synopsis without hunting for focus.
            binding.detailMore.post {
                binding.scrollBody.requestChildFocus(binding.detailMore, binding.detailMore)
            }
        }

        val trailerHadFocus = binding.trailerButton.hasFocus()
        binding.trailerButton.visibility =
            if (series.trailerUrl.isNullOrBlank()) View.GONE else View.VISIBLE
        if (trailerHadFocus && binding.trailerButton.visibility != View.VISIBLE) {
            binding.favoriteButton.requestFocus()
        }
        if (retryHadFocus) focusPrimaryAction()
        if (currentFocus == null) binding.favoriteButton.requestFocus()
        restoreDetailFocusIfReady()
    }

    private fun loadFavorite(id: String) {
        favoriteJob?.cancel()
        favoriteJob = lifecycleScope.launch {
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
        if (!binding.favoriteButton.isClickable) return
        favoriteJob?.cancel()
        binding.favoriteButton.isClickable = false
        binding.favoriteButton.alpha = 0.58f
        favoriteJob = lifecycleScope.launch {
            try {
                isFavorite =
                    repo.toggleFavorite("series_" + id)
                renderFavorite()
                Toast.makeText(
                    this@SeriesDetailActivity,
                    if (isFavorite) R.string.added_to_favorites
                    else R.string.removed_from_favorites,
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Toast.makeText(
                    this@SeriesDetailActivity,
                    R.string.error_unknown,
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                binding.favoriteButton.isClickable = true
                binding.favoriteButton.alpha = 1f
            }
        }
    }

    private fun renderFavorite() {
        binding.favoriteButton.setImageResource(
            if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        binding.favoriteButton.isSelected = isFavorite
        binding.favoriteButton.isActivated = isFavorite
        binding.favoriteButton.contentDescription = getString(R.string.section_favorites)
        ViewCompat.setStateDescription(
            binding.favoriteButton,
            getString(
                if (isFavorite) R.string.favorite_state_on
                else R.string.favorite_state_off,
            ),
        )
        restoreDetailFocusIfReady()
    }

    private fun loadSeasons(id: String) {
        seasonsJob?.cancel()
        val retryInPlace =
            restoreFocusAfterSeasonRetry &&
                binding.episodeEmptyContainer.visibility == View.VISIBLE &&
                binding.episodeRetryButton.hasFocus()
        seasonsLoading = true
        binding.episodeEmptyContainer.visibility =
            if (retryInPlace) View.VISIBLE else View.GONE
        if (retryInPlace) {
            // Keep the focused retry surface in place while the request runs. If it
            // vanished here Android TV would hand focus to an arbitrary neighbour.
            binding.episodeRetryButton.isClickable = false
            binding.episodeRetryButton.alpha = 0.58f
        }
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
                val target = preferredSeason(cached)
                seasonAdapter.submitList(cached) {
                    showSeason(target)
                    restoreSeasonRetryFocus()
                    restoreDetailFocusIfReady()
                }
            }
            // Spinner only on a cold open with nothing cached to display yet.
            binding.loading.visibility =
                if (hasCache || retryInPlace) View.GONE else View.VISIBLE

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
            binding.episodeRetryButton.isClickable = true
            binding.episodeRetryButton.alpha = 1f

            val refreshed = try {
                repo.getSeriesCached(id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                null
            }
            refreshed?.let {
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
                seasonAdapter.setSelected(null)
                seasonAdapter.submitList(emptyList())
                episodesReadyForSeason = null
                focusEpisodeAfterCommit = false
                binding.episodeList.visibility = View.INVISIBLE
                episodeAdapter.submitList(emptyList()) {
                    updateSeasonFocusLinks()
                    restoreDetailFocusIfReady()
                }
                updateSeasonFocusLinks()
                renderPlayState()
                if (restoreFocusAfterSeasonRetry) {
                    restoreFocusAfterSeasonRetry = false
                    binding.episodeRetryButton.post {
                        binding.episodeRetryButton.requestFocus()
                    }
                }
                restoreDetailFocusIfReady()
                return@launch
            }
            // Apply refreshed metadata and episodes while preserving the season
            // the viewer was browsing.
            val target = preferredSeason(seasons)
            seasonsLoading = false
            seasonAdapter.submitList(seasons) {
                showSeason(target)
                restoreSeasonRetryFocus()
                restoreDetailFocusIfReady()
            }
        }
    }

    private fun preferredSeason(seasons: List<Season>): Season {
        val preferred = currentSeason?.seasonNumber ?: restoredSeasonNumber
        return seasons.firstOrNull { it.seasonNumber == preferred } ?: seasons.first()
    }

    private fun restoreSeasonRetryFocus() {
        if (!restoreFocusAfterSeasonRetry) return
        restoreFocusAfterSeasonRetry = false
        focusCurrentSeason()
    }

    private fun showSeason(season: Season) {
        currentSeason = season
        seasonAdapter.setSelected(season.seasonNumber)
        episodesReadyForSeason = null
        focusEpisodeAfterCommit = false
        binding.episodeList.visibility = View.INVISIBLE
        // Providers occasionally return placeholder episodes without a playable
        // URL. Do not expose dead OK targets in a TV rail.
        val playableEpisodes = season.episodes.filter { it.streamUrl.isNotBlank() }
        val submittedSeason = season.seasonNumber
        episodeAdapter.submitList(playableEpisodes) {
            if (currentSeason?.seasonNumber != submittedSeason) return@submitList
            episodesReadyForSeason = submittedSeason
            binding.episodeList.visibility =
                if (playableEpisodes.isNotEmpty()) View.VISIBLE else View.INVISIBLE
            updateSeasonFocusLinks()
            if (focusEpisodeAfterCommit && playableEpisodes.isNotEmpty()) {
                focusEpisodeAfterCommit = false
                focusCurrentEpisode()
            }
            restoreDetailFocusIfReady()
        }
        binding.episodeEmptyContainer.visibility =
            if (playableEpisodes.isEmpty()) View.VISIBLE else View.GONE
        updateSeasonFocusLinks()
        renderPlayState()
    }

    private fun updateSeasonFocusLinks() {
        val downId =
            if (hasReadyEpisodes()) {
                R.id.episodeList
            } else {
                R.id.episodeRetryButton
            }
        for (i in 0 until binding.seasonList.childCount) {
            binding.seasonList.getChildAt(i).apply {
                nextFocusUpId = focusPrimaryActionId()
                nextFocusDownId = downId
            }
        }
        val similarUpId = focusTargetAboveSimilar()
        for (i in 0 until binding.similarList.childCount) {
            binding.similarList.getChildAt(i).nextFocusUpId = similarUpId
        }
    }

    private fun renderPlayState() {
        val latest = latestResume?.takeIf { it.streamUrl.isNotBlank() }
        val playable = latest != null ||
            currentSeason?.episodes?.any { it.streamUrl.isNotBlank() } == true
        val playHadFocus = binding.playButton.hasFocus()
        binding.playButton.isClickable = playable
        binding.playButton.isEnabled = playable
        binding.playButton.isFocusable = playable
        binding.playButton.alpha = if (playable) 1f else 0.42f
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
        binding.playButton.contentDescription = binding.playLabel.text
        updateSeasonFocusLinks()
        if (playHadFocus && !playable) {
            when {
                seasonAdapter.itemCount > 0 -> focusCurrentSeason()
                binding.episodeEmptyContainer.visibility == View.VISIBLE ->
                    binding.episodeRetryButton.requestFocus()
                else -> binding.favoriteButton.requestFocus()
            }
        }
        restoreDetailFocusIfReady()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val rtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val towardEnd =
                if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val towardStart =
                if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            when {
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> when {
                    binding.headerRetryButton.hasFocus() -> {
                        when {
                            seasonAdapter.itemCount > 0 -> focusCurrentSeason()
                            binding.episodeEmptyContainer.visibility == View.VISIBLE ->
                                binding.episodeRetryButton.requestFocus()
                        }
                        return true
                    }
                    binding.detailMore.hasFocus() -> {
                        focusPrimaryAction()
                        return true
                    }
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
                        if (hasReadyEpisodes()) {
                            focusCurrentEpisode()
                        } else if (currentSeasonHasPlayableEpisodes()) {
                            focusEpisodeAfterCommit = true
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
                    binding.episodeRetryButton.hasFocus() && similarVisible -> {
                        binding.similarList.requestFocusAt(0)
                        return true
                    }
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> when {
                    binding.seasonList.hasFocus() -> {
                        focusPrimaryAction()
                        return true
                    }
                    binding.playButton.hasFocus() ||
                        binding.trailerButton.hasFocus() ||
                        binding.favoriteButton.hasFocus() -> {
                        if (binding.detailMore.visibility == View.VISIBLE) {
                            binding.detailMore.requestFocus()
                            return true
                        }
                    }
                    binding.episodeRetryButton.hasFocus() -> {
                        if (seasonAdapter.itemCount > 0) focusCurrentSeason()
                        else focusPrimaryAction()
                        return true
                    }
                    binding.episodeList.hasFocus() -> {
                        focusCurrentSeason()
                        return true
                    }
                    binding.similarList.hasFocus() -> {
                        when {
                            hasReadyEpisodes() -> focusCurrentEpisode()
                            binding.episodeEmptyContainer.visibility == View.VISIBLE ->
                                binding.episodeRetryButton.requestFocus()
                            seasonAdapter.itemCount > 0 -> focusCurrentSeason()
                            else -> focusPrimaryAction()
                        }
                        return true
                    }
                }
                event.keyCode == towardEnd && binding.playButton.hasFocus() -> {
                    if (binding.trailerButton.visibility == View.VISIBLE) {
                        binding.trailerButton.requestFocus()
                    } else {
                        binding.favoriteButton.requestFocus()
                    }
                    return true
                }
                event.keyCode == towardEnd && binding.trailerButton.hasFocus() -> {
                    binding.favoriteButton.requestFocus()
                    return true
                }
                event.keyCode == towardStart && binding.favoriteButton.hasFocus() -> {
                    if (binding.trailerButton.visibility == View.VISIBLE) {
                        binding.trailerButton.requestFocus()
                    } else if (binding.playButton.isEnabled) {
                        binding.playButton.requestFocus()
                    }
                    return true
                }
                event.keyCode == towardStart && binding.trailerButton.hasFocus() -> {
                    if (binding.playButton.isEnabled) binding.playButton.requestFocus()
                    return true
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

    private fun focusCurrentEpisode() {
        if (!hasReadyEpisodes()) {
            if (currentSeasonHasPlayableEpisodes()) {
                focusEpisodeAfterCommit = true
            }
            return
        }
        val season = currentSeason?.seasonNumber
        val position = season?.let { episodePositionBySeason[it] } ?: 0
        binding.episodeList.requestFocusAt(position.coerceAtLeast(0))
    }

    private fun focusPrimaryAction() {
        if (binding.playButton.isEnabled) {
            binding.playButton.requestFocus()
        } else {
            binding.favoriteButton.requestFocus()
        }
    }

    private fun focusPrimaryActionId(): Int =
        if (binding.playButton.isEnabled) R.id.playButton else R.id.favoriteButton

    private fun focusTargetAboveSimilar(): Int = when {
        hasReadyEpisodes() -> R.id.episodeList
        binding.episodeEmptyContainer.visibility == View.VISIBLE -> R.id.episodeRetryButton
        seasonAdapter.itemCount > 0 -> R.id.seasonList
        binding.playButton.isEnabled -> R.id.playButton
        else -> R.id.favoriteButton
    }

    /**
     * Async rails cannot be focused until their adapter commit has attached a
     * child. Restore once, at the same logical item the viewer was using.
     */
    private fun restoreDetailFocusIfReady() {
        if (initialFocusRestored) return
        val restored = when (restoredFocusZone) {
            FOCUS_PLAY -> when {
                binding.playButton.isEnabled -> binding.playButton.requestFocus()
                binding.headerErrorContainer.visibility == View.VISIBLE ->
                    binding.headerRetryButton.requestFocus()
                !seasonsLoading &&
                    binding.episodeEmptyContainer.visibility == View.VISIBLE ->
                    binding.episodeRetryButton.requestFocus()
                else -> false
            }
            FOCUS_SEASON -> {
                if (seasonAdapter.itemCount > 0) {
                    focusCurrentSeason()
                    true
                } else {
                    false
                }
            }
            FOCUS_EPISODE -> {
                if (hasReadyEpisodes()) {
                    focusCurrentEpisode()
                    true
                } else {
                    false
                }
            }
            FOCUS_RETRY -> {
                if (binding.episodeEmptyContainer.visibility == View.VISIBLE) {
                    binding.episodeRetryButton.requestFocus()
                } else {
                    false
                }
            }
            FOCUS_SIMILAR -> when {
                similarVisible && similarAdapter.itemCount > 0 -> {
                    binding.similarList.requestFocusAt(0)
                    true
                }
                similarLoaded -> {
                    when {
                        hasReadyEpisodes() -> focusCurrentEpisode()
                        seasonAdapter.itemCount > 0 -> focusCurrentSeason()
                        else -> focusPrimaryAction()
                    }
                    true
                }
                else -> false
            }
            FOCUS_TRAILER -> when {
                binding.trailerButton.visibility == View.VISIBLE ->
                    binding.trailerButton.requestFocus()
                seriesName.isNotBlank() ||
                    binding.headerErrorContainer.visibility == View.VISIBLE ->
                    binding.favoriteButton.requestFocus()
                else -> false
            }
            FOCUS_FAVORITE -> {
                if (binding.heroContent.visibility == View.VISIBLE) {
                    binding.favoriteButton.requestFocus()
                } else {
                    false
                }
            }
            FOCUS_MORE -> when {
                binding.detailMore.visibility == View.VISIBLE ->
                    binding.detailMore.requestFocus()
                seriesName.isNotBlank() ||
                    binding.headerErrorContainer.visibility == View.VISIBLE -> {
                    focusPrimaryAction()
                    true
                }
                else -> false
            }
            FOCUS_HEADER_RETRY -> {
                if (binding.headerErrorContainer.visibility == View.VISIBLE) {
                    binding.headerRetryButton.requestFocus()
                } else {
                    false
                }
            }
            else -> false
        }
        if (restored) initialFocusRestored = true
    }

    private fun hasReadyEpisodes(): Boolean =
        episodesReadyForSeason == currentSeason?.seasonNumber &&
            episodeAdapter.itemCount > 0

    private fun currentSeasonHasPlayableEpisodes(): Boolean =
        currentSeason?.episodes?.any { it.streamUrl.isNotBlank() } == true

    private fun playEpisode(episode: Episode) {
        if (episode.streamUrl.isBlank()) return
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
        val url = seriesTrailer?.takeIf { it.isNotBlank() } ?: return
        startActivity(Intent(this, TrailerActivity::class.java).apply {
            putExtra(TrailerActivity.EXTRA_TRAILER_URL, url)
            putExtra(TrailerActivity.EXTRA_TITLE, seriesName)
        })
    }
}
