/*
 * SeasonAdapter.kt
 * Horizontal season selector chips. The chosen season stays highlighted (white
 * card) even when focus moves down into the episode rail. Reports the selected
 * season on focus / click.
 */
package com.iptv.player.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.model.Season

class SeasonAdapter(
    private val onSelected: (Season) -> Unit
) : ListAdapter<Season, SeasonAdapter.VH>(DIFF) {

    /** Season number to render as "selected" while focus is on the episodes. */
    private var selectedNumber: Int? = null

    fun setSelected(seasonNumber: Int?) {
        if (selectedNumber == seasonNumber) return
        selectedNumber = seasonNumber
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_season, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.categoryTitle)

        fun bind(season: Season) {
            title.text = itemView.context.getString(R.string.series_season, season.seasonNumber)
            itemView.isSelected = season.seasonNumber == selectedNumber
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onSelected(season)
            }
            itemView.setOnClickListener { onSelected(season) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Season>() {
            override fun areItemsTheSame(a: Season, b: Season) =
                a.seriesId == b.seriesId && a.seasonNumber == b.seasonNumber
            override fun areContentsTheSame(a: Season, b: Season) = a == b
        }
    }
}
