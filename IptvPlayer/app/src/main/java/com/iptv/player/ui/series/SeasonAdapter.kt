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
        val prev = selectedNumber
        selectedNumber = seasonNumber
        // Repaint ONLY the two affected chips, via a payload so we don't rebind
        // their focus listeners. setSelected() is driven by the focus listener
        // (focusing a season selects it); a blanket notifyDataSetChanged() here
        // rebinds every chip on each D-pad move, which makes the horizontal
        // RecyclerView lose focus and snap back — the user then can't reach past
        // the first season or two. Targeted, payloaded updates keep focus intact.
        currentList.indexOfFirst { it.seasonNumber == prev }
            .takeIf { it >= 0 }?.let { notifyItemChanged(it, PAYLOAD_SELECTION) }
        currentList.indexOfFirst { it.seasonNumber == seasonNumber }
            .takeIf { it >= 0 }?.let { notifyItemChanged(it, PAYLOAD_SELECTION) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_season, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        // Selection-only update: flip the highlight without a full rebind, so the
        // currently focused chip keeps its D-pad focus.
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.itemView.isSelected = getItem(position).seasonNumber == selectedNumber
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
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
        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<Season>() {
            override fun areItemsTheSame(a: Season, b: Season) =
                a.seriesId == b.seriesId && a.seasonNumber == b.seasonNumber
            override fun areContentsTheSame(a: Season, b: Season) = a == b
        }
    }
}
