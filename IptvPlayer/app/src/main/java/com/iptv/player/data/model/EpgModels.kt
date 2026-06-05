/*
 * EpgModels.kt
 * Electronic Program Guide models. A Program belongs to an EPG channel id
 * (tvg-id / epg_channel_id) and has a start/stop window used for now/next and
 * the timeline guide.
 */
package com.iptv.player.data.model

data class Program(
    val epgChannelId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val stopMs: Long
) {
    /** True if [now] falls within this program's window. */
    fun isLiveAt(now: Long): Boolean = now in startMs until stopMs

    /** 0f..1f progress through the program at [now]. */
    fun progressAt(now: Long): Float {
        val total = (stopMs - startMs).coerceAtLeast(1)
        return ((now - startMs).toFloat() / total).coerceIn(0f, 1f)
    }
}

/** Now / next pair for a channel, used on channel cards and overlays. */
data class NowNext(
    val now: Program?,
    val next: Program?
)
