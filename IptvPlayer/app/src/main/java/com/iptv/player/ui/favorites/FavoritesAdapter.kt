/*
 * FavoritesAdapter.kt
 * Poster grid of favorites across all content types. Each card shows the artwork,
 * the title and a small type label (Live / Movie / Series). Clicking a card routes
 * to the correct player or detail screen for that type.
 */
package com.iptv.player.ui.favorites

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.FavoriteItem
import com.iptv.player.data.model.FavoriteKind
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.isAdult

class FavoritesAdapter(
    private val onClicked: (FavoriteItem) -> Unit
) : ListAdapter<FavoriteItem, FavoritesAdapter.VH>(DIFF) {

    /**
     * When true, adult-flagged favorites are masked in the grid (artwork + title
     * hidden behind a lock) until the parental PIN is entered on click — so the
     * metadata never leaks before gating, mirroring VodAdapter/SeriesAdapter.
     * Set from the parental-lock setting.
     */
    var adultLocked: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.favPoster)
        private val title: TextView = itemView.findViewById(R.id.favTitle)
        private val type: TextView = itemView.findViewById(R.id.favType)

        fun bind(item: FavoriteItem) {
            // Keep the card clickable even when masked: open() prompts for the PIN.
            itemView.setOnClickListener { onClicked(item) }

            // Parental gate: mask adult metadata in the grid until the PIN is
            // entered on click. The holder is recycled, so reset the unmasked
            // poster scaleType on the non-locked path below.
            if (adultLocked && item.isAdult()) {
                title.text = itemView.context.getString(R.string.adult_locked_title)
                type.visibility = View.GONE
                poster.scaleType = ImageView.ScaleType.FIT_CENTER
                poster.setImageResource(R.drawable.ic_lock)
                return
            }
            type.visibility = View.VISIBLE
            poster.scaleType = ImageView.ScaleType.CENTER_CROP

            title.text = item.title
            type.setText(
                when (item.kind) {
                    FavoriteKind.CHANNEL -> R.string.nav_live
                    FavoriteKind.MOVIE -> R.string.nav_movies
                    FavoriteKind.SERIES -> R.string.nav_series
                }
            )

            val placeholder = LogoPlaceholder.forName(itemView.context, item.title)
            if (item.posterUrl.isNullOrBlank()) {
                poster.setImageDrawable(placeholder)
            } else {
                poster.load(item.posterUrl) {
                    placeholder(placeholder); error(placeholder)
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FavoriteItem>() {
            override fun areItemsTheSame(a: FavoriteItem, b: FavoriteItem) =
                a.favoriteId == b.favoriteId

            override fun areContentsTheSame(a: FavoriteItem, b: FavoriteItem) = a == b
        }
    }
}
