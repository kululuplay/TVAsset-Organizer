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

    /**
     * Returns true when a row must hide its channel metadata behind the parental
     * lock. Kept as a predicate so Home can honour its session unlocks while the
     * global Search screen can apply the settings-level lock directly.
     */
    var lockedProvider: (Channel) -> Boolean = { false }

    /**
     * Re-binds only attached rows after parental-lock state changes. This avoids
     * replacing the list (and disturbing TV focus) merely to update masking.
     */
    fun refreshVisible(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val holder =
                recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? VH ?: continue
            holder.rebindCurrent()
        }
    }

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
        private val catchupBadge: ImageView = itemView.findViewById(R.id.catchupBadge)

        /**
         * Resolve callbacks against the adapter's current snapshot at event time.
         * AsyncListDiffer can replace/move a row after bind but before a delayed
         * focus/click/long-click arrives; capturing [channel] would then act on the
         * stale item that previously occupied this holder.
         */
        private fun currentChannel(): Channel? {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || position >= itemCount) return null
            return getItem(position)
        }

        fun rebindCurrent() {
            currentChannel()?.let(::bind)
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

            if (lockedProvider(channel)) {
                name.setText(R.string.adult_locked_title)
                number.text = ""
                favStar.visibility = View.GONE
                catchupBadge.visibility = View.GONE
                logo.scaleType = ImageView.ScaleType.FIT_CENTER
                // Use Coil for the replacement too: it cancels any in-flight
                // request for the real logo, preventing a late artwork leak.
                logo.load(R.drawable.ic_lock) {
                    crossfade(false)
                }
                return
            }

            name.text = ChannelText.clean(channel.name)
            number.text = channel.number?.toString() ?: ""
            favStar.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            catchupBadge.visibility = if (channel.catchupDays > 0) View.VISIBLE else View.GONE
            logo.scaleType = ImageView.ScaleType.FIT_CENTER

            val placeholder = LogoPlaceholder.forName(itemView.context, channel.name)
            if (channel.logoUrl.isNullOrBlank()) {
                logo.load(placeholder) {
                    crossfade(false)
                }
            } else {
                logo.load(channel.logoUrl) {
                    crossfade(false)
                    placeholder(placeholder)
                    error(placeholder)
                    // Downscale to the view size to save memory on cheap sticks.
                    size(120, 120)
                }
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
