package com.iptv.player.ui.series

/** Keeps the same column where possible, including a partially filled last row. */
internal object EpisodeGridNavigation {
    fun verticalTarget(position: Int, count: Int, columns: Int, down: Boolean): Int? {
        if (position !in 0 until count || columns <= 0) return null
        if (!down) return (position - columns).takeIf { it >= 0 }
        if ((position / columns + 1) * columns >= count) return null
        return (position + columns).coerceAtMost(count - 1)
    }
}
