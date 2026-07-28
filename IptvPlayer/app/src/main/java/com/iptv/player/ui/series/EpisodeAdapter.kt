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
    private val onFocused: (Episode, Int) -> Unit,
    private val onClicked: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeAdapter.VH>(DIFF) {

    /** Saved positions (ms) keyed by episode id, used to draw per-card progress. */
    private var progressByEpisode: Map<String, Long> = emptyMap()

    /** Raw episode ids that have been fully watched, used to draw the tick. */
    private var watchedEpisodes: Set<String> = emptySet()

    /** Series poster, used when an episode has no still of its own. */
    var fallbackPoster: String? = null
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_ART)
        }

    /**
     * Applies resume progress + watched ticks together in a single rebind, so the
     * rail isn't churned by two back-to-back full refreshes.
     */
    fun setWatchState(progress: Map<String, Long>, watched: Set<String>) {
        if (progressByEpisode == progress && watchedEpisodes == watched) return
        progressByEpisode = progress
        watchedEpisodes = watched
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_WATCH_STATE)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_card, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = getItem(position)
        if (payloads.contains(PAYLOAD_ART)) holder.bindArtwork(item)
        if (payloads.contains(PAYLOAD_WATCH_STATE)) holder.bindWatchState(item)
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

            bindArtwork(episode)
            bindWatchState(episode)

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                val position = bindingAdapterPosition
                currentItem()?.let { focused ->
                    if (position != RecyclerView.NO_POSITION) onFocused(focused, position)
                }
            }
            val playable = episode.streamUrl.isNotBlank()
            itemView.alpha = if (playable) 1f else 0.52f
            itemView.isEnabled = playable
            itemView.isFocusable = playable
            itemView.isClickable = playable
            itemView.setOnClickListener(
                if (playable) {
                    View.OnClickListener { currentItem()?.let(onClicked) }
                } else {
                    null
                },
            )
        }

        fun bindArtwork(episode: Episode) {
            val placeholder = LogoPlaceholder.forName(itemView.context, episode.title)
            val art = episode.posterUrl?.takeIf { it.isNotBlank() } ?: fallbackPoster
            if (art.isNullOrBlank()) {
                // Starting a new Coil request also disposes any late URL request
                // still attached to this recycled holder.
                thumb.load(placeholder) { crossfade(false) }
            } else {
                thumb.load(art) {
                    crossfade(false)
                    placeholder(placeholder)
                    error(placeholder)
                }
            }
        }

        fun bindWatchState(episode: Episode) {
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

            itemView.contentDescription = buildList {
                add(
                    itemView.context.getString(
                        R.string.episode_se_format,
                        episode.seasonNumber,
                        episode.episodeNumber,
                    ),
                )
                add(episode.title)
                if (isWatched) add(itemView.context.getString(R.string.cd_watched))
            }.joinToString(", ")
        }

        private fun currentItem(): Episode? {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || position >= itemCount) return null
            return getItem(position)
        }
    }

    companion object {
        private const val PAYLOAD_WATCH_STATE = "watch_state"
        private const val PAYLOAD_ART = "art"

        private val DIFF = object : DiffUtil.ItemCallback<Episode>() {
            override fun areItemsTheSame(a: Episode, b: Episode) = a.id == b.id
            override fun areContentsTheSame(a: Episode, b: Episode) = a == b
        }
    }
}
