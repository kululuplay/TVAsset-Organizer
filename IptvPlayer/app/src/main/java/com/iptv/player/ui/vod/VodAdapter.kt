/*
 * VodAdapter.kt
 * Poster grid for VOD (movies). Shows the poster, title and release year, with
 * a floating rating badge when available. DiffUtil keeps the grid smooth.
 */
package com.iptv.player.ui.vod

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.VodItem
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.isAdult

class VodAdapter(
    private val onFocused: (VodItem) -> Unit,
    private val onClicked: (VodItem) -> Unit
) : PagingDataAdapter<VodItem, VodAdapter.VH>(DIFF) {

    /** Returns 0..100 watch-progress for a movie's resume id ("vod_<id>"). */
    var progressProvider: ((String) -> Int)? = null

    /** True when a movie's resume id ("vod_<id>") has been fully watched. */
    var watchedProvider: ((String) -> Boolean)? = null

    /**
     * When true, adult-flagged posters are masked (artwork, title and badges
     * hidden behind a lock) until the parental PIN is entered on click — so the
     * metadata never leaks on screen. Set from the parental-lock setting.
     */
    var adultLocked: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poster, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Paging may hand back null for not-yet-loaded placeholders; skip those.
        val item = getItem(position) ?: return
        holder.bind(item)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.posterImage)
        private val name: TextView = itemView.findViewById(R.id.posterName)
        private val meta: TextView = itemView.findViewById(R.id.posterMeta)
        private val ratingRow: LinearLayout = itemView.findViewById(R.id.posterRatingRow)
        private val rating: TextView = itemView.findViewById(R.id.posterRating)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.posterProgress)
        private val watched: ImageView = itemView.findViewById(R.id.posterWatched)

        fun bind(item: VodItem) {
            itemView.setOnClickListener { onClicked(item) }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused(item)
            }

            // Parental gate: mask adult metadata until the PIN is entered.
            if (adultLocked && item.isAdult()) {
                name.text = itemView.context.getString(R.string.adult_locked_title)
                meta.visibility = View.GONE
                ratingRow.visibility = View.GONE
                progressBar.visibility = View.GONE
                watched.visibility = View.GONE
                poster.scaleType = ImageView.ScaleType.FIT_CENTER
                poster.setImageResource(R.drawable.ic_lock)
                return
            }
            poster.scaleType = ImageView.ScaleType.CENTER_CROP

            name.text = item.name

            val contentId = "vod_${item.id}"
            val isWatched = watchedProvider?.invoke(contentId) == true
            watched.visibility = if (isWatched) View.VISIBLE else View.GONE

            val pct = progressProvider?.invoke(contentId) ?: 0
            if (!isWatched && pct in 1..99) {
                progressBar.progress = pct
                progressBar.visibility = View.VISIBLE
            } else {
                progressBar.visibility = View.GONE
            }

            val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
            meta.text = year ?: ""
            meta.visibility = if (year != null) View.VISIBLE else View.GONE

            val ratingText = item.rating?.takeIf { it > 0 }?.let { String.format("%.1f", it) }
            if (ratingText != null) {
                rating.text = ratingText
                ratingRow.visibility = View.VISIBLE
            } else {
                ratingRow.visibility = View.GONE
            }

            val placeholder = LogoPlaceholder.forName(itemView.context, item.name)
            if (item.posterUrl.isNullOrBlank()) {
                poster.setImageDrawable(placeholder)
            } else {
                poster.load(item.posterUrl) {
                    crossfade(false)
                    placeholder(placeholder)
                    error(placeholder)
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VodItem>() {
            override fun areItemsTheSame(a: VodItem, b: VodItem) = a.id == b.id
            override fun areContentsTheSame(a: VodItem, b: VodItem) = a == b
        }
    }
}
