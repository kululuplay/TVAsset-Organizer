/*
 * SeriesAdapter.kt
 * Poster grid for Series. Mirrors VodAdapter: poster, title and release year
 * with a floating rating badge, DiffUtil for smooth scrolling.
 */
package com.iptv.player.ui.series

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
import com.iptv.player.data.model.Series
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.isAdult

class SeriesAdapter(
    private val onFocused: (Series) -> Unit,
    private val onClicked: (Series) -> Unit
) : PagingDataAdapter<Series, SeriesAdapter.VH>(DIFF) {

    /**
     * Returns the latest in-progress percent (0..100) for a series id. A series
     * has no series-level "watched" tick (the grid can't know if every episode
     * is finished), so only the resume bar is shown — see [seriesWatchProgress].
     */
    var progressProvider: ((String) -> Int)? = null

    /**
     * When true, adult-flagged posters are masked (artwork, title and badges
     * hidden behind a lock) until the parental PIN is entered on click — so the
     * metadata never leaks on screen. Set from the parental-lock setting.
     */
    var adultLocked: Boolean = false

    /**
     * Re-binds only the currently attached cards so resume bars / adult masking
     * pick up state that changed while away — WITHOUT touching the adapter's item
     * notifications. Calling notifyItemRangeChanged(0, itemCount) on a Paging
     * adapter races its own async page invalidation (lazy Room writes invalidate
     * the PagingSource) and throws "Inconsistency detected" -> a crash that drops
     * the user back to the Dashboard. A direct holder rebind avoids that entirely.
     */
    fun refreshVisible(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? VH ?: continue
            holder.boundItem?.let { holder.bind(it) }
        }
    }

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

        /** Last item bound here, so [refreshVisible] can re-bind without Paging. */
        var boundItem: Series? = null
            private set

        fun bind(item: Series) {
            boundItem = item
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
                poster.scaleType = ImageView.ScaleType.FIT_CENTER
                poster.setImageResource(R.drawable.ic_lock)
                return
            }
            poster.scaleType = ImageView.ScaleType.CENTER_CROP

            name.text = item.name

            val pct = progressProvider?.invoke(item.id) ?: 0
            if (pct in 1..99) {
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
        private val DIFF = object : DiffUtil.ItemCallback<Series>() {
            override fun areItemsTheSame(a: Series, b: Series) = a.id == b.id
            override fun areContentsTheSame(a: Series, b: Series) = a == b
        }
    }
}
