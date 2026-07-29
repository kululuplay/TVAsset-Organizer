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
import java.util.Locale

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
            .inflate(R.layout.item_series_poster, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Never leave recycled artwork/text visible in a Paging placeholder.
        getItem(position)?.let(holder::bind) ?: holder.clear()
    }

    override fun onViewRecycled(holder: VH) {
        // Cancels the ImageView's in-flight Coil request as well as clearing state.
        // Without this, a late image response can flash on a newly recycled card.
        holder.clear()
        super.onViewRecycled(holder)
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
            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.alpha = 1f
            poster.contentDescription = null
            itemView.setOnClickListener {
                currentItem()?.let(onClicked)
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val position = bindingAdapterPosition
                    currentItem()?.let { focused ->
                        if (position != RecyclerView.NO_POSITION) onFocused(focused)
                    }
                }
            }

            // Parental gate: mask adult metadata until the PIN is entered.
            if (adultLocked && item.isAdult()) {
                name.text = itemView.context.getString(R.string.adult_locked_title)
                meta.text = ""
                meta.visibility = View.INVISIBLE
                ratingRow.visibility = View.GONE
                progressBar.visibility = View.GONE
                poster.scaleType = ImageView.ScaleType.FIT_CENTER
                poster.load(R.drawable.ic_lock) { crossfade(false) }
                itemView.contentDescription = name.text
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
            // Keep the fixed metadata slot even when no year is known so cards in
            // the same grid row never end up with different heights.
            meta.visibility = if (year != null) View.VISIBLE else View.INVISIBLE

            val ratingText = item.rating?.takeIf { it > 0 }?.let {
                String.format(Locale.getDefault(), "%.1f", it)
            }
            if (ratingText != null) {
                rating.text = ratingText
                ratingRow.visibility = View.VISIBLE
            } else {
                ratingRow.visibility = View.GONE
            }

            val placeholder = LogoPlaceholder.forName(itemView.context, item.name)
            if (item.posterUrl.isNullOrBlank()) {
                poster.load(placeholder) { crossfade(false) }
            } else {
                poster.load(item.posterUrl) {
                    crossfade(false)
                    placeholder(placeholder)
                    error(placeholder)
                }
            }

            itemView.contentDescription = buildList {
                add(item.name)
                year?.let(::add)
                ratingText?.let { add(itemView.context.getString(R.string.detail_rating) + " " + it) }
                if (pct in 1..99) {
                    add(itemView.context.getString(R.string.resume_continue) + " " + pct + "%")
                }
            }.joinToString(", ")
        }

        /**
         * Paging can temporarily expose a null placeholder while a new source is
         * committing. Explicitly clear the recycled holder so stale series art can
         * never be clicked or announced as the new row.
         */
        fun clear() {
            boundItem = null
            itemView.isFocusable = false
            itemView.isClickable = false
            itemView.alpha = 0f
            itemView.setOnClickListener(null)
            itemView.onFocusChangeListener = null
            itemView.contentDescription = null
            name.text = ""
            meta.text = ""
            meta.visibility = View.INVISIBLE
            ratingRow.visibility = View.GONE
            progressBar.visibility = View.GONE
            poster.contentDescription = null
            poster.load(null) { crossfade(false) }
        }

        private fun currentItem(): Series? {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || position >= itemCount) return null
            return getItem(position)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Series>() {
            override fun areItemsTheSame(a: Series, b: Series) = a.id == b.id
            override fun areContentsTheSame(a: Series, b: Series) = a == b
        }
    }
}
