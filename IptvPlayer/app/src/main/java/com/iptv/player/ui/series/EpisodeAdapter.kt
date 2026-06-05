/*
 * EpisodeAdapter.kt
 * Episode list for the currently selected season. Focusable rows for D-pad;
 * clicking an episode starts on-demand playback.
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
import com.iptv.player.data.model.Episode

class EpisodeAdapter(
    private val onClicked: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.episodeNumber)
        private val title: TextView = itemView.findViewById(R.id.episodeTitle)
        private val plot: TextView = itemView.findViewById(R.id.episodePlot)

        fun bind(episode: Episode) {
            number.text = episode.episodeNumber.toString()
            title.text = episode.title
            if (episode.plot.isNullOrBlank()) {
                plot.visibility = View.GONE
            } else {
                plot.visibility = View.VISIBLE
                plot.text = episode.plot
            }
            itemView.setOnClickListener { onClicked(episode) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Episode>() {
            override fun areItemsTheSame(a: Episode, b: Episode) = a.id == b.id
            override fun areContentsTheSame(a: Episode, b: Episode) = a == b
        }
    }
}
