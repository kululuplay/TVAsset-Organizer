/*
 * NewAddedAdapter.kt
 * Horizontal "Newly added" rail on the dashboard, mixing the most recently added
 * movies and series. Each card shows the poster, a NEW badge and the title.
 * Clicking opens the matching detail screen (VOD or series).
 */
package com.iptv.player.ui.dashboard

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
import com.iptv.player.ui.common.LogoPlaceholder

/** A movie or series shown in the newly-added rail. */
data class NewAddedItem(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val isSeries: Boolean
)

class NewAddedAdapter(
    private val onClicked: (NewAddedItem) -> Unit
) : ListAdapter<NewAddedItem, NewAddedAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_new_added, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.newPoster)
        private val title: TextView = itemView.findViewById(R.id.newTitle)

        fun bind(item: NewAddedItem) {
            title.text = item.title

            val placeholder = LogoPlaceholder.forName(itemView.context, item.title)
            if (item.posterUrl.isNullOrBlank()) {
                poster.setImageDrawable(placeholder)
            } else {
                poster.load(item.posterUrl) {
                    placeholder(placeholder); error(placeholder)
                }
            }

            itemView.setOnClickListener { onClicked(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NewAddedItem>() {
            override fun areItemsTheSame(a: NewAddedItem, b: NewAddedItem) =
                a.id == b.id && a.isSeries == b.isSeries

            override fun areContentsTheSame(a: NewAddedItem, b: NewAddedItem) = a == b
        }
    }
}
