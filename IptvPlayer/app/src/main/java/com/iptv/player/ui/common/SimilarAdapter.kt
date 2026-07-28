/*
 * SimilarAdapter.kt
 * A lightweight, non-paged poster rail used by the movie/series detail screens to
 * show "Similar" recommendations. Reuses item_poster.xml (without the resume
 * progress / watched badge) and is type-agnostic via [SimilarCard], so both
 * VodItem and Series lists can feed the same rail.
 */
package com.iptv.player.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R

/** Minimal poster card data, mapped from either a VodItem or a Series. */
data class SimilarCard(
    val id: String,
    val name: String,
    val posterUrl: String?,
    val rating: Double?,
    val releaseDate: String?,
    val categoryName: String?,
)

class SimilarAdapter(
    private val onClicked: (SimilarCard) -> Unit
) : ListAdapter<SimilarCard, SimilarAdapter.VH>(DIFF) {

    var adultLocked: Boolean = false

    fun refreshVisible(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val holder =
                recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? VH ?: continue
            holder.boundCard?.let { holder.bind(it) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poster, parent, false)
        // item_poster is match_parent (grid use); pin a fixed width for the
        // horizontal rail so each poster keeps its 2:3 shape instead of stretching.
        view.layoutParams = RecyclerView.LayoutParams(
            parent.context.resources.getDimensionPixelSize(R.dimen.similar_poster_width),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.posterImage)
        private val name: TextView = itemView.findViewById(R.id.posterName)
        private val meta: TextView = itemView.findViewById(R.id.posterMeta)
        private val ratingRow: LinearLayout = itemView.findViewById(R.id.posterRatingRow)
        private val rating: TextView = itemView.findViewById(R.id.posterRating)

        var boundCard: SimilarCard? = null
            private set

        fun bind(item: SimilarCard) {
            boundCard = item
            itemView.setOnClickListener { onClicked(item) }

            if (
                adultLocked &&
                (PinLockHelper.looksAdult(item.name) ||
                    PinLockHelper.looksAdult(item.categoryName))
            ) {
                name.setText(R.string.adult_locked_title)
                meta.visibility = View.GONE
                ratingRow.visibility = View.GONE
                poster.scaleType = ImageView.ScaleType.FIT_CENTER
                poster.load(R.drawable.ic_lock) { crossfade(false) }
                return
            }

            poster.scaleType = ImageView.ScaleType.CENTER_CROP
            name.text = item.name

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
                poster.load(placeholder) { crossfade(false) }
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
        private val DIFF = object : DiffUtil.ItemCallback<SimilarCard>() {
            override fun areItemsTheSame(a: SimilarCard, b: SimilarCard) = a.id == b.id
            override fun areContentsTheSame(a: SimilarCard, b: SimilarCard) = a == b
        }
    }
}
