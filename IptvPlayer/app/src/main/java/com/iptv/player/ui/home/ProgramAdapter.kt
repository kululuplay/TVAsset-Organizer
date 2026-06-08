/*
 * ProgramAdapter.kt
 * Right-pane EPG schedule list. Each row shows a status dot (emerald when the
 * program is airing now, muted gray otherwise), the program title, and its
 * start/stop time range. Display only — rows are not focusable.
 */
package com.iptv.player.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.model.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProgramAdapter : ListAdapter<Program, ProgramAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_program, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Rebinds visible rows so the "live now" dot tracks wall-clock program
     * boundaries. Rows aren't focusable, so a full-range rebind is safe on TV.
     */
    fun refreshLiveState() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dot: View = itemView.findViewById(R.id.programDot)
        private val title: TextView = itemView.findViewById(R.id.programTitle)
        private val time: TextView = itemView.findViewById(R.id.programTime)

        fun bind(program: Program) {
            title.text = program.title
            time.text = "${timeFmt.format(Date(program.startMs))} : ${timeFmt.format(Date(program.stopMs))}"
            val live = program.isLiveAt(System.currentTimeMillis())
            val tint = ContextCompat.getColor(
                itemView.context,
                if (live) R.color.accent_emerald else R.color.text_muted
            )
            ViewCompat.setBackgroundTintList(dot, android.content.res.ColorStateList.valueOf(tint))
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
