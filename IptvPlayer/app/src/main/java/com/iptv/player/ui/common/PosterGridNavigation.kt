package com.iptv.player.ui.common

/** Adapter positions, rather than screen coordinates, determine the next TV card. */
object PosterGridNavigation {
    enum class Direction { UP, DOWN, PREVIOUS, NEXT }

    fun target(position: Int, count: Int, columns: Int, direction: Direction): Int? {
        if (count <= 0 || columns <= 0 || position !in 0 until count) return null
        val column = position % columns
        return when (direction) {
            Direction.UP -> if (position >= columns) position - columns else position
            Direction.DOWN -> {
                val nextRow = (position / columns + 1) * columns
                if (nextRow < count) minOf(position + columns, count - 1) else position
            }
            Direction.PREVIOUS -> if (column > 0) position - 1 else position
            Direction.NEXT -> if (column < columns - 1 && position + 1 < count) position + 1 else position
        }
    }
}
