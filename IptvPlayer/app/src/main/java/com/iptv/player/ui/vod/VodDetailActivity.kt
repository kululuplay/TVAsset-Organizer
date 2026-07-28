/*
 * VodDetailActivity.kt
 * Movie detail screen: a full-bleed backdrop hero with title, star rating, year,
 * duration, genres, plot and a circular cast row. Play resumes when a saved
 * position exists; Trailer plays in-app (TrailerActivity); the heart toggles favorite.
 */
package com.iptv.player.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.VodItem
import com.iptv.player.databinding.ActivityVodDetailBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.CastAdapter
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.RatingStars
import com.iptv.player.ui.common.SimilarAdapter
import com.iptv.player.ui.common.SimilarCard
import com.iptv.player.ui.player.VodPlayerActivity
import com.iptv.player.ui.trailer.TrailerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VodDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_VOD_ID = "extra_vod_id"
    }

    private lateinit var binding: ActivityVodDetailBinding
    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings
    private val castAdapter = CastAdapter()
    private val similarAdapter = SimilarAdapter(onClicked = { openSimilar(it) })

    private var current: VodItem? = null
    private var hasResume = false
    private var isFavorite = false

    // The cached pass and the detailed pass each kick off cast/similar enrichment.
    // Tracked so a re-load cancels the previous (cached) coroutine before starting
    // the detailed one — otherwise the slower cached pass could land last and
    // overwrite the accurate (tmdbId-based) cast/similar with name-search results.
    private var castJob: Job? = null
    private var similarJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.castList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.castList.adapter = castAdapter

        binding.similarList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.similarList.adapter = similarAdapter

        val id = intent.getStringExtra(EXTRA_VOD_ID)
        if (id == null) {
            finish()
            return
        }

        binding.playButton.setOnClickListener { launchPlayer(autoResume = hasResume) }
        binding.trailerButton.setOnClickListener { openTrailer() }
        binding.favoriteButton.setOnClickListener { toggleFavorite(id) }
        binding.playButton.post { binding.playButton.requestFocus() }

        lifecycleScope.launch {
            similarAdapter.adultLocked =
                settings.lockAdult.first() && settings.hasPin()
            load(id)
        }
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
        // Play auto-resumes when a saved position already exists.
        hasResume = repo.getResume("vod_" + item.id) > 0L
        // Reflect the stored favorite state on the heart icon.
        isFavorite = try {
            repo.isContentFavorite("vod_" + item.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            false
        }
        renderFavorite()
        loadSimilar(item)
    }

    /** Loads other movies from the same category into the "Similar" rail. */
    private fun loadSimilar(item: VodItem) {
        similarJob?.cancel()
        similarJob = lifecycleScope.launch {
            val similar = try {
                repo.similarMovies(item)
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
            binding.similarLabel.text = item.categoryName?.takeIf { it.isNotBlank() }?.let {
                getString(R.string.detail_more_in_category, it)
            } ?: getString(R.string.detail_more_to_watch)
            binding.similarLabel.visibility = if (show) View.VISIBLE else View.GONE
            binding.similarList.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    /** Reopens the detail screen for a tapped "Similar" poster. */
    private fun openSimilar(card: SimilarCard) {
        val adult = PinLockHelper.looksAdult(card.name) ||
            PinLockHelper.looksAdult(card.categoryName)
        PinLockHelper.guard(this, isAdult = adult) {
            startActivity(Intent(this, VodDetailActivity::class.java).apply {
                putExtra(EXTRA_VOD_ID, card.id)
            })
        }
    }

    private fun bind(item: VodItem) {
        binding.detailTitle.text = item.name

        val placeholder = LogoPlaceholder.forName(this, item.name)
        val backdrop = item.backdropUrl ?: item.posterUrl
        if (backdrop.isNullOrBlank()) {
            binding.detailBackdrop.setImageDrawable(placeholder)
        } else {
            binding.detailBackdrop.load(backdrop) {
                placeholder(placeholder); error(placeholder)
            }
        }
        if (item.posterUrl.isNullOrBlank()) {
            binding.detailPoster.setImageDrawable(placeholder)
        } else {
            binding.detailPoster.load(item.posterUrl) {
                placeholder(placeholder); error(placeholder)
            }
        }

        RatingStars.apply(binding.ratingStars, item.rating)

        val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        binding.detailYear.text = year ?: ""
        binding.detailYear.visibility = if (year != null) View.VISIBLE else View.GONE

        val duration = item.durationSecs?.takeIf { it > 0 }?.let { formatDuration(it) }
        binding.detailDuration.text = duration ?: ""
        binding.detailDuration.visibility = if (duration != null) View.VISIBLE else View.GONE

        binding.detailGenre.text = item.genre.orEmpty()
        binding.detailGenre.visibility = if (item.genre.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.detailPlot.text = item.plot.orEmpty()
        binding.detailPlot.visibility = if (item.plot.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.trailerButton.visibility =
            if (item.trailerUrl.isNullOrBlank()) View.GONE else View.VISIBLE

        loadCast(item)
    }

    /**
     * Renders the cast row: instantly with the source's names, then upgrades to
     * TMDB head-shots in the background. A failure just leaves the names visible.
     */
    private fun loadCast(item: VodItem) {
        castJob?.cancel()
        castJob = lifecycleScope.launch {
            val cast = try {
                repo.castFor(item.name, item.tmdbId, item.cast, isMovie = true)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emptyList()
            }
            castAdapter.submitList(cast)
            val showCast = cast.isNotEmpty()
            binding.castLabel.visibility = if (showCast) View.VISIBLE else View.GONE
            binding.castList.visibility = if (showCast) View.VISIBLE else View.GONE
        }
    }

    /** Seconds -> "H:MM:SS" (or "M:SS" when under an hour). */
    private fun formatDuration(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun toggleFavorite(id: String) {
        lifecycleScope.launch {
            isFavorite = try {
                repo.toggleFavorite("vod_" + id)
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
        startActivity(Intent(this, TrailerActivity::class.java).apply {
            putExtra(TrailerActivity.EXTRA_TRAILER_URL, url)
            putExtra(TrailerActivity.EXTRA_TITLE, current?.name)
        })
    }
}
