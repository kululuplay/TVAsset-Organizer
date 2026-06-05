/*
 * CatchupProgramAdapter.kt
 * Right-pane list of past programs (newest first) for the focused channel. Each
 * row shows the program title and its start date/time; clicking plays it back
 * from the archive.
 */
package com.iptv.player.ui.catchup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.model.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CatchupProgramAdapter(
    private val onClicked: (Program) -> Unit
) : ListAdapter<Program, CatchupProgramAdapter.VH>(DIFF) {

    private val formatter = SimpleDateFormat("EEE dd MMM • HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_catchup_program, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.programTitle)
        private val time: TextView = itemView.findViewById(R.id.programTime)

        fun bind(program: Program) {
            title.text = program.title
            time.text = "${formatter.format(Date(program.startMs))} – ${
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(program.stopMs))
            }"
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
