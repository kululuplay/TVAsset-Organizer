/*
 * ProfileAdapter.kt
 * Lists saved profiles (subscriptions). Click switches the active profile;
 * long-press deletes it. The active profile shows a small "Active" badge.
 */
package com.iptv.player.ui.profiles

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.model.Profile

class ProfileAdapter(
    private val onClicked: (Profile) -> Unit,
    private val onLongClicked: (Profile) -> Unit
) : ListAdapter<Profile, ProfileAdapter.VH>(DIFF) {

    var activeProfileId: Long = -1L
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.profileName)
        private val summary: TextView = itemView.findViewById(R.id.profileSummary)
        private val activeBadge: TextView = itemView.findViewById(R.id.profileActiveBadge)

        fun bind(profile: Profile) {
            name.text = profile.name
            summary.text = profile.config.serverUrl.ifBlank { profile.config.m3uUrl }
            activeBadge.visibility =
                if (profile.id == activeProfileId) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onClicked(profile) }
            itemView.setOnLongClickListener {
                onLongClicked(profile)
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Profile>() {
            override fun areItemsTheSame(a: Profile, b: Profile) = a.id == b.id
            override fun areContentsTheSame(a: Profile, b: Profile) = a == b
        }
    }
}
