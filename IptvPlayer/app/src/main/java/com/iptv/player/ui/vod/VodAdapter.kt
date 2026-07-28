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
import java.text.NumberFormat
import java.util.Locale

class VodAdapter(
    private val onFocused: (VodItem) -> Unit,
    private val onClicked: (VodItem) -> Unit
) : PagingDataAdapter<VodItem, VodAdapter.VH>(DIFF) {

    /**
     * Optional position-aware focus callback used by the movie catalog to
     * restore the exact poster after opening/closing the detail screen. Keeping
     * this separate preserves the adapter API used by the global Search screen.
     */
    var onFocusedAt: ((VodItem, Int) -> Unit)? = null

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

    init {
        // RecyclerView must not restore a stale child position while Paging is
        // still empty. It will restore after the first committed page instead.
        stateRestorationPolicy =
            RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    /**
     * Re-binds only the currently attached cards so resume bars / watched ticks /
     * adult masking pick up state that changed while away — WITHOUT touching the
     * adapter's item notifications. Calling notifyItemRangeChanged(0, itemCount)
     * on a Paging adapter races its own async page invalidation (lazy Room writes
     * invalidate the PagingSource) and throws "Inconsistency detected" -> a crash
     * that drops the user back to the Dashboard. A direct holder rebind avoids it.
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
        // Paging placeholders are disabled today, but keep this holder safe if
        // that policy changes: never leave recycled art/click listeners visible.
        val item = getItem(position)
        if (item == null) holder.clear() else holder.bind(item)
    }

    override fun onViewRecycled(holder: VH) {
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
        private val watched: ImageView = itemView.findViewById(R.id.posterWatched)

        /** Last item bound here, so [refreshVisible] can re-bind without Paging. */
        var boundItem: VodItem? = null
            private set

        fun bind(item: VodItem) {
            boundItem = item
            itemView.setOnClickListener {
                currentItem()?.let(onClicked)
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val position = bindingAdapterPosition
                    currentItem()?.let { current ->
                        onFocused(current)
                        if (position != RecyclerView.NO_POSITION) {
                            onFocusedAt?.invoke(current, position)
                        }
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
                watched.visibility = View.GONE
                poster.scaleType = ImageView.ScaleType.FIT_CENTER
                poster.contentDescription = null
                poster.load(R.drawable.ic_lock) { crossfade(false) }
                itemView.contentDescription =
                    itemView.context.getString(R.string.adult_locked_title)
                return
            }
            poster.scaleType = ImageView.ScaleType.CENTER_CROP
            poster.contentDescription = null

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
            // Keep every card the same height. GONE made undated movies one text
            // line shorter, producing jagged rows and unreliable vertical focus.
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

            // The whole poster card is the single accessibility target. Avoid a
            // duplicate image announcement and expose the useful state together.
            itemView.contentDescription = buildList {
                add(item.name)
                year?.let(::add)
                ratingText?.let {
                    add("${itemView.context.getString(R.string.detail_rating)} $it")
                }
                when {
                    isWatched -> add(itemView.context.getString(R.string.cd_watched))
                    pct in 1..99 -> {
                        val percent = NumberFormat
                            .getPercentInstance(Locale.getDefault())
                            .format(pct / 100.0)
                        add("${itemView.context.getString(R.string.resume_continue)} $percent")
                    }
                }
            }.joinToString(", ")
        }

        fun clear() {
            boundItem = null
            itemView.setOnClickListener(null)
            itemView.setOnFocusChangeListener(null)
            itemView.contentDescription = null
            name.text = ""
            meta.text = ""
            meta.visibility = View.INVISIBLE
            rating.text = ""
            ratingRow.visibility = View.GONE
            progressBar.progress = 0
            progressBar.visibility = View.GONE
            watched.visibility = View.GONE
            poster.contentDescription = null
            // Starting an empty request replaces/cancels any prior ImageView
            // request, preventing a late recycled-poster result from flashing.
            poster.load(null) { crossfade(false) }
        }

        private fun currentItem(): VodItem? {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return null
            return peek(position)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VodItem>() {
            override fun areItemsTheSame(a: VodItem, b: VodItem) = a.id == b.id
            override fun areContentsTheSame(a: VodItem, b: VodItem) = a == b
        }
    }
}
