/*
 * ChannelManagerAdapter.kt
 * Lists every live channel with its number, name and a "hidden" badge. Clicking a
 * row opens an actions dialog (show/hide, move up, move down) in the activity.
 */
package com.iptv.player.ui.channels

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.model.ManagedChannel

class ChannelManagerAdapter(
    private val onClicked: (ManagedChannel) -> Unit
) : ListAdapter<ManagedChannel, ChannelManagerAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_managed_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.mcNumber)
        private val name: TextView = itemView.findViewById(R.id.mcName)
        private val hiddenBadge: TextView = itemView.findViewById(R.id.mcHidden)

        fun bind(item: ManagedChannel) {
            number.text = item.channel.number?.toString() ?: ""
            name.text = item.channel.name
            hiddenBadge.visibility = if (item.hidden) View.VISIBLE else View.GONE
            itemView.alpha = if (item.hidden) 0.5f else 1f
            itemView.setOnClickListener { onClicked(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ManagedChannel>() {
            override fun areItemsTheSame(a: ManagedChannel, b: ManagedChannel) =
                a.channel.id == b.channel.id
            override fun areContentsTheSame(a: ManagedChannel, b: ManagedChannel) = a == b
        }
    }
}
