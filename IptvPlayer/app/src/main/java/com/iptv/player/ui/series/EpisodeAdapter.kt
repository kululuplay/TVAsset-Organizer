/*
 * EpisodeAdapter.kt
 * Horizontal episode rail for the selected season: a 16:9 thumbnail with a
 * resume bar, an "S1 · E2" caption and the title. Focusable for D-pad; clicking
 * an episode starts on-demand playback.
 */
package com.iptv.player.ui.series

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
import com.iptv.player.data.model.Episode
import com.iptv.player.ui.common.LogoPlaceholder

class EpisodeAdapter(
    private val onClicked: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeAdapter.VH>(DIFF) {

    /** Saved positions (ms) keyed by episode id, used to draw per-card progress. */
    private var progressByEpisode: Map<String, Long> = emptyMap()

    /** Raw episode ids that have been fully watched, used to draw the tick. */
    private var watchedEpisodes: Set<String> = emptySet()

    /** Series poster, used when an episode has no still of its own. */
    var fallbackPoster: String? = null

    /**
     * Applies resume progress + watched ticks together in a single rebind, so the
     * rail isn't churned by two back-to-back full refreshes.
     */
    fun setWatchState(progress: Map<String, Long>, watched: Set<String>) {
        progressByEpisode = progress
        watchedEpisodes = watched
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_card, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.episodeThumb)
        private val number: TextView = itemView.findViewById(R.id.episodeNumber)
        private val title: TextView = itemView.findViewById(R.id.episodeTitle)
        private val progress: ProgressBar = itemView.findViewById(R.id.episodeProgress)
        private val watched: ImageView = itemView.findViewById(R.id.episodeWatched)

        fun bind(episode: Episode) {
            number.text = itemView.context.getString(
                R.string.episode_se_format, episode.seasonNumber, episode.episodeNumber
            )
            title.text = episode.title

            val placeholder = LogoPlaceholder.forName(itemView.context, episode.title)
            val art = episode.posterUrl?.takeIf { it.isNotBlank() } ?: fallbackPoster
            if (art.isNullOrBlank()) {
                thumb.setImageDrawable(placeholder)
            } else {
                thumb.load(art) {
                    crossfade(false)
                    placeholder(placeholder)
                    error(placeholder)
                }
            }

            val isWatched = episode.id in watchedEpisodes
            watched.visibility = if (isWatched) View.VISIBLE else View.GONE

            val positionMs = progressByEpisode[episode.id] ?: 0L
            val durationMs = (episode.durationSecs ?: 0) * 1000L
            if (!isWatched && positionMs > 0L && durationMs > 0L) {
                progress.progress = ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)
                progress.visibility = View.VISIBLE
            } else {
                progress.visibility = View.GONE
            }
            itemView.setOnClickListener { onClicked(episode) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Episode>() {
            override fun areItemsTheSame(a: Episode, b: Episode) = a.id == b.id
            override fun areContentsTheSame(a: Episode, b: Episode) = a == b
        }
    }
}
