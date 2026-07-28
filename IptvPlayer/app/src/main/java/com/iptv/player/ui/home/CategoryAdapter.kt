/*
 * CategoryAdapter.kt
 * Left-pane category list. Uses DiffUtil for smooth updates and reports both
 * focus changes (to refresh the center channel list) and clicks.
 */
package com.iptv.player.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.model.Category

class CategoryAdapter(
    private val onFocused: (Category) -> Unit,
    private val onClicked: (Category) -> Unit
) : ListAdapter<Category, CategoryAdapter.VH>(DIFF) {

    /** Id of the category to show as "selected" (white outline) when focus is
     *  elsewhere (e.g. after drilling into its channels). */
    private var selectedId: String? = null

    fun setSelected(id: String?) {
        if (selectedId == id) return
        val prev = selectedId
        selectedId = id
        // Repaint ONLY the two affected rows, and via a payload so we don't rebind
        // their focus listeners. A blanket notifyDataSetChanged() here rebinds every
        // row on each focus move, which makes the RecyclerView lose the D-pad focus
        // (it snaps back to the top) — the user then can't scroll past the first
        // couple of categories. Targeted, payloaded updates keep focus intact.
        currentList.indexOfFirst { it.id == prev }
            .takeIf { it >= 0 }?.let { notifyItemChanged(it, PAYLOAD_SELECTION) }
        currentList.indexOfFirst { it.id == id }
            .takeIf { it >= 0 }?.let { notifyItemChanged(it, PAYLOAD_SELECTION) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        // Selection-only update: flip the outline without a full rebind, so the
        // currently focused row keeps its focus.
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.itemView.isSelected = getItem(position).id == selectedId
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.categoryTitle)
        private val count: TextView = itemView.findViewById(R.id.categoryCount)

        private fun currentCategory(): Category? {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || position >= itemCount) return null
            return getItem(position)
        }

        fun bind(category: Category) {
            title.text = category.name
            count.text = category.count?.toString() ?: ""
            itemView.isSelected = category.id == selectedId
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) currentCategory()?.let(onFocused)
            }
            itemView.setOnClickListener { currentCategory()?.let(onClicked) }
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<Category>() {
            override fun areItemsTheSame(a: Category, b: Category) = a.id == b.id
            override fun areContentsTheSame(a: Category, b: Category) = a == b
        }
    }
}
