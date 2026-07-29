/*
 * SearchChannelAdapter.kt
 * Compact, search-specific Live TV rows with strict recycling and parental
 * masking. Search does not expose favorite mutation, so this adapter deliberately
 * has only focus and open actions.
 */
package com.iptv.player.ui.search

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
import com.iptv.player.ui.common.isAdult

class SearchChannelAdapter(
    private val onFocused: (Channel, Int) -> Unit,
    private val onClicked: (Channel) -> Unit,
) : ListAdapter<Channel, SearchChannelAdapter.VH>(DIFF) {

    var adultLocked: Boolean = false

    init {
        stateRestorationPolicy =
            RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    fun refreshVisible(recyclerView: RecyclerView) {
        for (index in 0 until recyclerView.childCount) {
            val holder =
                recyclerView.getChildViewHolder(recyclerView.getChildAt(index)) as? VH
                    ?: continue
            holder.rebindCurrent()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_channel, parent, false),
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: VH) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logo: ImageView = itemView.findViewById(R.id.searchChannelLogo)
        private val number: TextView = itemView.findViewById(R.id.searchChannelNumber)
        private val name: TextView = itemView.findViewById(R.id.searchChannelName)
        private val category: TextView = itemView.findViewById(R.id.searchChannelCategory)
        private val catchup: ImageView = itemView.findViewById(R.id.searchChannelCatchup)
        private val favorite: ImageView = itemView.findViewById(R.id.searchChannelFavorite)

        private var boundId: String? = null

        fun bind(channel: Channel) {
            boundId = channel.id
            itemView.setOnClickListener {
                currentChannel()?.let(onClicked)
            }
            itemView.setOnFocusChangeListener { _, focused ->
                if (!focused) return@setOnFocusChangeListener
                val position = bindingAdapterPosition
                val current = currentChannel()
                if (position != RecyclerView.NO_POSITION && current != null) {
                    onFocused(current, position)
                }
            }

            if (adultLocked && channel.isAdult()) {
                name.setText(R.string.adult_locked_title)
                number.text = ""
                number.visibility = View.INVISIBLE
                category.text = ""
                category.visibility = View.INVISIBLE
                catchup.visibility = View.GONE
                favorite.visibility = View.GONE
                logo.scaleType = ImageView.ScaleType.FIT_CENTER
                logo.contentDescription = null
                logo.load(R.drawable.ic_lock) { crossfade(false) }
                itemView.contentDescription =
                    itemView.context.getString(R.string.adult_locked_title)
                return
            }

            name.text = ChannelText.clean(channel.name)
            number.text = channel.number?.toString().orEmpty()
            number.visibility =
                if (channel.number == null) View.INVISIBLE else View.VISIBLE
            category.text = channel.categoryName.orEmpty()
            category.visibility =
                if (channel.categoryName.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
            catchup.visibility = if (channel.catchupDays > 0) View.VISIBLE else View.GONE
            favorite.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            logo.scaleType = ImageView.ScaleType.FIT_CENTER
            logo.contentDescription = null

            val placeholder = LogoPlaceholder.forName(itemView.context, channel.name)
            if (channel.logoUrl.isNullOrBlank()) {
                logo.load(placeholder) { crossfade(false) }
            } else {
                logo.load(channel.logoUrl) {
                    crossfade(false)
                    placeholder(placeholder)
                    error(placeholder)
                    size(120, 120)
                }
            }

            itemView.contentDescription = buildList {
                channel.number?.let { add(it.toString()) }
                add(ChannelText.clean(channel.name))
                channel.categoryName?.takeIf(String::isNotBlank)?.let(::add)
                if (channel.catchupDays > 0) {
                    add(itemView.context.getString(R.string.catchup_title))
                }
                if (channel.isFavorite) {
                    add(itemView.context.getString(R.string.section_favorites))
                }
            }.joinToString(", ")
        }

        fun rebindCurrent() {
            currentChannel()?.let(::bind)
        }

        fun clear() {
            boundId = null
            itemView.setOnClickListener(null)
            itemView.onFocusChangeListener = null
            itemView.contentDescription = null
            number.text = ""
            number.visibility = View.INVISIBLE
            name.text = ""
            category.text = ""
            category.visibility = View.INVISIBLE
            catchup.visibility = View.GONE
            favorite.visibility = View.GONE
            logo.contentDescription = null
            logo.load(null) { crossfade(false) }
        }

        private fun currentChannel(): Channel? {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || position >= itemCount) return null
            val current = getItem(position)
            return current.takeIf { boundId == null || it.id == boundId }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
                return oldItem == newItem
            }
        }
    }
}
