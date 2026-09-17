/*
 * VodTrackSelection.kt
 *
 * Architecture note (VOD player decomposition):
 *   Audio/subtitle track menus and the persisted language preference for the
 *   on-demand player, split out of VodPlayerActivity. Owns the preferred-track
 *   tokens, their DataStore save jobs, the debounced "re-apply preferred tracks"
 *   runnable and the Media3 once-per-generation application guard. Backend access
 *   (idle VLC player, bounded owner commands, Media3 engine/generation checks) and
 *   the active-dialog slot are reached through [Host] so the Activity keeps
 *   ownership of the players and of dialog lifecycle.
 *
 * Moved verbatim from VodPlayerActivity; labels and command names are unchanged.
 */
package com.iptv.player.ui.player

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.databinding.ActivityVodPlayerBinding
import com.iptv.player.player.vod.VodEngine
import com.iptv.player.player.vod.VodTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer

internal class VodTrackSelection(
    private val activity: Activity,
    private val binding: ActivityVodPlayerBinding,
    private val handler: Handler,
    private val settings: SettingsStore,
    private val host: Host,
) {
    interface Host {
        val castHandoffStopped: Boolean
        val playbackStarted: Boolean
        val media3Route: Boolean
        val media3Engine: VodEngine?
        val media3Generation: Long
        /** Current raw VLC player field, for identity checks against a captured handle. */
        val mediaPlayer: MediaPlayer?
        var activeDialog: Dialog?
        fun currentIdlePlayer(): MediaPlayer?
        fun isCurrentMedia3Generation(generation: Long): Boolean
        fun postBoundedCommand(label: String, command: (MediaPlayer) -> Unit): Boolean
        fun showControls()
    }

    companion object {
        const val TRACK_DISABLED = "__off__"
    }

    var preferredAudioTrack: String? = null
    var preferredSubtitleTrack: String? = null
    private var audioPreferenceJob: Job? = null
    private var subtitlePreferenceJob: Job? = null
    private var media3PreferencesAppliedGeneration = VodEngine.NO_GENERATION
    private val preferredTrackRunnable = Runnable { applyPreferredTracks() }

    fun resetMedia3PreferencesApplied() {
        media3PreferencesAppliedGeneration = VodEngine.NO_GENERATION
    }

    fun schedulePreferredTracks(delayMs: Long) {
        handler.removeCallbacks(preferredTrackRunnable)
        handler.postDelayed(preferredTrackRunnable, delayMs)
    }

    fun cancelPreferredTracks() {
        handler.removeCallbacks(preferredTrackRunnable)
    }

    fun applyMedia3PreferencesOnce(
        generation: Long,
        audio: List<VodTrack>,
        subtitles: List<VodTrack>,
    ) {
        if (media3PreferencesAppliedGeneration == generation) return
        if (!host.isCurrentMedia3Generation(generation)) return
        val engine = host.media3Engine ?: return
        val plan = VodTrackPreference.applicationPlan(
            savedAudio = preferredAudioTrack,
            savedSubtitle = preferredSubtitleTrack,
            audioCandidates = audio.map(VodTrack::label),
            subtitleCandidates = subtitles.map(VodTrack::label),
            disabledSubtitleToken = TRACK_DISABLED,
        )
        if (!plan.complete) return

        var applied = true
        plan.audioIndex?.let { index ->
            applied = engine.selectAudioTrack(audio[index].id) && applied
        }
        when {
            plan.disableSubtitles -> {
                applied = engine.selectSubtitleTrack(null) && applied
            }
            plan.subtitleIndex != null -> {
                applied = engine.selectSubtitleTrack(
                    subtitles[plan.subtitleIndex].id,
                ) && applied
            }
        }
        if (applied && host.isCurrentMedia3Generation(generation)) {
            media3PreferencesAppliedGeneration = generation
        }
    }

    // ---- Track selection ------------------------------------------------

    fun showTrackMenu(isAudio: Boolean) {
        if (host.castHandoffStopped) return
        if (host.media3Route) {
            showMedia3TrackMenu(isAudio)
            return
        }
        val mp = host.currentIdlePlayer() ?: return
        val tracks = if (isAudio) mp.audioTracks else mp.spuTracks

        val labels = mutableListOf<String>()
        val ids = mutableListOf<Int>()
        // Always offer an explicit "Off" for subtitles so it can be disabled even
        // when the media doesn't expose a disable track of its own.
        if (!isAudio) {
            labels.add(activity.getString(R.string.subtitles_off))
            ids.add(-1)
        }
        tracks?.forEach { desc ->
            // VLC may expose a synthetic "Disable" row for both track types. Do
            // not let an audio-language menu accidentally become a mute switch;
            // subtitles get our explicit, localized Off row above.
            if (desc.id != -1) {
                labels.add(desc.name)
                ids.add(desc.id)
            }
        }
        if (labels.isEmpty()) return

        val current = if (isAudio) mp.audioTrack else mp.spuTrack
        val titleRes = if (isAudio) R.string.audio_track else R.string.subtitle_track
        val options = labels.indices.map { i ->
            PlayerDialogs.Option(labels[i], ids[i] == current)
        }
        val sourceButton = if (isAudio) binding.audioButton else binding.subtitleButton
        host.activeDialog = PlayerDialogs.showOptions(activity, activity.getString(titleRes), options) { which ->
            host.activeDialog = null
            if (host.mediaPlayer !== mp || which !in ids.indices) return@showOptions
            val id = ids[which]
            val token = if (!isAudio && id == -1) {
                TRACK_DISABLED
            } else {
                VodTrackPreference.encode(labels[which])
            }
            if (isAudio) {
                host.postBoundedCommand(label = "audio track") { player ->
                    player.setAudioTrack(id)
                }
                preferredAudioTrack = token
                val store = settings
                audioPreferenceJob?.cancel()
                audioPreferenceJob =
                    ServiceLocator.appScope.launch { store.setPreferredAudioTrack(token) }
            } else {
                host.postBoundedCommand(label = "subtitle track") { player ->
                    player.setSpuTrack(id)
                }
                preferredSubtitleTrack = token
                val store = settings
                subtitlePreferenceJob?.cancel()
                subtitlePreferenceJob =
                    ServiceLocator.appScope.launch { store.setPreferredSubtitleTrack(token) }
            }
        }.apply {
            setOnDismissListener {
                host.activeDialog = null
                host.showControls()
                sourceButton.requestFocus()
            }
        }
    }

    private fun showMedia3TrackMenu(isAudio: Boolean) {
        val engine = host.media3Engine ?: return
        val tracks = (if (isAudio) engine.audioTracks() else engine.subtitleTracks())
            .filter(VodTrack::supported)
        if (tracks.isEmpty()) return
        val labels = buildList {
            if (!isAudio) add(activity.getString(R.string.subtitles_off))
            addAll(tracks.map(VodTrack::label))
        }
        val selectedIndex = if (isAudio) {
            tracks.indexOfFirst(VodTrack::selected).coerceAtLeast(0)
        } else {
            val selected = tracks.indexOfFirst(VodTrack::selected)
            if (selected >= 0) selected + 1 else 0
        }
        val titleRes = if (isAudio) R.string.audio_track else R.string.subtitle_track
        val options = labels.mapIndexed { index, label ->
            PlayerDialogs.Option(label, index == selectedIndex)
        }
        val sourceButton = if (isAudio) binding.audioButton else binding.subtitleButton
        host.activeDialog = PlayerDialogs.showOptions(activity, activity.getString(titleRes), options) { which ->
            host.activeDialog = null
            if (host.media3Engine !== engine || which !in labels.indices) return@showOptions
            if (isAudio) {
                val track = tracks.getOrNull(which) ?: return@showOptions
                engine.selectAudioTrack(track.id)
                val token = VodTrackPreference.encode(track.label)
                preferredAudioTrack = token
                audioPreferenceJob?.cancel()
                audioPreferenceJob = ServiceLocator.appScope.launch {
                    settings.setPreferredAudioTrack(token)
                }
            } else if (which == 0) {
                engine.selectSubtitleTrack(null)
                preferredSubtitleTrack = TRACK_DISABLED
                subtitlePreferenceJob?.cancel()
                subtitlePreferenceJob = ServiceLocator.appScope.launch {
                    settings.setPreferredSubtitleTrack(TRACK_DISABLED)
                }
            } else {
                val track = tracks.getOrNull(which - 1) ?: return@showOptions
                engine.selectSubtitleTrack(track.id)
                val token = VodTrackPreference.encode(track.label)
                preferredSubtitleTrack = token
                subtitlePreferenceJob?.cancel()
                subtitlePreferenceJob = ServiceLocator.appScope.launch {
                    settings.setPreferredSubtitleTrack(token)
                }
            }
        }.apply {
            setOnDismissListener {
                host.activeDialog = null
                host.showControls()
                sourceButton.requestFocus()
            }
        }
    }

    /**
     * Re-apply the viewer's last language/subtitle choice when a matching VLC
     * track exists. Tokens are human-readable track names because numeric VLC ids
     * are stream-local and change between files.
     */
    private fun applyPreferredTracks() {
        if (host.media3Route) {
            val engine = host.media3Engine ?: return
            applyMedia3PreferencesOnce(
                host.media3Generation,
                engine.audioTracks(),
                engine.subtitleTracks(),
            )
            return
        }
        val savedAudio = preferredAudioTrack
        val savedSubtitle = preferredSubtitleTrack
        val posted = host.postBoundedCommand(label = "preferred tracks") { player ->
            val audioTracks = player.audioTracks?.filter { it.id != -1 }.orEmpty()
            val audioTarget = VodTrackPreference.bestMatchIndex(
                savedAudio,
                audioTracks.map { it.name },
            )?.let(audioTracks::get)?.id
            val subtitleTracks = player.spuTracks?.filter { it.id != -1 }.orEmpty()
            val subtitleTarget = when (savedSubtitle) {
                null, "" -> null
                TRACK_DISABLED -> -1
                else -> VodTrackPreference.bestMatchIndex(
                    savedSubtitle,
                    subtitleTracks.map { it.name },
                )?.let(subtitleTracks::get)?.id
            }
            val currentAudio = player.audioTrack
            val currentSubtitle = player.spuTrack
            if (audioTarget != null && currentAudio != audioTarget) {
                player.setAudioTrack(audioTarget)
            }
            if (subtitleTarget != null && currentSubtitle != subtitleTarget) {
                player.setSpuTrack(subtitleTarget)
            }
        }
        if (!posted && host.playbackStarted && !host.media3Route) {
            handler.removeCallbacks(preferredTrackRunnable)
            handler.postDelayed(preferredTrackRunnable, NATIVE_EVENT_RETRY_MS)
        }
    }

    private val NATIVE_EVENT_RETRY_MS = 16L
}
