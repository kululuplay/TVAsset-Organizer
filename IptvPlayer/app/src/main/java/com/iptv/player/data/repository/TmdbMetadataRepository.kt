/*
 * TmdbMetadataRepository.kt
 * Optional TMDB enrichment (posters, backdrops, plots, cast, season metadata)
 * used by the VOD and series repositories when the user has set a TMDB key.
 */
package com.iptv.player.data.repository

import com.iptv.player.data.model.CastMember
import com.iptv.player.data.model.Season
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.VodItem
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.remote.MetadataPolicy
import com.iptv.player.data.remote.TmdbApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import java.util.Locale

internal class TmdbMetadataRepository(
    private val retrofitBuilder: Retrofit.Builder,
    private val settings: SettingsStore,
) {

    // ---- TMDB enrichment ------------------------------------------------

    suspend fun enrichWithTmdb(item: VodItem, isMovie: Boolean): VodItem {
        val key = settings.getTmdbKey()
        if (key.isBlank()) return item
        return runCatching {
            val api = retrofitBuilder.baseUrl(TmdbApi.BASE_URL).build().create(TmdbApi::class.java)
            val result = MetadataPolicy.tmdbId(item.tmdbId)?.let { tmdbId ->
                if (isMovie) api.movieDetail(tmdbId, key) else api.tvDetail(tmdbId, key)
            } ?: (if (isMovie) api.searchMovie(key, MetadataPolicy.searchTitle(item.name))
            else api.searchTv(key, MetadataPolicy.searchTitle(item.name))).results?.firstOrNull() ?: return item
            item.copy(
                posterUrl = TmdbApi.posterUrl(result.posterPath) ?: item.posterUrl,
                backdropUrl = TmdbApi.backdropUrl(result.backdropPath) ?: item.backdropUrl,
                plot = item.plot?.takeIf { it.isNotBlank() } ?: result.overview,
                rating = item.rating ?: result.voteAverage,
                releaseDate = item.releaseDate?.takeIf { it.isNotBlank() }
                    ?: result.releaseDate
                    ?: result.firstAirDate,
                tmdbId = MetadataPolicy.tmdbId(item.tmdbId) ?: result.id?.toString(),
            )
        }.getOrDefault(item)
    }

    suspend fun enrichWithTmdb(item: Series): Series {
        val key = settings.getTmdbKey()
        if (key.isBlank()) return item
        return runCatching {
            val api = retrofitBuilder.baseUrl(TmdbApi.BASE_URL).build().create(TmdbApi::class.java)
            val result = MetadataPolicy.tmdbId(item.tmdbId)?.let { tmdbId ->
                api.tvDetail(tmdbId, key)
            } ?: api.searchTv(key, MetadataPolicy.searchTitle(item.name)).results?.firstOrNull() ?: return item
            item.copy(
                posterUrl = TmdbApi.posterUrl(result.posterPath) ?: item.posterUrl,
                backdropUrl = TmdbApi.backdropUrl(result.backdropPath) ?: item.backdropUrl,
                plot = item.plot?.takeIf { it.isNotBlank() } ?: result.overview,
                rating = item.rating ?: result.voteAverage,
                releaseDate = item.releaseDate?.takeIf { it.isNotBlank() }
                    ?: result.firstAirDate
                    ?: result.releaseDate,
                tmdbId = MetadataPolicy.tmdbId(item.tmdbId) ?: result.id?.toString(),
            )
        }.getOrDefault(item)
    }

    /**
     * Cast list for a detail screen. Falls back to the source's comma-separated
     * names (no photos) and upgrades to TMDB head-shots when a key is available
     * and the title can be resolved. Network failures degrade gracefully to the
     * name-only list so the cast row always renders something.
     */
    suspend fun castFor(
        name: String,
        tmdbId: String?,
        rawCast: String?,
        isMovie: Boolean
    ): List<CastMember> = withContext(Dispatchers.IO) {
        val fallback = rawCast?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { CastMember(it, null) }
            ?: emptyList()

        val key = settings.getTmdbKey()
        if (key.isBlank()) return@withContext fallback

        runCatching {
            val api = retrofitBuilder.baseUrl(TmdbApi.BASE_URL).build().create(TmdbApi::class.java)
            val id = MetadataPolicy.tmdbId(tmdbId) ?: run {
                val results = if (isMovie) api.searchMovie(key, MetadataPolicy.searchTitle(name)).results
                else api.searchTv(key, MetadataPolicy.searchTitle(name)).results
                results?.firstOrNull()?.id?.toString()
            } ?: return@runCatching fallback

            val credits = if (isMovie) api.movieCredits(id, key) else api.tvCredits(id, key)
            val people = credits.cast.orEmpty()
                .sortedBy { it.order ?: Int.MAX_VALUE }
                .mapNotNull { c ->
                    val n = c.name?.trim().orEmpty()
                    if (n.isEmpty()) null else CastMember(n, TmdbApi.profileUrl(c.profilePath))
                }
                .take(15)
            if (people.isNotEmpty()) people else fallback
        }.getOrDefault(fallback)
    }

    /** Fetch the selected season only. Metadata never creates a playable provider episode. */
    suspend fun enrichSeason(series: Series, season: Season): Season = withContext(Dispatchers.IO) {
        val key = settings.getTmdbKey()
        if (key.isBlank()) return@withContext season
        try {
            val api = retrofitBuilder.baseUrl(TmdbApi.BASE_URL).build().create(TmdbApi::class.java)
            val id = MetadataPolicy.tmdbId(series.tmdbId) ?: api.searchTv(
                key, MetadataPolicy.searchTitle(series.name), Locale.getDefault().toLanguageTag(),
            ).results?.firstOrNull()?.id?.toString() ?: return@withContext season
            val metadata = api.seasonDetail(id, season.seasonNumber, key, Locale.getDefault().toLanguageTag())
                .episodes.orEmpty().associateBy { it.episodeNumber }
            season.copy(episodes = season.episodes.map { episode ->
                metadata[episode.episodeNumber]?.let { MetadataPolicy.enrichEpisode(episode, it) } ?: episode
            })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            season
        }
    }
}
