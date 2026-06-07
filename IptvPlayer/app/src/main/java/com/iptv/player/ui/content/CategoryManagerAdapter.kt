/*
 * CategoryManagerAdapter.kt
 * Lists categories with their name, content count and a "hidden" badge. While a
 * row is being reordered it shows a "moving" badge. For Live, a trailing chevron
 * hints that ▶ drills into the channel editor. Row interactions (OK = show/hide,
 * hold = move, ▶ = drill) are handled by the activity.
 */
package com.iptv.player.ui.content

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.model.ManagedCategory

class CategoryManagerAdapter(
    private val canDrill: Boolean,
    private val onClicked: (ManagedCategory) -> Unit,
    private val onLongClicked: (ManagedCategory) -> Unit,
    private val onDrill: (ManagedCategory) -> Unit
) : ListAdapter<ManagedCategory, CategoryManagerAdapter.VH>(DIFF) {

    /** Id of the row currently in move mode (null = none). */
    var movingId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_managed_category, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.mcName)
        private val count: TextView = itemView.findViewById(R.id.mcCount)
        private val moving: TextView = itemView.findViewById(R.id.mcMoving)
        private val hiddenBadge: TextView = itemView.findViewById(R.id.mcHidden)
        private val arrow: ImageView = itemView.findViewById(R.id.mcArrow)

        fun bind(item: ManagedCategory) {
            name.text = item.category.name
            val c = item.category.count
            count.text = c?.toString() ?: ""
            count.visibility = if (c == null) View.GONE else View.VISIBLE
            hiddenBadge.visibility = if (item.hidden) View.VISIBLE else View.GONE
            moving.visibility = if (item.category.id == movingId) View.VISIBLE else View.GONE
            arrow.visibility = if (canDrill) View.VISIBLE else View.GONE
            itemView.alpha = if (item.hidden) 0.5f else 1f

            itemView.setOnClickListener { onClicked(item) }
            itemView.setOnLongClickListener { onLongClicked(item); true }
            if (canDrill) {
                itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                        movingId == null
                    ) {
                        onDrill(item); true
                    } else false
                }
            } else {
                itemView.setOnKeyListener(null)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ManagedCategory>() {
            override fun areItemsTheSame(a: ManagedCategory, b: ManagedCategory) =
                a.category.id == b.category.id
            override fun areContentsTheSame(a: ManagedCategory, b: ManagedCategory) = a == b
        }
    }
}
