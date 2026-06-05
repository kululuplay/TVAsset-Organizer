/*
 * VodAdapter.kt
 * Poster grid for VOD (movies). Loads posters with Coil and shows the rating
 * badge when available. DiffUtil keeps the grid smooth on weak devices.
 */
package com.iptv.player.ui.vod

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
        private val rating: TextView = itemView.findViewById(R.id.posterRating)
        private val star: ImageView = itemView.findViewById(R.id.posterStar)

        fun bind(item: VodItem) {
            name.text = item.name
            val ratingText = item.rating?.takeIf { it > 0 }?.let { String.format("%.1f", it) }
            if (ratingText != null) {
                rating.text = ratingText
                rating.visibility = View.VISIBLE
                star.visibility = View.VISIBLE
            } else {
                rating.visibility = View.GONE
                star.visibility = View.GONE
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
