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

/**
 * Dedicated 10-foot channel rail for inline fullscreen playback.
 *
 * It deliberately owns a separate adapter from the browse screen: the hidden
 * browser keeps its focus/selection state while this list follows the playing
 * category and updates the EPG for the row under the remote focus.
 */
class FullscreenChannelGuideAdapter(
    private val onFocused: (Channel) -> Unit,
    private val onClicked: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit,
) : ListAdapter<Channel, FullscreenChannelGuideAdapter.VH>(DIFF) {

    var lockedProvider: (Channel) -> Boolean = { false }

    private var playingChannelId: String? = null

    fun setPlayingChannel(channelId: String?) {
        if (playingChannelId == channelId) return
        val previous = playingChannelId
        playingChannelId = channelId
        currentList.indexOfFirst { it.id == previous }
            .takeIf { it >= 0 }
            ?.let { notifyItemChanged(it, PAYLOAD_PLAYING) }
        currentList.indexOfFirst { it.id == channelId }
            .takeIf { it >= 0 }
            ?.let { notifyItemChanged(it, PAYLOAD_PLAYING) }
    }

    fun replaceChannel(channel: Channel) {
        val position = currentList.indexOfFirst { it.id == channel.id }
        if (position < 0) return
        submitList(currentList.toMutableList().apply { this[position] = channel })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fullscreen_channel_guide, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PLAYING)) {
            holder.bindPlayingState(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.fullscreenGuideChannelNumber)
        private val logo: ImageView = itemView.findViewById(R.id.fullscreenGuideChannelLogo)
        private val name: TextView = itemView.findViewById(R.id.fullscreenGuideChannelName)
        private val liveBadge: TextView = itemView.findViewById(R.id.fullscreenGuideLiveBadge)
        private val favorite: ImageView = itemView.findViewById(R.id.fullscreenGuideFavorite)
        private val catchup: ImageView = itemView.findViewById(R.id.fullscreenGuideCatchup)

        private fun currentChannel(): Channel? {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || position >= itemCount) return null
            return getItem(position)
        }

        fun bind(channel: Channel) {
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) currentChannel()?.let(onFocused)
            }
            itemView.setOnClickListener {
                currentChannel()?.let(onClicked)
            }
            itemView.setOnLongClickListener {
                val current = currentChannel() ?: return@setOnLongClickListener false
                if (!lockedProvider(current)) onToggleFavorite(current)
                true
            }

            bindPlayingState(channel)
            if (lockedProvider(channel)) {
                number.text = ""
                name.setText(R.string.adult_locked_title)
                favorite.visibility = View.GONE
                catchup.visibility = View.GONE
                logo.load(R.drawable.ic_lock) { crossfade(false) }
                return
            }

            number.text = channel.number?.toString().orEmpty()
            name.text = ChannelText.clean(channel.name)
            favorite.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            catchup.visibility = if (channel.catchupDays > 0) View.VISIBLE else View.GONE
            val placeholder = LogoPlaceholder.forName(itemView.context, channel.name)
            if (channel.logoUrl.isNullOrBlank()) {
                logo.load(placeholder) { crossfade(false) }
            } else {
                logo.load(channel.logoUrl) {
                    crossfade(false)
                    placeholder(placeholder)
                    error(placeholder)
                    size(112, 84)
                }
            }
        }

        fun bindPlayingState(channel: Channel) {
            val playing = channel.id == playingChannelId
            itemView.isSelected = playing
            liveBadge.visibility = if (playing) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private const val PAYLOAD_PLAYING = "playing"

        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean =
                oldItem == newItem
        }
    }
}
