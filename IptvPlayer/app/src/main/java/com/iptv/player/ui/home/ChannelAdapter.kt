/*
 * ChannelAdapter.kt
 * Center-pane channel list. Loads logos with Coil (downscaled + cached), shows a
 * colored initial placeholder when no logo, and supports long-press OK to toggle
 * favorites. DiffUtil keeps scrolling smooth on weak devices.
 */
package com.iptv.player.ui.home

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
import com.iptv.player.ui.common.ChannelText
import com.iptv.player.ui.common.LogoPlaceholder

class ChannelAdapter(
    private val onFocused: (Channel) -> Unit,
    private val onClicked: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logo: ImageView = itemView.findViewById(R.id.channelLogo)
        private val name: TextView = itemView.findViewById(R.id.channelName)
        private val number: TextView = itemView.findViewById(R.id.channelNumber)
        private val favStar: ImageView = itemView.findViewById(R.id.favStar)

        fun bind(channel: Channel) {
            name.text = ChannelText.clean(channel.name)
            number.text = channel.number?.toString() ?: ""
            favStar.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE

            val placeholder = LogoPlaceholder.forName(itemView.context, channel.name)
            if (channel.logoUrl.isNullOrBlank()) {
                logo.setImageDrawable(placeholder)
            } else {
                logo.load(channel.logoUrl) {
                    crossfade(false)
                    placeholder(placeholder)
                    error(placeholder)
                    // Downscale to the view size to save memory on cheap sticks.
                    size(120, 120)
                }
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused(channel)
            }
            itemView.setOnClickListener { onClicked(channel) }
            itemView.setOnLongClickListener {
                onToggleFavorite(channel)
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel) = a.id == b.id
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }
}
