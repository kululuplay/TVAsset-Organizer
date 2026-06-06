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
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.VodItem
import com.iptv.player.ui.common.LogoPlaceholder

class VodAdapter(
    private val onFocused: (VodItem) -> Unit,
    private val onClicked: (VodItem) -> Unit
) : ListAdapter<VodItem, VodAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poster, parent, false)
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

        fun bind(item: VodItem) {
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
                poster.setImageDrawable(placeholder)
            } else {
                poster.load(item.posterUrl) {
                    crossfade(false)
                    placeholder(placeholder)
                    error(placeholder)
                }
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused(item)
            }
            itemView.setOnClickListener { onClicked(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VodItem>() {
            override fun areItemsTheSame(a: VodItem, b: VodItem) = a.id == b.id
            override fun areContentsTheSame(a: VodItem, b: VodItem) = a == b
        }
    }
}
