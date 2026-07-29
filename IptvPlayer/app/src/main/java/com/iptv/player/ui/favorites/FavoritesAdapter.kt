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
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.FavoriteItem
import com.iptv.player.data.model.FavoriteKind
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.isAdult

class FavoritesAdapter(
    private val onClicked: (FavoriteItem) -> Unit,
    private val onLongClicked: (FavoriteItem) -> Unit,
    private val onFocused: (String, Int) -> Unit,
) : ListAdapter<FavoriteItem, FavoritesAdapter.VH>(DIFF) {

    /**
     * When true, adult-flagged favorites are masked in the grid (artwork + title
     * hidden behind a lock) until the parental PIN is entered on click — so the
     * metadata never leaks before gating, mirroring VodAdapter/SeriesAdapter.
     * Set from the parental-lock setting.
     */
    var adultLocked: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PRIVACY_PAYLOAD)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.favPoster)
        private val title: TextView = itemView.findViewById(R.id.favTitle)
        private val type: TextView = itemView.findViewById(R.id.favType)

        fun bind(item: FavoriteItem) {
            itemView.setOnClickListener { onClicked(item) }
            itemView.setOnLongClickListener {
                onLongClicked(item)
                true
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (hasFocus && position != RecyclerView.NO_POSITION) {
                    onFocused(item.favoriteId, position)
                }
            }

            // Cancel the previous holder request before changing scale/type. This
            // prevents a late Coil result from leaking recycled artwork.
            poster.dispose()

            // Parental gate: mask adult metadata in the grid until the PIN is
            // entered on click. Keep the card actionable so OK still opens PIN.
            if (adultLocked && item.isAdult()) {
                title.text = itemView.context.getString(R.string.adult_locked_title)
                type.visibility = View.GONE
                poster.scaleType = ImageView.ScaleType.FIT_CENTER
                poster.load(R.drawable.ic_lock) { crossfade(false) }
                itemView.contentDescription =
                    itemView.context.getString(R.string.adult_locked_title)
                ViewCompat.setStateDescription(itemView, null)
                return
            }
            type.visibility = View.VISIBLE
            poster.scaleType = ImageView.ScaleType.CENTER_CROP

            title.text = item.title
            val typeLabel = itemView.context.getString(
                when (item.kind) {
                    FavoriteKind.CHANNEL -> R.string.nav_live
                    FavoriteKind.MOVIE -> R.string.nav_movies
                    FavoriteKind.SERIES -> R.string.nav_series
                }
            )
            type.text = typeLabel
            itemView.contentDescription = listOf(item.title, typeLabel).joinToString(". ")
            ViewCompat.setStateDescription(itemView, null)

            val placeholder = LogoPlaceholder.forName(itemView.context, item.title)
            if (item.posterUrl.isNullOrBlank()) {
                poster.load(placeholder) { crossfade(false) }
            } else {
                poster.load(item.posterUrl) {
                    placeholder(placeholder); error(placeholder)
                }
            }
        }

        fun recycle() {
            poster.dispose()
            poster.setImageDrawable(null)
            itemView.setOnClickListener(null)
            itemView.setOnLongClickListener(null)
            itemView.setOnFocusChangeListener(null)
            itemView.contentDescription = null
            ViewCompat.setStateDescription(itemView, null)
        }
    }

    companion object {
        private const val PRIVACY_PAYLOAD = "privacy"

        private val DIFF = object : DiffUtil.ItemCallback<FavoriteItem>() {
            override fun areItemsTheSame(a: FavoriteItem, b: FavoriteItem) =
                a.favoriteId == b.favoriteId

            override fun areContentsTheSame(a: FavoriteItem, b: FavoriteItem) = a == b
        }
    }
}
