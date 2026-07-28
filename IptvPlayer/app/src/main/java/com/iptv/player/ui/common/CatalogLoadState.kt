package com.iptv.player.ui.common

import androidx.annotation.StringRes

/**
 * Network state for lazy movie/series catalog hydration. [total] is greater than
 * one when an All/Recommended/search view is completing the whole visible
 * catalog; a regular category load uses a one-item progress state.
 */
data class CatalogLoadState(
    val loading: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    @StringRes val errorRes: Int? = null,
)
