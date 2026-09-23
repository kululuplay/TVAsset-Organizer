/*
 * HomeInlinePlayerMenu.kt
 *
 * Architecture note (Home screen decomposition):
 *   The MENU-key option sheet of HomeActivity's inline fullscreen player and its
 *   sub-dialogs (sleep timer, audio/subtitle delay, live track chooser, stream
 *   info toast). Keeps the essential PlayerActivity controls available without
 *   changing player ownership: the preview PlayerController and CastController
 *   are read through [Host] at show time, never retained.
 *
 * Moved verbatim from HomeActivity.
 */
package com.iptv.player.ui.home

import android.app.Activity
import android.widget.Toast
import com.iptv.player.R
import com.iptv.player.cast.CastController
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Channel
import com.iptv.player.player.LiveSubtitlePreference
import com.iptv.player.player.PlayerController
import com.iptv.player.ui.common.SleepTimer
import com.iptv.player.ui.player.PlayerDialogs
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class HomeInlinePlayerMenu(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val sleepTimer: SleepTimer,
    private val host: Host,
) {
    interface Host {
        val previewingChannel: Channel?
        val previewController: PlayerController?
        val castController: CastController
        fun togglePreviewFavorite()
        fun reportPlaybackProblem()
    }

    private companion object {
        const val STATS_DASH = "—"
    }

    private fun getString(res: Int): String = activity.getString(res)
    private fun getString(res: Int, vararg args: Any): String = activity.getString(res, *args)

    /** Keeps the essential PlayerActivity controls available without changing player ownership. */
    fun show() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val channel = host.previewingChannel
        if (channel != null) {
            labels.add(
                getString(
                    if (channel.isFavorite) R.string.removed_from_favorites
                    else R.string.added_to_favorites,
                ),
            )
            actions.add(host::togglePreviewFavorite)
        }

        labels.add(getString(R.string.sleep_timer))
        actions.add(::showSleepDialog)

        val controller = host.previewController
        if (controller?.supportsDelay == true) {
            labels.add(getString(R.string.audio_delay))
            actions.add { showDelayDialog(audio = true) }
            labels.add(getString(R.string.subtitle_delay))
            actions.add { showDelayDialog(audio = false) }
        }

        if (controller?.audioTracks()?.isNotEmpty() == true) {
            labels.add(getString(R.string.audio_track))
            actions.add { showTrackDialog(audio = true) }
        }

        if (controller != null) {
            labels.add(getString(R.string.subtitle_track))
            actions.add { showTrackDialog(audio = false) }
        }

        val castController = host.castController
        if (castController.isAvailable) {
            labels.add(getString(R.string.cast))
            actions.add { castController.onCastButtonClicked() }
        }

        labels.add(getString(R.string.stream_info))
        actions.add(::showStreamInfo)

        if (channel != null) {
            labels.add(getString(R.string.support_playback_problem_channel))
            actions.add(host::reportPlaybackProblem)
        }

        PlayerDialogs.showOptions(
            activity,
            getString(R.string.player_menu),
            labels.map { PlayerDialogs.Option(it) },
        ) { index -> actions[index].invoke() }
    }

    private fun showSleepDialog() {
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = minutes.map { value ->
            if (value == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_minutes, value)
        }
        PlayerDialogs.showOptions(
            activity,
            getString(R.string.sleep_timer),
            labels.mapIndexed { index, label ->
                PlayerDialogs.Option(label, minutes[index] == sleepTimer.minutes)
            },
        ) { index ->
            val value = minutes[index]
            sleepTimer.set(value)
            Toast.makeText(
                activity,
                if (value == 0) getString(R.string.sleep_timer_off)
                else getString(R.string.sleep_timer_set, value),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showDelayDialog(audio: Boolean) {
        val controller = host.previewController ?: return
        val values = (-2000..2000 step 250).toList()
        val current =
            if (audio) controller.currentAudioDelayMs else controller.currentSubtitleDelayMs
        val title = if (audio) R.string.audio_delay else R.string.subtitle_delay
        PlayerDialogs.showOptions(
            activity,
            getString(title),
            values.map { value ->
                PlayerDialogs.Option(
                    getString(R.string.delay_ms, value),
                    selected = value.toLong() == current,
                )
            },
        ) { index ->
            val value = values[index].toLong()
            if (audio) controller.setAudioDelay(value) else controller.setSubtitleDelay(value)
        }
    }

    /** D-pad friendly live track chooser shared by preview and inline fullscreen. */
    private fun showTrackDialog(audio: Boolean) {
        val controller = host.previewController ?: return
        val tracks = if (audio) controller.audioTracks() else controller.subtitleTracks()
        if (audio && tracks.isEmpty()) return
        val options = buildList {
            if (!audio) {
                add(
                    PlayerDialogs.Option(
                        getString(R.string.subtitles_auto),
                        selected = controller.subtitlePreference is LiveSubtitlePreference.Auto,
                    ),
                )
                add(
                    PlayerDialogs.Option(
                        getString(R.string.subtitles_off),
                        selected = controller.subtitlePreference is LiveSubtitlePreference.Off,
                    ),
                )
            }
            tracks.forEach { add(PlayerDialogs.Option(it.label, it.selected)) }
        }
        PlayerDialogs.showOptions(
            activity,
            getString(if (audio) R.string.audio_track else R.string.subtitle_track),
            options,
        ) { index ->
            if (!audio && index == 0) {
                if (controller.selectSubtitleAuto()) {
                    scope.launch {
                        ServiceLocator.settings.setLiveSubtitlePreference(
                            LiveSubtitlePreference.Auto,
                        )
                    }
                }
                return@showOptions
            }
            if (!audio && index == 1) {
                if (controller.selectSubtitleTrack(null)) {
                    scope.launch {
                        ServiceLocator.settings.setLiveSubtitlePreference(
                            LiveSubtitlePreference.Off,
                        )
                    }
                }
                return@showOptions
            }
            val trackIndex = if (audio) index else index - 2
            val track = tracks.getOrNull(trackIndex) ?: return@showOptions
            val selected = if (audio) {
                controller.selectAudioTrack(track.id)
            } else {
                controller.selectSubtitleTrack(track.id)
            }
            if (selected && track.language != null) {
                scope.launch {
                    if (audio) {
                        ServiceLocator.settings.setLiveAudioLanguage(track.language)
                    } else {
                        LiveSubtitlePreference.Language.from(track.language)?.let {
                            ServiceLocator.settings.setLiveSubtitlePreference(it)
                        }
                    }
                }
            }
        }
    }

    private fun showStreamInfo() {
        val info = host.previewController?.streamInfo()
        val message = if (info == null) {
            getString(R.string.stream_info_unavailable)
        } else {
            val resolution =
                if (info.width > 0 && info.height > 0) "${info.width}×${info.height}"
                else STATS_DASH
            val fps =
                if (info.fps > 0f) String.format(Locale.US, "%.0f", info.fps)
                else STATS_DASH
            val bitrate = info.bitrateKbps?.let { "$it kbps" } ?: STATS_DASH
            buildString {
                append(getString(R.string.stream_info_engine, info.engine)).append('\n')
                append(getString(R.string.stream_info_resolution, resolution)).append('\n')
                append(getString(R.string.stream_info_fps, fps)).append('\n')
                append(getString(R.string.stream_info_codec, info.codec ?: STATS_DASH)).append('\n')
                append(getString(R.string.stream_info_bitrate, bitrate))
            }
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
