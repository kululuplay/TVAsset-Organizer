/*
 * GuideProgramAdapter.kt
 * Horizontal timeline of programs for the focused channel. Each card's width is
 * proportional to its duration (epg_minute_width per minute), and the live
 * program (Program.isLiveAt(now)) is highlighted with a "Now" badge. DiffUtil
 * keeps the timeline smooth when switching channels.
 */
package com.iptv.player.ui.guide

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.model.Program

class GuideProgramAdapter(
    private val onClicked: (Program) -> Unit
) : ListAdapter<Program, GuideProgramAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_guide_program, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val time: TextView = itemView.findViewById(R.id.programTime)
        private val live: TextView = itemView.findViewById(R.id.programLive)
        private val title: TextView = itemView.findViewById(R.id.programTitle)

        fun bind(program: Program) {
            val now = System.currentTimeMillis()
            time.text = EpgTimeFormatter.range(program)
            title.text = program.title
            live.visibility = if (program.isLiveAt(now)) View.VISIBLE else View.GONE

            // Width proportional to duration so the row reads as a timeline.
            val minuteWidth = itemView.resources.getDimensionPixelSize(R.dimen.epg_minute_width)
            val minutes = ((program.stopMs - program.startMs) / 60_000L).toInt().coerceIn(15, 240)
            val params = itemView.layoutParams
            params.width = minutes * minuteWidth
            itemView.layoutParams = params

            itemView.setOnClickListener { onClicked(program) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Program>() {
            override fun areItemsTheSame(a: Program, b: Program) =
                a.epgChannelId == b.epgChannelId && a.startMs == b.startMs
            override fun areContentsTheSame(a: Program, b: Program) = a == b
        }
    }
}
