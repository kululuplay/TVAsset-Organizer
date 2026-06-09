/*
 * CastAdapter.kt
 * Horizontal cast row. Each entry is a circular head-shot (from TMDB when
 * available) with a colored initial avatar as fallback, plus the actor name.
 * Display only (not focusable).
 */
package com.iptv.player.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.iptv.player.R
import com.iptv.player.data.model.CastMember

class CastAdapter : ListAdapter<CastMember, CastAdapter.VH>(DIFF) {

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
        private val photo: ImageView = itemView.findViewById(R.id.castPhoto)
        private val name: TextView = itemView.findViewById(R.id.castName)

        fun bind(person: CastMember) {
            name.text = person.name
            initial.text = person.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            // The colored initial sits underneath; the photo (when present)
            // covers it. Hiding the photo when there's no URL reveals the initial.
            if (person.photoUrl.isNullOrBlank()) {
                photo.visibility = View.GONE
                photo.setImageDrawable(null)
            } else {
                photo.visibility = View.VISIBLE
                photo.load(person.photoUrl) {
                    transformations(CircleCropTransformation())
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CastMember>() {
            override fun areItemsTheSame(a: CastMember, b: CastMember) = a.name == b.name
            override fun areContentsTheSame(a: CastMember, b: CastMember) = a == b
        }
    }
}
