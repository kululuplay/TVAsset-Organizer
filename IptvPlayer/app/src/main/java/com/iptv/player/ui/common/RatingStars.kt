/*
 * RatingStars.kt
 * Paints a row of five star ImageViews from a 0-10 rating (TMDB scale). Each
 * child of the supplied container becomes full / half / empty accordingly.
 */
package com.iptv.player.ui.common

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.iptv.player.R

object RatingStars {

    /** @param rating10 rating on a 0-10 scale (null/<=0 renders all empty). */
    fun apply(container: LinearLayout, rating10: Double?) {
        val normalized = (rating10 ?: 0.0).coerceIn(0.0, 10.0)
        val outOfFive = normalized / 2.0
        for (i in 0 until container.childCount) {
            val star = container.getChildAt(i) as? ImageView ?: continue
            // Five decorative stars are one value, not five TalkBack stops.
            star.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val slot = i + 1
            star.setImageResource(
                when {
                    outOfFive >= slot -> R.drawable.ic_star
                    outOfFive >= slot - 0.5 -> R.drawable.ic_star_half
                    else -> R.drawable.ic_star_outline
                }
            )
        }
    }
}
