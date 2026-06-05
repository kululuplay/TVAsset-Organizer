/*
 * CatchupChannelAdapter.kt
 * Left-pane list of catch-up capable channels. Reuses item_channel; the favorite
 * star is hidden and the program line shows the archive window in days.
 */
package com.iptv.player.ui.catchup

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
import com.iptv.player.data.model.Channel
import com.iptv.player.ui.common.LogoPlaceholder

class CatchupChannelAdapter(
    private val onFocused: (Channel) -> Unit,
    private val onClicked: (Channel) -> Unit
) : ListAdapter<Channel, CatchupChannelAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logo: ImageView = itemView.findViewById(R.id.channelLogo)
        private val name: TextView = itemView.findViewById(R.id.channelName)
        private val number: TextView = itemView.findViewById(R.id.channelNumber)
        private val program: TextView = itemView.findViewById(R.id.channelProgram)
        private val favStar: ImageView = itemView.findViewById(R.id.favStar)

        fun bind(channel: Channel) {
            name.text = channel.name
            number.text = channel.number?.toString() ?: ""
            favStar.visibility = View.GONE
            program.visibility = View.VISIBLE
            program.text = itemView.context.getString(R.string.catchup_days, channel.catchupDays)

            val placeholder = LogoPlaceholder.forName(itemView.context, channel.name)
            if (channel.logoUrl.isNullOrBlank()) {
                logo.setImageDrawable(placeholder)
            } else {
                logo.load(channel.logoUrl) {
                    crossfade(false); placeholder(placeholder); error(placeholder); size(120, 120)
                }
            }

            itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused(channel) }
            itemView.setOnClickListener { onClicked(channel) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel) = a.id == b.id
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }
}
