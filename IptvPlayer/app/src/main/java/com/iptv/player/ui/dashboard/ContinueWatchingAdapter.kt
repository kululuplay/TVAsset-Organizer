/*
 * ContinueWatchingAdapter.kt
 * TV-first grid of in-progress movies and episodes.
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
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.ui.common.LogoPlaceholder
import java.text.NumberFormat

class ContinueWatchingAdapter(
    private val onClicked: (ContinueItem) -> Unit,
    private val onLongClicked: (ContinueItem) -> Unit,
    private val onFocused: (String, Int) -> Unit,
) : ListAdapter<ContinueItem, ContinueWatchingAdapter.VH>(DIFF) {

    var lockedAdultIds: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PRIVACY_PAYLOAD)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.continuePoster)
        private val progress: ProgressBar = itemView.findViewById(R.id.continueProgress)
        private val title: TextView = itemView.findViewById(R.id.continueTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.continueSubtitle)
        private val percent: TextView = itemView.findViewById(R.id.continuePercent)

        fun bind(item: ContinueItem) {
            itemView.setOnClickListener { onClicked(item) }
            itemView.setOnLongClickListener {
                onLongClicked(item)
                true
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (hasFocus && position != RecyclerView.NO_POSITION) {
                    onFocused(item.contentId, position)
                }
            }

            poster.dispose()

            if (item.contentId in lockedAdultIds) {
                title.text = itemView.context.getString(R.string.adult_locked_title)
                poster.scaleType = ImageView.ScaleType.FIT_CENTER
                poster.load(R.drawable.ic_lock) { crossfade(false) }
                subtitle.visibility = View.GONE
                percent.visibility = View.GONE
                progress.visibility = View.GONE
                itemView.contentDescription =
                    itemView.context.getString(R.string.adult_locked_title)
                ViewCompat.setStateDescription(itemView, null)
                return
            }

            title.text = item.title
            poster.scaleType = ImageView.ScaleType.CENTER_CROP
            subtitle.visibility = View.VISIBLE
            percent.visibility = View.VISIBLE
            progress.visibility = View.VISIBLE

            val placeholder = LogoPlaceholder.forName(itemView.context, item.title)
            if (item.posterUrl.isNullOrBlank()) {
                // Starting a local Coil request also cancels any late network
                // request owned by this recycled ImageView.
                poster.load(placeholder) { crossfade(false) }
            } else {
                poster.load(item.posterUrl) {
                    placeholder(placeholder); error(placeholder)
                }
            }

            progress.progress = item.progressPercent
            percent.text = NumberFormat.getPercentInstance().apply {
                maximumFractionDigits = 0
            }.format(item.progressPercent / 100.0)

            val typeLabel = if (item.kind == ResumeKind.EPISODE) {
                itemView.context.getString(
                    R.string.episode_se_format, item.seasonNumber, item.episodeNumber
                )
            } else {
                itemView.context.getString(R.string.nav_movies)
            }
            subtitle.text = typeLabel
            itemView.contentDescription = listOf(item.title, typeLabel).joinToString(". ")
            ViewCompat.setStateDescription(
                itemView,
                itemView.context.getString(
                    R.string.collections_progress_percent,
                    item.progressPercent
                )
            )
        }

        fun recycle() {
            poster.dispose()
            poster.setImageDrawable(null)
            itemView.setOnClickListener(null)
            itemView.setOnLongClickListener(null)
            itemView.setOnFocusChangeListener(null)
            itemView.contentDescription = null
            ViewCompat.setStateDescription(itemView, null)
        }
    }

    companion object {
        private const val PRIVACY_PAYLOAD = "privacy"

        private val DIFF = object : DiffUtil.ItemCallback<ContinueItem>() {
            override fun areItemsTheSame(a: ContinueItem, b: ContinueItem) =
                a.contentId == b.contentId

            override fun areContentsTheSame(a: ContinueItem, b: ContinueItem) = a == b
        }
    }
}
