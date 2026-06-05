/*
 * ResumeModels.kt
 * Domain models for the "Continue Watching" feature. ContinueItem powers the
 * dashboard rail; ResumeMeta is the metadata the player persists alongside the
 * current position so items can later be rendered and reopened.
 */
package com.iptv.player.data.model

/**
 * Whether a resumable entry is a movie, a series episode, or a catch-up program.
 * Only MOVIE and EPISODE appear on the Continue Watching rail; CATCHUP keeps its
 * resume position for reopening but is intentionally excluded from the rail.
 */
enum class ResumeKind(val raw: String) {
    MOVIE("movie"),
    EPISODE("episode"),
    CATCHUP("catchup");

    companion object {
        fun fromRaw(value: String?): ResumeKind =
            entries.firstOrNull { it.raw == value } ?: MOVIE
    }
}

/** An in-progress item shown on the Continue Watching rail. */
data class ContinueItem(
    val contentId: String,
    val kind: ResumeKind,
    val title: String,
    val posterUrl: String?,
    val streamUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val vodId: String?,
    val seriesId: String?,
    val seasonNumber: Int,
    val episodeNumber: Int
) {
    /** Watched fraction as a 0..100 percentage for the progress bar. */
    val progressPercent: Int
        get() = if (durationMs > 0)
            ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)
        else 0
}

/** Display + navigation metadata persisted with a resume position. */
data class ResumeMeta(
    val contentId: String,
    val kind: ResumeKind,
    val title: String,
    val posterUrl: String?,
    val streamUrl: String,
    val vodId: String?,
    val seriesId: String?,
    val seasonNumber: Int,
    val episodeNumber: Int
)
