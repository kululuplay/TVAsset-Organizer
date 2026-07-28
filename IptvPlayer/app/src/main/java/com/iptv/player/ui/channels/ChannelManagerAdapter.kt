/*
 * ChannelManagerAdapter.kt
 * Lists live channels with their number, name, a favourite star and a "hidden"
 * badge; while a row is being reordered it shows a "moving" badge. Row
 * interactions (OK = show/hide, hold = move, ▶ = favourite) are handled by the
 * activity.
 */
package com.iptv.player.ui.channels

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.model.ManagedChannel

class ChannelManagerAdapter(
    private val onClicked: (ManagedChannel) -> Unit,
    private val onLongClicked: (ManagedChannel) -> Unit,
    private val onFavorite: (ManagedChannel) -> Unit,
    private val onFocused: (String) -> Unit
) : ListAdapter<ManagedChannel, ChannelManagerAdapter.VH>(DIFF) {

    /** Id of the row currently in move mode (null = none). */
    var movingId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_managed_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.mcNumber)
        private val name: TextView = itemView.findViewById(R.id.mcName)
        private val favorite: ImageView = itemView.findViewById(R.id.mcFavorite)
        private val moving: TextView = itemView.findViewById(R.id.mcMoving)
        private val hiddenBadge: TextView = itemView.findViewById(R.id.mcHidden)

        init {
            itemView.id = View.generateViewId()
        }

        fun bind(item: ManagedChannel) {
            number.text = item.channel.number?.toString() ?: ""
            name.text = item.channel.name
            favorite.visibility = if (item.isFavorite) View.VISIBLE else View.GONE
            hiddenBadge.visibility = if (item.hidden) View.VISIBLE else View.GONE
            val isMoving = item.channel.id == movingId
            moving.visibility = if (isMoving) View.VISIBLE else View.GONE
            itemView.isSelected = isMoving
            name.alpha = if (item.hidden) 0.62f else 1f
            number.alpha = if (item.hidden) 0.62f else 1f
            itemView.contentDescription = listOfNotNull(
                item.channel.number?.toString(),
                item.channel.name
            ).joinToString(". ")
            val states = buildList {
                if (isMoving) add(itemView.context.getString(R.string.manager_moving))
                if (item.hidden) {
                    add(itemView.context.getString(R.string.channel_manager_hidden))
                }
                add(
                    itemView.context.getString(
                        if (item.isFavorite) {
                            R.string.favorite_state_on
                        } else {
                            R.string.favorite_state_off
                        }
                    )
                )
            }
            ViewCompat.setStateDescription(itemView, states.joinToString(" · "))
            itemView.nextFocusLeftId = itemView.id
            itemView.nextFocusRightId = itemView.id

            itemView.setOnClickListener { onClicked(item) }
            itemView.setOnLongClickListener { onLongClicked(item); true }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused(item.channel.id)
            }
            val favoriteKey = if (itemView.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                KeyEvent.KEYCODE_DPAD_LEFT
            } else {
                KeyEvent.KEYCODE_DPAD_RIGHT
            }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (keyCode != favoriteKey || movingId != null) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    onFavorite(item)
                }
                true
            }
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
