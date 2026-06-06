/*
 * CastAdapter.kt
 * Horizontal cast row. We only have cast names (no head-shots from the source),
 * so each entry is a circular initial avatar + name. Display only (not focusable).
 */
package com.iptv.player.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R

class CastAdapter : ListAdapter<String, CastAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cast, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val initial: TextView = itemView.findViewById(R.id.castInitial)
        private val name: TextView = itemView.findViewById(R.id.castName)

        fun bind(person: String) {
            name.text = person
            initial.text = person.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        }
    }

    companion object {
        /** Splits a comma-separated cast string into trimmed, non-blank names. */
        fun parse(raw: String?): List<String> =
            raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}
