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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.categoryTitle)

        fun bind(category: Category) {
            title.text = category.name
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused(category)
            }
            itemView.setOnClickListener { onClicked(category) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Category>() {
            override fun areItemsTheSame(a: Category, b: Category) = a.id == b.id
            override fun areContentsTheSame(a: Category, b: Category) = a == b
        }
    }
}
