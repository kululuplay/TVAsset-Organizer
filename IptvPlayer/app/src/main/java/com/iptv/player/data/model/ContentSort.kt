/*
 * ContentSort.kt
 * Ordering options for the Movies / Series poster grids. RECENT is the default
 * (newest first); the others map to the per-field paging queries in the DAOs.
 */
package com.iptv.player.data.model

enum class ContentSort {
    RECENT,
    NAME,
    RATING,
    YEAR
}
