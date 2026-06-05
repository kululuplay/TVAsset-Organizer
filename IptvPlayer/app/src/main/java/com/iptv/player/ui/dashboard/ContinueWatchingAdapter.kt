/*
 * ContinueWatchingAdapter.kt
 * Horizontal rail of in-progress movies and episodes shown on the dashboard.
 * Each card shows the poster, a resume-progress bar, the title and (for
 * episodes) an SxEy subtitle. Clicking a card resumes from the saved position.
 */
package com.iptv.player.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.ui.common.LogoPlaceholder

class ContinueWatchingAdapter(
    private val onClicked: (ContinueItem) -> Unit
) : ListAdapter<ContinueItem, ContinueWatchingAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.continuePoster)
        private val progress: ProgressBar = itemView.findViewById(R.id.continueProgress)
        private val title: TextView = itemView.findViewById(R.id.continueTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.continueSubtitle)

        fun bind(item: ContinueItem) {
            title.text = item.title

            val placeholder = LogoPlaceholder.forName(itemView.context, item.title)
            if (item.posterUrl.isNullOrBlank()) {
                poster.setImageDrawable(placeholder)
            } else {
                poster.load(item.posterUrl) {
                    placeholder(placeholder); error(placeholder)
                }
            }

            progress.progress = item.progressPercent

            if (item.kind == ResumeKind.EPISODE) {
                subtitle.text = itemView.context.getString(
                    R.string.episode_se_format, item.seasonNumber, item.episodeNumber
                )
                subtitle.visibility = View.VISIBLE
            } else {
                subtitle.visibility = View.GONE
            }

            itemView.setOnClickListener { onClicked(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ContinueItem>() {
            override fun areItemsTheSame(a: ContinueItem, b: ContinueItem) =
                a.contentId == b.contentId

            override fun areContentsTheSame(a: ContinueItem, b: ContinueItem) = a == b
        }
    }
}
