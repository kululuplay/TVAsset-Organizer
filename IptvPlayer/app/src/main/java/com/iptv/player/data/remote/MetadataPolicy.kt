package com.iptv.player.data.remote

import com.iptv.player.data.model.Episode

internal object MetadataPolicy {
    fun isNew(addedAtSeconds: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val age = nowMs / 1000L - addedAtSeconds
        return addedAtSeconds > 0L && age in 0 until 86_400L
    }

    fun tmdbId(value: String?): String? = value?.trim()?.toLongOrNull()
        ?.takeIf { it > 0L }?.toString()

    fun searchTitle(value: String): String = value.trim()
        .replace(Regex("^(?:vodGerman\\s*|(?:TR|DE|AT|UK|US|EN|FR|IT|ES)\\s*[:|]\\s*)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*(?:\\(|\\[)(?:19|20)\\d{2}(?:\\)|\\])\\s*$"), "")
        .replace(Regex("\\s+(?:4K|UHD|FHD|HD|SD)\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()

    /** All catalog ordering uses Unix seconds, never the time the detail page was opened. */
    fun newest(previous: Long, vararg timestamps: String?): Long =
        (timestamps.mapNotNull { it?.trim()?.toLongOrNull() } + previous)
            .map { if (it >= 100_000_000_000L) it / 1000L else it }
            .filter { it > 0L }.maxOrNull() ?: 0L

    fun enrichEpisode(episode: Episode, metadata: TmdbEpisode): Episode {
        val generic = Regex("(?i)^(?:episode|folge|bölüm)\\s*\\d+$|^\\d+\\.?\\s*(?:bölüm|episode|folge)$")
        return episode.copy(
            title = if (episode.title.isBlank() || generic.matches(episode.title.trim()))
                metadata.name?.takeIf { it.isNotBlank() } ?: episode.title else episode.title,
            plot = episode.plot?.takeIf { it.isNotBlank() } ?: metadata.overview?.takeIf { it.isNotBlank() },
            posterUrl = episode.posterUrl?.takeIf { it.isNotBlank() } ?: TmdbApi.posterUrl(metadata.stillPath),
            durationSecs = episode.durationSecs?.takeIf { it > 0 }
                ?: metadata.runtime?.takeIf { it > 0 }?.times(60),
        )
    }
}
