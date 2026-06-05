/*
 * GuideChannelAdapter.kt
 * Left-column channel list for the TV guide. Each row lazily loads its "now"
 * program via NowNextBinder and cancels that work on recycle to bound memory.
 * Focus drives the right-hand timeline; long-press opens EPG id matching.
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
import com.iptv.player.data.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class GuideChannelAdapter(
    private val scope: CoroutineScope,
    private val onFocused: (Channel) -> Unit,
    private val onClicked: (Channel) -> Unit,
    private val onLongPress: (Channel) -> Unit
) : ListAdapter<Channel, GuideChannelAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_guide_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.channelNumber)
        private val name: TextView = itemView.findViewById(R.id.channelName)
        private val now: TextView = itemView.findViewById(R.id.channelNow)
        private var nowJob: Job? = null

        fun bind(channel: Channel) {
            number.text = channel.number?.toString() ?: ""
            name.text = channel.name
            now.text = ""

            nowJob?.cancel()
            nowJob = NowNextBinder.bind(scope, channel, now)

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused(channel)
            }
            itemView.setOnClickListener { onClicked(channel) }
            itemView.setOnLongClickListener {
                onLongPress(channel)
                true
            }
        }

        fun recycle() {
            nowJob?.cancel()
            nowJob = null
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel) = a.id == b.id
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }
}
